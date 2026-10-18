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

class V44AttentionReviewMigrationUpgradeTest {
    @Test void upgradeKeepsLegacySignalsWithoutInventingSourceOrPeriod() {
        try (var postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("m7_v44_upgrade_disposable")
                .withUsername("fitness_app").withPassword("testpass123")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("43"))
                    .load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID student = UUID.randomUUID(), signal = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) "
                    + "VALUES (?,?,'hash','Legacy','ACTIVE','vi-VN','Etc/UTC')",
                    student,student+"@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)",student);
            jdbc.update("INSERT INTO fitness.attention_signals(id,student_id,source,signal_type,severity,status,title,explanation) "
                    + "VALUES (?,?,'SYSTEM_RULE','LEGACY_RULE','LOW','OPEN','Legacy','Legacy evidence')",
                    signal,student);
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.attention_signals "
                    + "WHERE id=? AND coaching_period_id IS NULL AND source_key IS NULL AND version=0",
                    Integer.class,signal)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.student_review_timezones WHERE student_id=?",
                    Integer.class,student)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.flyway_schema_history "
                    + "WHERE version='44' AND success",Integer.class)).isEqualTo(1);
        }
    }
}
