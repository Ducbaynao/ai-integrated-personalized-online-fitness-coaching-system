package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V33WorkoutScheduleChangeMigrationIntegrationTest {
    @Test void cleanInstallAndUpgradePreserveLegacyUnknownEnd() {
        try (var db = database("workout_change_v33")) {
            db.start();
            var source = source(db);
            migrate(source, "32");
            var jdbc = new JdbcTemplate(source);
            UUID student = UUID.randomUUID(), occurrence = UUID.randomUUID();
            person(jdbc, student);
            jdbc.update("""
                    INSERT INTO fitness.planned_workouts
                    (id,student_id,planned_start_at,original_planned_start_at,created_by)
                    VALUES (?,?,clock_timestamp()+interval '3 days',clock_timestamp()+interval '3 days',?)
                    """, occurrence, student, student);
            migrate(source, "33");
            assertThat(jdbc.queryForObject("""
                    SELECT planned_end_at IS NULL AND schedule_batch_id IS NULL AND schedule_change_id IS NULL
                    FROM fitness.planned_workouts WHERE id=?
                    """, Boolean.class, occurrence)).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
                    WHERE t.relname='planned_workouts' AND c.conname='ex_workout_schedule_materialized_occupied_v33'
                    """, Integer.class)).isOne();
        }
    }

    @Test void ambiguousLegacyLineageFailsUpgradeWithoutChangingRows() {
        try (var db = database("workout_change_ambiguous")) {
            db.start();
            var source = source(db);
            migrate(source, "32");
            var jdbc = new JdbcTemplate(source);
            UUID student = UUID.randomUUID(), parent = UUID.randomUUID(), childA = UUID.randomUUID(),
                    childB = UUID.randomUUID();
            person(jdbc, student);
            jdbc.update("""
                    INSERT INTO fitness.planned_workouts
                    (id,student_id,planned_start_at,original_planned_start_at,created_by)
                    VALUES (?,?,clock_timestamp()+interval '3 days',clock_timestamp()+interval '3 days',?)
                    """, parent, student, student);
            for (UUID child : new UUID[]{childA, childB}) jdbc.update("""
                    INSERT INTO fitness.planned_workouts
                    (id,student_id,planned_start_at,original_planned_start_at,rescheduled_from_id,created_by)
                    VALUES (?,?,clock_timestamp()+interval '4 days',clock_timestamp()+interval '3 days',?,?)
                    """, child, student, parent, student);
            assertThatThrownBy(() -> migrate(source, "33"))
                    .hasMessageContaining("Ambiguous legacy planned-workout reschedule lineage");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?
                    """, Integer.class, parent)).isEqualTo(2);
        }
    }

    private static void person(JdbcTemplate jdbc, UUID student) {
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'hash','Legacy','ACTIVE','vi-VN','Etc/UTC')
                """, student, student + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
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
}
