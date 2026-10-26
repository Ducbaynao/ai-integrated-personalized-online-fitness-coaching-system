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

class V49PushMigrationUpgradeTest {
    @Test void upgradePreservesExistingNoticesAndDoesNotOptAnyoneIntoPush() {
        try (var postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("b08_v49_upgrade_disposable")
                .withUsername("fitness_app").withPassword("testpass123")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("48"))
                    .load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID user = UUID.randomUUID(), notice = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                    VALUES (?,?,'hash','Student','ACTIVE','vi-VN','Etc/UTC')
                    """, user, user + "@example.com");
            jdbc.update("""
                    INSERT INTO fitness.notifications(id,recipient_user_id,event_type,title,body)
                    VALUES (?,?,'CHAT_MESSAGE','old','old')
                    """, notice, user);
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.flyway_schema_history WHERE version='49' AND success",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE id=?",
                    Integer.class, notice)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notification_preferences WHERE user_id=? AND channel='PUSH'",
                    Integer.class, user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.push_devices", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.push_delivery_attempts", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='fitness' AND table_name='push_delivery_attempts'
                      AND column_name IN ('next_attempt_at','claim_token','lease_expires_at',
                        'send_attempt_count','receipt_attempt_count')
                    """, Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_indexes WHERE schemaname='fitness'
                      AND indexname='idx_push_attempt_queue' AND indexdef LIKE '%next_attempt_at%'
                    """, Integer.class)).isEqualTo(1);
        }
    }
}
