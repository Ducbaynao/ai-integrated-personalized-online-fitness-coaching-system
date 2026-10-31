package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V53ChatLinkedResourceMigrationTest {
    @Test void cleanInstallHasShapeGuard() { migrate(null); }
    @Test void upgradeFromV51PreservesExistingMessages() { migrate("51"); }
    @Test void upgradeAfterAiV52PreservesExistingMessages() { migrate("52"); }

    private void migrate(String priorVersion) {
        try (var pg = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("b08_v53_disposable").withUsername("fitness_app").withPassword("testpass123")) {
            pg.start();
            var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            UUID legacyMessage = null;
            if (priorVersion != null) {
                Flyway.configure().dataSource(dataSource).schemas("fitness").defaultSchema("fitness")
                        .locations("classpath:db/migration").target(MigrationVersion.fromVersion(priorVersion))
                        .load().migrate();
                if ("52".equals(priorVersion)) {
                    assertThat(jdbc.queryForObject("SELECT script FROM fitness.flyway_schema_history "
                            + "WHERE version='52' AND success", String.class))
                            .isEqualTo("V52__ai_recommendation_source_snapshot.sql");
                    assertThat(jdbc.queryForObject("SELECT checksum FROM fitness.flyway_schema_history "
                            + "WHERE version='52' AND success", Integer.class)).isEqualTo(-990909199);
                }
                UUID legacyConversation = jdbc.queryForObject("INSERT INTO fitness.conversations(conversation_type) "
                        + "VALUES ('SUPPORT') RETURNING id", UUID.class);
                legacyMessage = jdbc.queryForObject("INSERT INTO fitness.messages(conversation_id,message_type,body,"
                        + "shared_entity_type,shared_entity_id) VALUES (?,'WORKOUT_SHARE','legacy','OLD_RESOURCE',?) RETURNING id",
                        UUID.class, legacyConversation, UUID.randomUUID());
            }
            Flyway.configure().dataSource(dataSource).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT script FROM fitness.flyway_schema_history "
                    + "WHERE version='52' AND success", String.class))
                    .isEqualTo("V52__ai_recommendation_source_snapshot.sql");
            assertThat(jdbc.queryForObject("SELECT checksum FROM fitness.flyway_schema_history "
                    + "WHERE version='52' AND success", Integer.class)).isEqualTo(-990909199);
            assertThat(jdbc.queryForObject("SELECT script FROM fitness.flyway_schema_history "
                    + "WHERE version='53' AND success", String.class))
                    .isEqualTo("V53__chat_linked_resource_parent.sql");
            assertThat(jdbc.queryForObject("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema='fitness' AND table_name='ai_recommendations' "
                    + "AND column_name='source_snapshot'", String.class)).isEqualTo("source_snapshot");
            assertThat(jdbc.queryForObject("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema='fitness' AND table_name='messages' AND column_name='shared_parent_id'",
                    String.class)).isEqualTo("shared_parent_id");
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint "
                    + "WHERE conrelid='fitness.messages'::regclass AND conname='chat_shared_resource_shape_ck'",
                    Boolean.class)).isFalse();
            if (legacyMessage != null) {
                jdbc.update("UPDATE fitness.messages SET body='legacy updated' WHERE id=?", legacyMessage);
                assertThat(jdbc.queryForObject("SELECT body FROM fitness.messages WHERE id=?", String.class,
                        legacyMessage)).isEqualTo("legacy updated");
                assertThat(jdbc.queryForObject("SELECT shared_guarded_v1 FROM fitness.messages WHERE id=?",
                        Boolean.class, legacyMessage)).isNull();
            }
            UUID conversation = jdbc.queryForObject("INSERT INTO fitness.conversations(conversation_type) "
                    + "VALUES ('SUPPORT') RETURNING id", UUID.class);
            UUID source = UUID.randomUUID(), parent = UUID.randomUUID();
            reject(jdbc, conversation, "WORKOUT_SHARE", "APPOINTMENT", source, null);
            reject(jdbc, conversation, "WORKOUT_SHARE", "PLANNED_WORKOUT", source, null);
            reject(jdbc, conversation, "SCHEDULE_SHARE", "WORKOUT_PLAN_VERSION", source, parent);
            reject(jdbc, conversation, "WORKOUT_SHARE", null, source, null);
            reject(jdbc, conversation, "WORKOUT_SHARE", "WORKOUT_PLAN_VERSION", source, null);
            reject(jdbc, conversation, "WORKOUT_SHARE", "PLANNED_WORKOUT", source, parent);
            accept(jdbc, conversation, "WORKOUT_SHARE", "WORKOUT_PLAN_VERSION", source, parent);
            accept(jdbc, conversation, "SCHEDULE_SHARE", "PLANNED_WORKOUT", source, null);
            accept(jdbc, conversation, "SCHEDULE_SHARE", "APPOINTMENT", source, null);
            UUID guarded = jdbc.queryForObject("INSERT INTO fitness.messages(conversation_id,message_type,body,"
                    + "shared_entity_type,shared_entity_id,shared_guarded_v1) "
                    + "VALUES (?,'SCHEDULE_SHARE','minimal','APPOINTMENT',?,false) RETURNING id",
                    UUID.class, conversation, source);
            assertThat(jdbc.queryForObject("SELECT shared_guarded_v1 FROM fitness.messages WHERE id=?",
                    Boolean.class, guarded)).isTrue();
            jdbc.update("UPDATE fitness.messages SET shared_guarded_v1=false WHERE id=?", guarded);
            assertThat(jdbc.queryForObject("SELECT shared_guarded_v1 FROM fitness.messages WHERE id=?",
                    Boolean.class, guarded)).isTrue();
            jdbc.update("UPDATE fitness.messages SET message_type='SYSTEM',body=NULL,shared_entity_type=NULL,"
                    + "shared_entity_id=NULL,shared_parent_id=NULL WHERE id=?", guarded);
        }
    }

    private void reject(JdbcTemplate jdbc, UUID conversation, String type, String resourceType,
                        UUID source, UUID parent) {
        assertThatThrownBy(() -> accept(jdbc, conversation, type, resourceType, source, parent))
                .isInstanceOf(DataAccessException.class);
    }

    private void accept(JdbcTemplate jdbc, UUID conversation, String type, String resourceType,
                        UUID source, UUID parent) {
        jdbc.update("INSERT INTO fitness.messages(conversation_id,message_type,body,shared_entity_type,"
                + "shared_entity_id,shared_parent_id) VALUES (?,?::fitness.message_type,'minimal',?,?,?)",
                conversation, type, resourceType, source, parent);
    }
}
