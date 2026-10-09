package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase.*;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionReadPort;
import com.fitnesscoaching.platform.modules.workout.domain.SetExecution;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionStatus;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class WorkoutExecutionPersistenceIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("workout_execution_b05").withUsername("fitness_app").withPassword("testpass123");
    static { postgres.start(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired WorkoutExecutionCommandUseCase commands;
    @Autowired WorkoutExecutionReadPort reads;
    @Autowired WorkoutExecutionQueryUseCase queries;
    @Autowired JdbcTemplate jdbc;

    @Test
    void startSetCompleteAndCommandReplaysAreAtomicAndIdempotent() {
        Fixture f = fixture();
        var started = commands.start(new StartCommand(f.occurrenceId(), f.studentId(), 0, "start-" + f.occurrenceId()));
        var replayedStart = commands.start(new StartCommand(f.occurrenceId(), f.studentId(), 0,
                "start-" + f.occurrenceId()));

        assertThat(started.status()).isEqualTo(WorkoutExecutionStatus.IN_PROGRESS);
        assertThat(replayedStart.id()).isEqualTo(started.id());
        assertThat(started.exercises()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT execution_started_at IS NOT NULL FROM fitness.planned_workouts WHERE id=?",
                Boolean.class, f.occurrenceId())).isTrue();

        UUID clientSetId = UUID.randomUUID();
        SetExecution set = new SetExecution(null, clientSetId, 1, 1, "WORKING", "COMPLETED",
                10, null, null, null, null, null, null, null, null, null, "Đủ biên độ");
        var afterSet = commands.upsertSet(new UpsertSetCommand(started.id(), started.exercises().getFirst().id(),
                f.studentId(), 0, set));
        var identicalRetry = commands.upsertSet(new UpsertSetCommand(started.id(), started.exercises().getFirst().id(),
                f.studentId(), 0, set));

        assertThat(afterSet.version()).isEqualTo(1);
        assertThat(identicalRetry.version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.set_logs WHERE client_set_id=?",
                Integer.class, clientSetId)).isOne();

        TerminalCommand complete = new TerminalCommand(started.id(), f.studentId(), 1,
                "complete-" + started.id(), null, "Hoàn tất");
        var completed = commands.complete(complete);
        var replayedComplete = commands.complete(complete);
        assertThat(completed.status()).isEqualTo(WorkoutExecutionStatus.COMPLETED);
        assertThat(replayedComplete.id()).isEqualTo(completed.id());
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.planned_workouts WHERE id=?",
                String.class, f.occurrenceId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_execution_command_receipts WHERE actor_id=?",
                Integer.class, f.studentId())).isEqualTo(2);
    }

    @Test
    void unresolvedLegacyAdjustmentRejectsFreezeWithoutSealingOccurrence() {
        Fixture f = fixture();
        jdbc.update("""
                INSERT INTO fitness.workout_session_adjustments
                (planned_workout_id,adjustment_type,after_value,reason,adjusted_by)
                VALUES (?,'NOTE',?::jsonb,'Ambiguous legacy payload',?)
                """, f.occurrenceId(), "{\"note\":\"new\",\"unsupported\":true}", f.studentId());

        assertThatThrownBy(() -> commands.start(new StartCommand(
                f.occurrenceId(), f.studentId(), 0, "start-unresolved-" + f.occurrenceId())))
                .isInstanceOfSatisfying(WorkoutExecutionFailure.class, failure ->
                        assertThat(failure.error()).isEqualTo(WorkoutExecutionError.WORKOUT_EXECUTION_BASELINE_INVALID));
        assertThat(jdbc.queryForObject("SELECT execution_started_at FROM fitness.planned_workouts WHERE id=?",
                java.sql.Timestamp.class, f.occurrenceId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs WHERE planned_workout_id=?",
                Integer.class, f.occurrenceId())).isZero();
    }

    @Test
    void startPersistsFrozenReadMetadataAndLaterSourceSupervisionChangeCannotRewriteIt() {
        Fixture f = fixture();
        var expected = jdbc.queryForMap("""
                SELECT planned_start_at,planned_end_at,original_planned_start_at,supervision_requirement::text
                FROM fitness.planned_workouts WHERE id=?
                """, f.occurrenceId());

        var started = commands.start(new StartCommand(f.occurrenceId(), f.studentId(), 0,
                "metadata-" + f.occurrenceId()));
        jdbc.update("""
                UPDATE fitness.planned_workouts SET supervision_requirement='COACH_PREFERRED',version=version+1
                WHERE id=?
                """, f.occurrenceId());

        var read = reads.findDetail(started.id()).orElseThrow();
        assertThat(read.planId()).isEqualTo(f.planId());
        assertThat(read.planVersionId()).isEqualTo(f.planVersionId());
        assertThat(read.planSessionId()).isEqualTo(f.planSessionId());
        assertThat(read.coachingPeriodId()).isEqualTo(f.coachingPeriodId());
        assertThat(java.sql.Timestamp.from(read.plannedStartAt()))
                .isEqualTo(expected.get("planned_start_at"));
        assertThat(java.sql.Timestamp.from(read.originalPlannedStartAt()))
                .isEqualTo(expected.get("original_planned_start_at"));
        assertThat(read.plannedEndAt()).isNull();
        assertThat(read.supervisionRequirement()).isEqualTo(expected.get("supervision_requirement"));
        assertThat(read.supervisionRequirement()).isEqualTo("SELF_ALLOWED");
        assertThat(read.frozenAt()).isNotNull();
        assertThat(read.sourceOccurrenceVersion()).isEqualTo(1);
    }

    @Test
    void commandKeyLengthMatchesDatabaseInvariantBeforePersistence() {
        Fixture accepted = fixture();
        assertThat(commands.start(new StartCommand(accepted.occurrenceId(), accepted.studentId(), 0,
                "k".repeat(120))).id()).isNotNull();

        Fixture rejected = fixture();
        assertThatThrownBy(() -> commands.start(new StartCommand(rejected.occurrenceId(), rejected.studentId(), 0,
                "k".repeat(121))))
                .isInstanceOfSatisfying(WorkoutExecutionFailure.class, failure ->
                        assertThat(failure.error()).isEqualTo(WorkoutExecutionError.VALIDATION_FAILED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs WHERE planned_workout_id=?",
                Integer.class, rejected.occurrenceId())).isZero();
    }

    @Test
    void terminalHistoryPresentsCurrentCatalogLabelsForRetainedExerciseAndSetUnitIds() {
        short kilograms = unit("KG", "kg", "MASS");
        short pounds = unit("LB", "lb", "MASS");
        short metres = unit("M", "m", "LENGTH");
        Fixture f = fixture(kilograms, metres);

        var started = commands.start(new StartCommand(f.occurrenceId(), f.studentId(), 0,
                "unit-start-" + f.occurrenceId()));
        UUID clientSetId = UUID.randomUUID();
        var set = new SetExecution(null, clientSetId, 1, 1, "WORKING", "COMPLETED", 8,
                new BigDecimal("175"), pounds, 30, new BigDecimal("10"), metres,
                null, null, null, null, null);
        commands.upsertSet(new UpsertSetCommand(started.id(), started.exercises().getFirst().id(),
                f.studentId(), 0, set));
        commands.complete(new TerminalCommand(started.id(), f.studentId(), 1,
                "unit-complete-" + started.id(), null, null));

        jdbc.update("UPDATE fitness.measurement_units SET symbol='kg-current' WHERE id=?", kilograms);
        var detail = queries.history(f.studentId(), f.studentId(), 0, 20).items().stream()
                .filter(item -> item.executionId().equals(started.id())).findFirst().orElseThrow();
        var exercise = detail.exercises().getFirst();

        assertThat(detail.status()).isEqualTo(WorkoutExecutionStatus.COMPLETED);
        assertThat(exercise.loadUnitId()).isEqualTo(kilograms);
        assertThat(exercise.loadUnit().id()).isEqualTo(kilograms);
        assertThat(exercise.loadUnit().code()).isEqualTo("KG");
        assertThat(exercise.loadUnit().symbol()).isEqualTo("kg-current");
        assertThat(exercise.distanceUnit().id()).isEqualTo(metres);
        assertThat(exercise.distanceUnit().symbol()).isEqualTo("m");
        assertThat(exercise.sets().getFirst().loadUnitId()).isEqualTo(pounds);
        assertThat(exercise.sets().getFirst().loadUnit().code()).isEqualTo("LB");
        assertThat(exercise.sets().getFirst().loadUnit().symbol()).isEqualTo("lb");
        assertThat(exercise.sets().getFirst().distanceUnit().id()).isEqualTo(metres);
    }

    private Fixture fixture() {
        return fixture(null, null);
    }

    private Fixture fixture(Short loadUnitId, Short distanceUnitId) {
        UUID student = person();
        UUID period = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,created_by) VALUES (?,?,'SELF_DIRECTED',clock_timestamp()-interval '1 day',?)",
                period, student, student);
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID(), session = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,coaching_period_id,name,source,status,created_by,decision_owner_type,decision_owner_id)
                VALUES (?,?,?,'B05','STUDENT','DRAFT',?,'STUDENT',?)
                """, plan, student, period, student, student);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions
                (id,workout_plan_id,version_number,change_level,created_by)
                VALUES (?,?,1,'INITIAL',?)
                """, version, plan, student);
        jdbc.update("INSERT INTO fitness.workout_plan_sessions(id,workout_plan_version_id,week_number,day_number,sequence_number,name) VALUES (?,?,1,1,1,'Buổi 1')",
                session, version);
        UUID variation = variation(student);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_session_exercises
                (id,workout_plan_session_id,exercise_variation_id,sequence_number,target_sets,target_reps_min,
                 target_reps_max,target_load,load_unit_id,distance_value,distance_unit_id)
                VALUES (?,?,?,1,1,8,12,?,?,?,?)
                """, UUID.randomUUID(), session, variation, loadUnitId == null ? null : new BigDecimal("80"),
                loadUnitId, distanceUnitId == null ? null : new BigDecimal("100"), distanceUnitId);
        jdbc.update("""
                UPDATE fitness.workout_plan_versions
                SET effective_from=clock_timestamp()-interval '1 day',locked_at=clock_timestamp(),
                    locked_by=?,lock_reason='ACTIVATED' WHERE id=?
                """, student, version);
        jdbc.update("UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1 WHERE id=?", plan);
        UUID occurrence = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,coaching_period_id,planned_start_at,
                 original_planned_start_at,created_by)
                VALUES (?,?,?, ?,clock_timestamp(),clock_timestamp(),?)
                """, occurrence, student, session, period, student);
        return new Fixture(student, occurrence, plan, version, session, period);
    }

    private UUID person() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?,'hash','Student','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')",
                id, id + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='STUDENT'",
                id, id);
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        return id;
    }

    private UUID variation(UUID creator) {
        UUID exercise = UUID.randomUUID(), variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                exercise, "exercise-" + exercise, "Bài tập", creator);
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                variation, exercise, "variation-" + variation, "Biến thể");
        return variation;
    }

    private short unit(String code, String symbol, String dimension) {
        return jdbc.queryForObject("""
                INSERT INTO fitness.measurement_units(code,symbol,dimension,base_unit_code,multiplier_to_base,
                    offset_to_base)
                VALUES (?,?,?,?,1,0)
                ON CONFLICT (code) DO UPDATE SET symbol=EXCLUDED.symbol,dimension=EXCLUDED.dimension
                RETURNING id
                """, Short.class, code, symbol, dimension, code);
    }

    private record Fixture(UUID studentId, UUID occurrenceId, UUID planId, UUID planVersionId,
                           UUID planSessionId, UUID coachingPeriodId) {}
}
