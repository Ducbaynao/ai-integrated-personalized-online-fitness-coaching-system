package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V29WorkoutExecutionMigrationIntegrationTest {
    @Test
    void cleanInstallReachesV29AndCreatesExecutionInvariants() {
        try (var db = database("workout_v29_clean")) {
            db.start();
            var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness").target("29").load().migrate();
            var jdbc = new JdbcTemplate(source);

            assertThat(jdbc.queryForObject("""
                    SELECT version::text FROM fitness.flyway_schema_history
                    WHERE success ORDER BY installed_rank DESC LIMIT 1
                    """, String.class)).isEqualTo("29");
            assertThat(jdbc.queryForList("""
                    SELECT enumlabel FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid
                    JOIN pg_namespace n ON n.oid=t.typnamespace
                    WHERE n.nspname='fitness' AND t.typname='planned_workout_status'
                    ORDER BY e.enumsortorder
                    """, String.class)).contains("ATTEMPTED");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='fitness' AND table_name='workout_session_logs'
                      AND column_name IN ('version','workout_plan_session_id','frozen_occurrence_version',
                                          'snapshot_frozen_at','snapshot_mode')
                    """, Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema='fitness' AND table_name IN
                      ('workout_execution_status_history','workout_execution_command_receipts',
                       'workout_execution_applied_adjustments')
                    """, Integer.class)).isEqualTo(3);
        }
    }

    @Test
    void unambiguousLegacyNoteIsNormalizedWithoutChangingRawHistory() {
        try (var db = database("workout_v29_legacy_note")) {
            db.start();
            var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .target("28").load().migrate();
            var jdbc = new JdbcTemplate(source);

            UUID student = UUID.randomUUID();
            UUID occurrence = UUID.randomUUID();
            UUID adjustment = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.users
                    (id,email,password_hash,display_name,status,preferred_locale,timezone)
                    VALUES (?,?,'hash','Legacy Student','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')
                    """, student, student + "@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
            jdbc.update("""
                    INSERT INTO fitness.planned_workouts
                    (id,student_id,planned_start_at,original_planned_start_at,created_by)
                    VALUES (?,?,clock_timestamp(),clock_timestamp(),?)
                    """, occurrence, student, student);
            jdbc.update("""
                    INSERT INTO fitness.workout_session_adjustments
                    (id,planned_workout_id,adjustment_type,before_value,after_value,reason,adjusted_by)
                    VALUES (?,?,'NOTE',?::jsonb,?::jsonb,'Legacy note',?)
                    """, adjustment, occurrence, "{\"note\":\"Old note\"}",
                    "{\"note\":\"Keep range of motion controlled\"}", student);

            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .target("29").load().migrate();

            var normalized = jdbc.queryForMap("""
                    SELECT resolution_state,adjustment_contract_version,typed_note,
                           before_value::text before_value,after_value::text after_value
                    FROM fitness.workout_session_adjustments WHERE id=?
                    """, adjustment);
            assertThat(normalized.get("resolution_state")).isEqualTo("TYPED");
            assertThat(normalized.get("adjustment_contract_version")).isEqualTo(1);
            assertThat(normalized.get("typed_note")).isEqualTo("Keep range of motion controlled");
            assertThat(normalized.get("before_value").toString()).contains("Old note");
            assertThat(normalized.get("after_value").toString()).contains("Keep range of motion controlled");
        }
    }

    @Test
    void cleanV28UpgradeReachesV29() {
        try (var db = database("workout_v29_clean_upgrade")) {
            db.start();
            var source = source(db);
            migrate(source, "28");
            migrate(source, "29");
            var jdbc = new JdbcTemplate(source);

            assertThat(currentVersion(jdbc)).isEqualTo("29");
            assertThat(enumLabels(jdbc)).contains("ATTEMPTED");
            assertThat(columnExists(jdbc, "planned_workouts", "execution_started_at")).isTrue();
            assertThat(tableExists(jdbc, "workout_execution_command_receipts")).isTrue();
        }
    }

    @Test
    void safeLegacyTerminalExecutionIsPreservedReferenceOnlyAndProtected() {
        try (var db = database("workout_v29_legacy_terminal")) {
            db.start();
            var source = source(db);
            migrate(source, "28");
            var jdbc = new JdbcTemplate(source);
            LegacyGraph graph = legacyTerminalGraph(jdbc, "COMPLETED", true);

            migrate(source, "29");

            var execution = jdbc.queryForMap("""
                    SELECT status::text status,notes,snapshot_mode,workout_plan_session_id,
                           frozen_occurrence_version,snapshot_frozen_at
                    FROM fitness.workout_session_logs WHERE id=?
                    """, graph.executionId());
            assertThat(execution.get("status")).isEqualTo("COMPLETED");
            assertThat(execution.get("notes")).isEqualTo("Legacy actual facts");
            assertThat(execution.get("snapshot_mode")).isEqualTo("LEGACY_REFERENCE_ONLY");
            assertThat(execution.get("workout_plan_session_id")).isNull();
            assertThat(execution.get("frozen_occurrence_version")).isNull();
            assertThat(execution.get("snapshot_frozen_at")).isNull();
            assertThat(jdbc.queryForObject("SELECT baseline_set_number FROM fitness.set_logs WHERE id=?",
                    Integer.class, graph.setId())).isNull();
            assertThat(jdbc.queryForObject("SELECT repetitions FROM fitness.set_logs WHERE id=?",
                    Integer.class, graph.setId())).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT actual_exercise_variation_id FROM fitness.exercise_logs WHERE id=?",
                    UUID.class, graph.exerciseLogId())).isEqualTo(graph.variationId());
            assertThatThrownBy(() -> jdbc.update("UPDATE fitness.workout_session_logs SET notes='changed',version=version+1 WHERE id=?", graph.executionId()))
                    .isInstanceOf(DataAccessException.class).hasRootCauseMessage("ERROR: Terminal workout execution is immutable\n  Where: PL/pgSQL function guard_workout_execution() line 4 at RAISE");
        }
    }

    @Test
    void legacyInProgressFailsWithExplicitDiagnosticAndRollsBackV29() {
        try (var db = database("workout_v29_legacy_in_progress")) {
            db.start();
            var source = source(db);
            migrate(source, "28");
            var jdbc = new JdbcTemplate(source);
            legacyTerminalGraph(jdbc, "IN_PROGRESS", false);

            assertThatThrownBy(() -> migrate(source, "29"))
                    .isInstanceOf(FlywayException.class)
                    .hasRootCauseMessage("ERROR: V29 cannot migrate legacy IN_PROGRESS workout executions without a frozen snapshot\n  Where: PL/pgSQL function inline_code_block line 4 at RAISE");
            assertThat(currentVersion(jdbc)).isEqualTo("28");
            assertThat(columnExists(jdbc, "workout_session_logs", "snapshot_mode")).isFalse();
        }
    }

    @Test
    void duplicateLegacyExecutionsForOccurrenceFailWithoutSelectingWinner() {
        try (var db = database("workout_v29_legacy_duplicate")) {
            db.start();
            var source = source(db);
            migrate(source, "28");
            var jdbc = new JdbcTemplate(source);
            UUID student = legacyStudent(jdbc);
            UUID occurrence = legacyOccurrence(jdbc, student);
            legacyExecution(jdbc, student, occurrence, "ABORTED", "First");
            legacyExecution(jdbc, student, occurrence, "ABORTED", "Second");

            assertThatThrownBy(() -> migrate(source, "29"))
                    .isInstanceOf(FlywayException.class)
                    .hasRootCauseMessage("ERROR: V29 found duplicate legacy workout executions for one planned workout\n  Where: PL/pgSQL function inline_code_block line 11 at RAISE");
            assertThat(currentVersion(jdbc)).isEqualTo("28");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs WHERE planned_workout_id=?",
                    Integer.class, occurrence)).isEqualTo(2);
        }
    }

    @Test
    void ambiguousLegacyAdjustmentRemainsUnresolvedWithoutChangingRawHistory() {
        try (var db = database("workout_v29_legacy_ambiguous")) {
            db.start();
            var source = source(db);
            migrate(source, "28");
            var jdbc = new JdbcTemplate(source);
            UUID student = legacyStudent(jdbc);
            UUID occurrence = legacyOccurrence(jdbc, student);
            UUID adjustment = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.workout_session_adjustments
                    (id,planned_workout_id,adjustment_type,before_value,after_value,reason,adjusted_by)
                    VALUES (?,?,'NOTE',?::jsonb,?::jsonb,'Ambiguous legacy note',?)
                    """, adjustment, occurrence, "{\"note\":\"old\"}",
                    "{\"note\":\"new\",\"unsupported\":true}", student);

            migrate(source, "29");

            var row = jdbc.queryForMap("""
                    SELECT resolution_state,adjustment_contract_version,typed_note,
                           before_value::text before_value,after_value::text after_value
                    FROM fitness.workout_session_adjustments WHERE id=?
                    """, adjustment);
            assertThat(row.get("resolution_state")).isEqualTo("LEGACY_UNRESOLVED");
            assertThat(row.get("adjustment_contract_version")).isEqualTo(0);
            assertThat(row.get("typed_note")).isNull();
            assertThat(row.get("before_value").toString()).contains("old");
            assertThat(row.get("after_value").toString()).contains("unsupported");
        }
    }

    @Test
    void attemptedStatusPersistsAndIsTerminal() {
        try (var db = database("workout_v29_attempted")) {
            db.start();
            var source = source(db);
            migrate(source, "29");
            var jdbc = new JdbcTemplate(source);
            UUID student = legacyStudent(jdbc);
            UUID occurrence = legacyOccurrence(jdbc, student);

            jdbc.update("UPDATE fitness.planned_workouts SET status='ATTEMPTED',version=version+1 WHERE id=?", occurrence);
            assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.planned_workouts WHERE id=?",
                    String.class, occurrence)).isEqualTo("ATTEMPTED");
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fitness.planned_workouts SET status='COMPLETED',version=version+1 WHERE id=?", occurrence))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void terminalExecutionAndChildrenRejectUpdatesAndHardDeletes() {
        try (var db = database("workout_v29_terminal_guards")) {
            db.start();
            var source = source(db);
            migrate(source, "28");
            var jdbc = new JdbcTemplate(source);
            LegacyGraph graph = legacyTerminalGraph(jdbc, "COMPLETED", true);
            migrate(source, "29");

            assertThatThrownBy(() -> jdbc.update("UPDATE fitness.workout_session_logs SET notes='x',version=version+1 WHERE id=?", graph.executionId())).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE fitness.exercise_logs SET notes='x' WHERE id=?", graph.exerciseLogId())).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE fitness.set_logs SET repetitions=10 WHERE id=?", graph.setId())).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM fitness.set_logs WHERE id=?", graph.setId())).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM fitness.exercise_logs WHERE id=?", graph.exerciseLogId())).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM fitness.workout_session_logs WHERE id=?", graph.executionId())).isInstanceOf(DataAccessException.class);
        }
    }

    private DriverManagerDataSource source(PostgreSQLContainer<?> db) {
        return new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private void migrate(DriverManagerDataSource source, String target) {
        Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness").target(target).load().migrate();
    }

    private String currentVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT version::text FROM fitness.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class);
    }

    private java.util.List<String> enumLabels(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT enumlabel FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid
                JOIN pg_namespace n ON n.oid=t.typnamespace
                WHERE n.nspname='fitness' AND t.typname='planned_workout_status'
                ORDER BY e.enumsortorder
                """, String.class);
    }

    private boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM information_schema.columns
                WHERE table_schema='fitness' AND table_name=? AND column_name=?)
                """, Boolean.class, table, column));
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM information_schema.tables
                WHERE table_schema='fitness' AND table_name=?)
                """, Boolean.class, table));
    }

    private UUID legacyStudent(JdbcTemplate jdbc) {
        UUID student = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'hash','Legacy Student','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')
                """, student, student + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
        return student;
    }

    private UUID legacyOccurrence(JdbcTemplate jdbc, UUID student) {
        UUID occurrence = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,planned_start_at,original_planned_start_at,created_by)
                VALUES (?,?,clock_timestamp(),clock_timestamp(),?)
                """, occurrence, student, student);
        return occurrence;
    }

    private UUID legacyExecution(JdbcTemplate jdbc, UUID student, UUID occurrence, String status, String notes) {
        UUID execution = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_session_logs
                (id,student_id,planned_workout_id,performed_start_at,performed_end_at,status,notes,logged_by)
                VALUES (?,?,?,clock_timestamp()-interval '1 hour',
                        CASE WHEN ?='IN_PROGRESS' THEN NULL ELSE clock_timestamp() END,
                        ?::fitness.actual_workout_status,?,?)
                """, execution, student, occurrence, status, status, notes, student);
        return execution;
    }

    private LegacyGraph legacyTerminalGraph(JdbcTemplate jdbc, String status, boolean children) {
        UUID student = legacyStudent(jdbc);
        UUID occurrence = legacyOccurrence(jdbc, student);
        UUID execution = legacyExecution(jdbc, student, occurrence, status, "Legacy actual facts");
        if (!children) return new LegacyGraph(execution, null, null, null);
        UUID exercise = UUID.randomUUID();
        UUID variation = UUID.randomUUID();
        UUID exerciseLog = UUID.randomUUID();
        UUID set = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                exercise, "legacy-" + exercise, "Legacy exercise", student);
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                variation, exercise, "legacy-variation-" + variation, "Legacy variation");
        jdbc.update("""
                INSERT INTO fitness.exercise_logs
                (id,workout_session_log_id,exercise_variation_id,sequence_number,notes)
                VALUES (?,?,?,1,'Legacy exercise facts')
                """, exerciseLog, execution, variation);
        jdbc.update("""
                INSERT INTO fitness.set_logs
                (id,exercise_log_id,set_number,set_type,completion_status,repetitions,notes)
                VALUES (?,?,1,'WORKING','COMPLETED',9,'Legacy set facts')
                """, set, exerciseLog);
        return new LegacyGraph(execution, exerciseLog, set, variation);
    }

    private record LegacyGraph(UUID executionId, UUID exerciseLogId, UUID setId, UUID variationId) {}

    private PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18")
                .asCompatibleSubstituteFor("postgres")).withDatabaseName(name)
                .withUsername("test_user").withPassword("test_pass");
    }
}
