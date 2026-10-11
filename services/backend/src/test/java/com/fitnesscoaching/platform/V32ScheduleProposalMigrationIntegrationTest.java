package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V32ScheduleProposalMigrationIntegrationTest {
    @Test void cleanInstallCreatesProposalAndSharedReceiptConstraints() {
        try (var db = database("schedule_v32_clean")) {
            db.start();
            var source = source(db);
            migrate(source, "32");
            var jdbc = new JdbcTemplate(source);
            assertThat(version(jdbc)).isEqualTo("32");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
                    JOIN pg_namespace n ON n.oid=t.relnamespace
                    WHERE n.nspname='fitness' AND t.relname='workout_schedule_proposals'
                      AND c.conname='workout_schedule_proposal_terminal_ck'
                    """, Integer.class)).isOne();
        }
    }

    @Test void upgradePreservesLegacyUnknownEndAndDirectReceiptWithoutBackfill() {
        try (var db = database("schedule_v32_upgrade")) {
            db.start();
            var source = source(db);
            migrate(source, "31");
            var jdbc = new JdbcTemplate(source);
            UUID student = UUID.randomUUID(), occurrence = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                    VALUES (?,?,'hash','Legacy','ACTIVE','vi-VN','Etc/UTC')
                    """, student, student + "@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
            jdbc.update("""
                    INSERT INTO fitness.planned_workouts
                    (id,student_id,planned_start_at,original_planned_start_at,created_by)
                    VALUES (?,?,clock_timestamp()+interval '3 days',clock_timestamp()+interval '3 days',?)
                    """, occurrence, student, student);
            migrate(source, "32");
            assertThat(version(jdbc)).isEqualTo("32");
            assertThat(jdbc.queryForObject("""
                    SELECT planned_end_at IS NULL FROM fitness.planned_workouts WHERE id=?
                    """, Boolean.class, occurrence)).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.workout_schedule_proposals
                    """, Integer.class)).isZero();
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
