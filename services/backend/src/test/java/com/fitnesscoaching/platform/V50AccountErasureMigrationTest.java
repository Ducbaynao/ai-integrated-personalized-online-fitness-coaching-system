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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V50AccountErasureMigrationTest {
    @Test void upgradeFromV49PreservesLegacyUserAndAddsNoImplicitHoldOrErasure() {
        migrate(true);
    }

    @Test void cleanInstallCreatesWorklistAndDenyByDefaultHoldStorage() {
        migrate(false);
    }

    private void migrate(boolean upgrade) {
        try (var pg = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("b08_v50_disposable").withUsername("fitness_app").withPassword("testpass123")) {
            pg.start();
            var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            UUID legacy = UUID.randomUUID();
            if (upgrade) {
                Flyway.configure().dataSource(dataSource).schemas("fitness").defaultSchema("fitness")
                        .locations("classpath:db/migration").target(MigrationVersion.fromVersion("49"))
                        .load().migrate();
                jdbc.update("""
                        INSERT INTO fitness.users(id,email,password_hash,display_name,status)
                        VALUES (?,?,'hash','Legacy Student','ACTIVE')
                        """, legacy, legacy + "@example.com");
                jdbc.update("""
                        INSERT INTO fitness.audit_logs(actor_user_id,action,target_type,before_data)
                        VALUES (?,'LEGACY_ACTION','USER','{"private":"existing"}'::jsonb)
                        """, legacy);
                jdbc.update("""
                        INSERT INTO fitness.security_events(user_id,event_type,details)
                        VALUES (?,'LEGACY_LOGIN','{"device":"existing"}'::jsonb)
                        """, legacy);
                jdbc.update("""
                        INSERT INTO fitness.user_account_status_history(user_id,to_status,reason)
                        VALUES (?,'ACTIVE','LEGACY_STATUS')
                        """, legacy);
            }
            Flyway.configure().dataSource(dataSource).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.flyway_schema_history WHERE version='50' AND success
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.account_erasure_requests", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.account_erasure_holds", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.account_erasure_objects", Integer.class)).isZero();
            String dueIndex = jdbc.queryForObject("""
                    SELECT indexdef FROM pg_indexes
                    WHERE schemaname='fitness' AND indexname='idx_account_erasure_due'
                    """, String.class);
            assertThat(dueIndex).contains("HELD").doesNotContain("AUDIT_REDACTION_PENDING");
            assertThat(jdbc.queryForObject("""
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE conrelid='fitness.account_erasure_objects'::regclass
                      AND conname='account_erasure_objects_status_check'
                    """, String.class)).contains("STORAGE_REVIEW_REQUIRED");
            if (upgrade) {
                assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.users WHERE id=?",
                        String.class, legacy)).isEqualTo("ACTIVE");
                assertThat(jdbc.queryForObject("SELECT display_name FROM fitness.users WHERE id=?",
                        String.class, legacy)).isEqualTo("Legacy Student");
                assertThat(jdbc.queryForObject("""
                        SELECT before_data->>'private' FROM fitness.audit_logs
                        WHERE actor_user_id=? AND action='LEGACY_ACTION'
                        """, String.class, legacy)).isEqualTo("existing");
                assertThat(jdbc.queryForObject("""
                        SELECT details->>'device' FROM fitness.security_events
                        WHERE user_id=? AND event_type='LEGACY_LOGIN'
                        """, String.class, legacy)).isEqualTo("existing");
                assertThat(jdbc.queryForObject("""
                        SELECT reason FROM fitness.user_account_status_history
                        WHERE user_id=? AND reason='LEGACY_STATUS'
                        """, String.class, legacy)).isEqualTo("LEGACY_STATUS");
                assertThatThrownBy(() -> jdbc.update("""
                        UPDATE fitness.audit_logs SET action='MUTATED'
                        WHERE actor_user_id=? AND action='LEGACY_ACTION'
                        """, legacy)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(() -> jdbc.update("""
                        DELETE FROM fitness.security_events
                        WHERE user_id=? AND event_type='LEGACY_LOGIN'
                        """, legacy)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(() -> jdbc.update("""
                        UPDATE fitness.user_account_status_history SET reason='MUTATED'
                        WHERE user_id=? AND reason='LEGACY_STATUS'
                        """, legacy)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            }
        }
    }
}
