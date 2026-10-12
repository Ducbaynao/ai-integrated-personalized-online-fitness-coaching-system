package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V34ScheduleChangeProposalMigrationIntegrationTest {
    @Test void v33UpgradeKeepsLegacyUnknownEndAndAddsProposalStore() {
        try (var db = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18")
                .asCompatibleSubstituteFor("postgres")).withDatabaseName("change_proposal_upgrade")
                .withUsername("fitness_app").withPassword("testpass123")) {
            db.start();
            var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            migrate(source, "33");
            var jdbc = new JdbcTemplate(source);
            UUID student = UUID.randomUUID(), old = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                    VALUES (?,?,'hash','Legacy','ACTIVE','vi-VN','Etc/UTC')
                    """, student, student + "@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
            jdbc.update("""
                    INSERT INTO fitness.planned_workouts
                    (id,student_id,planned_start_at,original_planned_start_at,created_by)
                    VALUES (?,?,clock_timestamp()+interval '3 days',clock_timestamp()+interval '3 days',?)
                    """, old, student, student);
            migrate(source, "34");
            assertThat(jdbc.queryForObject("SELECT planned_end_at IS NULL FROM fitness.planned_workouts WHERE id=?",
                    Boolean.class, old)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_change_proposals",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.flyway_schema_history WHERE version='34' AND success=true
                    """, Integer.class)).isOne();
        }
    }

    private static void migrate(DriverManagerDataSource source, String target) {
        Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                .target(target).load().migrate();
    }
}
