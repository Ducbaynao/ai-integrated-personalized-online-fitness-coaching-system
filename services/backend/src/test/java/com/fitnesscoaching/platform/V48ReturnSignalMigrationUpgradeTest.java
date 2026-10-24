package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V48ReturnSignalMigrationUpgradeTest {
    @Test void upgradePreservesLegacyCompletionAndRecordsCutoverWithoutInventingEvidence() {
        try (var postgres=new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("m7_v48_upgrade_disposable")
                .withUsername("fitness_app").withPassword("testpass123")) {
            postgres.start();
            var source=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("47"))
                    .load().migrate();
            var jdbc=new JdbcTemplate(source);
            UUID student=UUID.randomUUID(),execution=UUID.randomUUID();
            Instant completed=Instant.parse("2025-01-01T12:00:00Z");
            jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) "
                    +"VALUES (?,?,'hash','Student','ACTIVE','vi-VN','Etc/UTC')",
                    student,student+"@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)",student);
            jdbc.update("""
                    INSERT INTO fitness.workout_session_logs
                      (id,student_id,performed_start_at,performed_end_at,status,logged_by)
                    VALUES (?,?,?,?,'COMPLETED',?)
                    """,execution,student,Timestamp.from(completed.minusSeconds(1800)),
                    Timestamp.from(completed),student);
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs "
                    +"WHERE id=? AND status='COMPLETED' AND performed_end_at=?",Integer.class,
                    execution,Timestamp.from(completed))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT activated_at > performed_end_at FROM "
                    +"fitness.attention_rule_activations CROSS JOIN fitness.workout_session_logs "
                    +"WHERE rule_code='RETURN_AFTER_100_DAYS_V1' AND id=?",Boolean.class,execution)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.attention_signals "
                    +"WHERE student_id=?",Integer.class,student)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.flyway_schema_history "
                    +"WHERE version='48' AND success",Integer.class)).isEqualTo(1);
        }
    }
}
