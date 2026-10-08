package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V30WorkoutExecutionReadProjectionMigrationIntegrationTest {
    @Test
    void cleanInstallReachesV30AndAddsFrozenReadMetadata() {
        try (var db = database("workout_v30_clean")) {
            db.start();
            var source = source(db);
            migrate(source, "30");
            var jdbc = new JdbcTemplate(source);

            assertThat(currentVersion(jdbc)).isEqualTo("30");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='fitness' AND table_name='workout_session_logs'
                      AND column_name IN ('source_workout_plan_id','frozen_planned_start_at',
                          'frozen_planned_end_at','frozen_original_planned_start_at',
                          'frozen_supervision_requirement','snapshot_contract_version')
                    """, Integer.class)).isEqualTo(6);
        }
    }

    @Test
    void v29UpgradePreservesLegacyUnknownsAndNewFrozenRowsRequireVersionedMetadata() {
        try (var db = database("workout_v30_upgrade")) {
            db.start();
            var source = source(db);
            migrate(source, "29");
            var jdbc = new JdbcTemplate(source);
            UUID student = student(jdbc); UUID execution = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.workout_session_logs
                    (id,student_id,performed_start_at,performed_end_at,status,logged_by)
                    VALUES (?,?,clock_timestamp()-interval '1 hour',clock_timestamp(),'COMPLETED',?)
                    """, execution, student, student);

            migrate(source, "30");

            var row = jdbc.queryForMap("""
                    SELECT snapshot_mode,source_workout_plan_id,frozen_planned_start_at,
                           frozen_supervision_requirement,snapshot_contract_version
                    FROM fitness.workout_session_logs WHERE id=?
                    """, execution);
            assertThat(row.get("snapshot_mode")).isEqualTo("LEGACY_REFERENCE_ONLY");
            assertThat(row.get("source_workout_plan_id")).isNull();
            assertThat(row.get("frozen_planned_start_at")).isNull();
            assertThat(row.get("frozen_supervision_requirement")).isNull();
            assertThat(((Number) row.get("snapshot_contract_version")).intValue()).isZero();

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO fitness.workout_session_logs
                    (id,student_id,performed_start_at,status,logged_by,snapshot_mode)
                    VALUES (?,?,clock_timestamp(),'IN_PROGRESS',?,'FROZEN')
                    """, UUID.randomUUID(), student, student)).isInstanceOf(DataAccessException.class);
        }
    }

    private static UUID student(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'hash','Legacy Student','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')
                """, id, id + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        return id;
    }

    private static void migrate(DriverManagerDataSource source, String target) {
        Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                .target(target).load().migrate();
    }

    private static String currentVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version::text FROM fitness.flyway_schema_history
                WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
    }

    private static DriverManagerDataSource source(PostgreSQLContainer<?> db) {
        return new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private static PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18")
                .asCompatibleSubstituteFor("postgres")).withDatabaseName(name)
                .withUsername("test_user").withPassword("test_pass");
    }
}
