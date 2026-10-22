package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V46WorkoutRecurrenceMigrationIntegrationTest {
    private static PostgreSQLContainer<?> disposable(String database) {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18")
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(database).withUsername("fitness_app").withPassword("testpass123");
    }

    @Test void cleanInstallAddsSeparateWorkoutSeriesAndMaterializedRowProtection() {
        try (var db = disposable("recurrence_v46_clean_disposable")) {
            db.start();
            var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            migrate(source, null);
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.flyway_schema_history "
                    + "WHERE version='46' AND success", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='fitness' "
                    + "AND indexname='uq_workout_schedule_materialized_source_instant_v46'", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname="
                    + "'ex_workout_schedule_materialized_occupied_v46'", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_series",
                    Integer.class)).isZero();
            assertVersionBoundItemForeignKeys(jdbc);
        }
    }

    @Test void upgradePreservesLegacyUnknownEndWithoutEnrollingItIntoNewConstraint() {
        try (var db = disposable("recurrence_v46_upgrade_disposable")) {
            db.start();
            var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            migrate(source, "45");
            var jdbc = new JdbcTemplate(source);
            UUID student = UUID.randomUUID(), legacy = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status) "
                    + "VALUES (?,?,'hash','Legacy','ACTIVE')", student, student + "@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
            jdbc.update("INSERT INTO fitness.planned_workouts "
                    + "(id,student_id,planned_start_at,original_planned_start_at,created_by) "
                    + "VALUES (?,?,clock_timestamp()+interval '10 days',clock_timestamp()+interval '10 days',?)",
                    legacy, student, student);
            migrate(source, null);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts "
                    + "WHERE id=? AND planned_end_at IS NULL AND series_period_id IS NULL", Integer.class,
                    legacy)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods",
                    Integer.class)).isZero();
            assertVersionBoundItemForeignKeys(jdbc);
        }
    }

    private static void assertVersionBoundItemForeignKeys(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname IN "
                + "('fk_workout_recurrence_item_series_version_v46',"
                + "'fk_workout_recurrence_item_session_version_v46') AND contype='f'",
                Integer.class)).isEqualTo(2);
    }

    private static void migrate(DriverManagerDataSource source, String target) {
        var config = Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                .locations("classpath:db/migration");
        if (target != null) config.target(MigrationVersion.fromVersion(target));
        config.load().migrate();
    }
}
