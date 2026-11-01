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

class V54TimedReminderMigrationTest {
    @Test void cleanInstallAndUpgradeFromV53PreserveLegacyInboxWithoutOptIn() {
        upgrade(false);
        upgrade(true);
    }

    private void upgrade(boolean fromV53) {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18")
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("b08_v54_disposable").withUsername("fitness_app").withPassword("testpass123")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(source);
            UUID user = UUID.randomUUID(), notice = UUID.randomUUID();
            if (fromV53) {
                Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                        .locations("classpath:db/migration").target(MigrationVersion.fromVersion("53"))
                        .load().migrate();
                jdbc.update("""
                        INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                        VALUES (?,?,'hash','Student','ACTIVE','vi-VN','Etc/UTC')
                        """, user, user + "@example.com");
                jdbc.update("""
                        INSERT INTO fitness.notifications(id,recipient_user_id,event_type,title,body)
                        VALUES (?,?,'CHAT_MESSAGE','old','old')
                        """, notice, user);
            }
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.flyway_schema_history WHERE version='54' AND success",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notification_preferences WHERE event_type LIKE '%_REMINDER'",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.timed_reminders", Integer.class)).isZero();
            if (fromV53) assertThat(jdbc.queryForObject("SELECT inbox_visible FROM fitness.notifications WHERE id=?",
                    Boolean.class, notice)).isTrue();
        }
    }
}
