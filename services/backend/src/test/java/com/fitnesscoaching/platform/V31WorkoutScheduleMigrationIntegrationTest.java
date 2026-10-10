package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V31WorkoutScheduleMigrationIntegrationTest {
    @Test void cleanInstallReachesV31WithBatchConstraints() {
        try (var db = database("workout_v31_clean")) {
            db.start();
            var source = source(db);
            migrate(source, "31");
            var jdbc = new JdbcTemplate(source);
            assertThat(version(jdbc)).isEqualTo("31");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
                    JOIN pg_namespace n ON n.oid=t.relnamespace
                    WHERE n.nspname='fitness' AND t.relname='planned_workouts'
                      AND c.conname='ex_workout_schedule_new_occupied_range'
                    """, Integer.class)).isOne();
        }
    }

    @Test void upgradePreservesConflictingAndUnknownEndLegacyRowsWithoutBackfill() {
        try (var db = database("workout_v31_legacy")) {
            db.start();
            var source = source(db);
            migrate(source, "30");
            var jdbc = new JdbcTemplate(source);
            UUID student = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                    VALUES (?,?, 'hash','Legacy','ACTIVE','vi-VN','Etc/UTC')
                    """, student, student + "@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
            jdbc.update("""
                    INSERT INTO fitness.planned_workouts
                    (id,student_id,planned_start_at,planned_end_at,original_planned_start_at,created_by)
                    VALUES (?,?,clock_timestamp()+interval '3 days',NULL,clock_timestamp()+interval '3 days',?)
                    """, first, student, student);
            jdbc.update("""
                    INSERT INTO fitness.planned_workouts
                    (id,student_id,planned_start_at,planned_end_at,original_planned_start_at,created_by)
                    VALUES (?,?,clock_timestamp()+interval '3 days',clock_timestamp()+interval '4 days',
                            clock_timestamp()+interval '3 days',?)
                    """, second, student, student);
            jdbc.update("""
                    INSERT INTO fitness.planned_workouts
                    (id,student_id,planned_start_at,planned_end_at,original_planned_start_at,created_by)
                    VALUES (?,?,clock_timestamp()+interval '3 days 12 hours',
                            clock_timestamp()+interval '4 days 12 hours',
                            clock_timestamp()+interval '3 days 12 hours',?)
                    """, third, student, student);
            migrate(source, "31");
            assertThat(version(jdbc)).isEqualTo("31");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.planned_workouts
                    WHERE id IN (?,?,?) AND schedule_batch_id IS NULL
                    """, Integer.class, first, second, third)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT planned_end_at IS NULL FROM fitness.planned_workouts WHERE id=?",
                    Boolean.class, first)).isTrue();
        }
    }

    private static PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18")
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(name).withUsername("fitness_app").withPassword("testpass123");
    }
    private static DriverManagerDataSource source(PostgreSQLContainer<?> db) {
        return new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }
    private static void migrate(DriverManagerDataSource source, String target) {
        Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                .target(target).load().migrate();
    }
    private static String version(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version::text FROM fitness.flyway_schema_history
                WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
    }
}
