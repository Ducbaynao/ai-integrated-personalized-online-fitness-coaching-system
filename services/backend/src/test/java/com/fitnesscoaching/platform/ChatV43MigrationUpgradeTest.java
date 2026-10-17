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

class ChatV43MigrationUpgradeTest {
    @Test void upgradePreservesLegacyV8RowsWithoutInventingPeriodIdentity() {
        try (var postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("chat_b08_upgrade_disposable").withUsername("fitness_app").withPassword("testpass123")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("42"))
                    .load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID legacyConversation = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.conversations(id,conversation_type) VALUES (?,'SUPPORT')", legacyConversation);
            UUID legacyUser = UUID.randomUUID(), legacyMessage = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) "
                    + "VALUES (?,?,'hash','Legacy','ACTIVE','vi-VN','Etc/UTC')", legacyUser,
                    legacyUser + "@example.com");
            jdbc.update("INSERT INTO fitness.notifications(recipient_user_id,event_type,title,body,related_entity_type,related_entity_id) "
                    + "VALUES (?,'CHAT_MESSAGE','Legacy','legacy body','MESSAGE',?),"
                    + "(?,'CHAT_MESSAGE','Legacy duplicate','legacy body','MESSAGE',?)",
                    legacyUser, legacyMessage, legacyUser, legacyMessage);
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.conversations WHERE id=? AND coaching_period_id IS NULL", Integer.class,
                    legacyConversation)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE recipient_user_id=? "
                    + "AND related_entity_id=? AND coaching_period_id IS NULL", Integer.class, legacyUser, legacyMessage))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.flyway_schema_history WHERE version='43' AND success", Integer.class))
                    .isEqualTo(1);
        }
    }
}
