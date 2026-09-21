package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V18MigrationIntegrationTest {

    @Test
    @DisplayName("V18 migration applies cleanly, migrates legacy enum values to trainer_verification_state, creates junction table, and enforces unique pending index")
    void v18_migration_preservesLegacyData_and_enforcesInvariants() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("digital_fitness_v18_test")
                .withUsername("test_user")
                .withPassword("test_pass")) {

            postgres.start();

            DataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword()
            );
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

            // 1. Run migrations up to V17
            Flyway flywayV17 = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas("fitness")
                    .defaultSchema("fitness")
                    .target("17")
                    .load();
            flywayV17.migrate();

            // 2. Set up legacy test data before V18
            UUID user1 = UUID.randomUUID();
            UUID user2 = UUID.randomUUID();
            UUID user3 = UUID.randomUUID();

            jdbcTemplate.update("""
                    INSERT INTO fitness.users (id, email, password_hash, display_name, status)
                    VALUES 
                        (?, 'u1@test.com', 'hash', 'User One', 'ACTIVE'::fitness.account_status),
                        (?, 'u2@test.com', 'hash', 'User Two', 'ACTIVE'::fitness.account_status),
                        (?, 'u3@test.com', 'hash', 'User Three', 'ACTIVE'::fitness.account_status)
                    """, user1, user2, user3);

            jdbcTemplate.update("""
                    INSERT INTO fitness.trainer_profiles (user_id, public_slug, verification_status, activity_status)
                    VALUES 
                        (?, 'trainer-1', 'VERIFIED'::fitness.trainer_verification_state, 'ACTIVE'::fitness.trainer_activity_status),
                        (?, 'trainer-2', 'NOT_SUBMITTED'::fitness.trainer_verification_state, 'ACTIVE'::fitness.trainer_activity_status),
                        (?, 'trainer-3', 'NOT_SUBMITTED'::fitness.trainer_verification_state, 'ACTIVE'::fitness.trainer_activity_status)
                    """, user1, user2, user3);

            UUID app1 = UUID.randomUUID(); // APPROVED -> should become VERIFIED
            UUID app2 = UUID.randomUUID(); // EXPIRED -> should become SUSPENDED
            UUID app3 = UUID.randomUUID(); // REVOKED -> should become SUSPENDED
            UUID app4 = UUID.randomUUID(); // REJECTED -> should remain REJECTED
            UUID app5 = UUID.randomUUID(); // PENDING -> should remain PENDING

            jdbcTemplate.update("""
                    INSERT INTO fitness.trainer_applications (id, trainer_id, status)
                    VALUES 
                        (?, ?, 'APPROVED'::fitness.verification_status),
                        (?, ?, 'EXPIRED'::fitness.verification_status),
                        (?, ?, 'REVOKED'::fitness.verification_status),
                        (?, ?, 'REJECTED'::fitness.verification_status),
                        (?, ?, 'PENDING'::fitness.verification_status)
                    """, app1, user1, app2, user1, app3, user1, app4, user2, app5, user3);

            // Add history with null and non-null from_status
            UUID h1 = UUID.randomUUID();
            UUID h2 = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO fitness.trainer_application_status_history (id, trainer_application_id, from_status, to_status)
                    VALUES 
                        (?, ?, null, 'PENDING'::fitness.verification_status),
                        (?, ?, 'PENDING'::fitness.verification_status, 'APPROVED'::fitness.verification_status)
                    """, h1, app1, h2, app1);

            // 3. Run migration to V18
            Flyway flywayV18 = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas("fitness")
                    .defaultSchema("fitness")
                    .target("18")
                    .load();
            var migrateResult = flywayV18.migrate();

            assertThat(migrateResult.success).isTrue();

            // 4. Verify migrated statuses in trainer_applications
            String status1 = jdbcTemplate.queryForObject(
                    "SELECT status::text FROM fitness.trainer_applications WHERE id = ?", String.class, app1);
            assertThat(status1).isEqualTo("VERIFIED");

            String status2 = jdbcTemplate.queryForObject(
                    "SELECT status::text FROM fitness.trainer_applications WHERE id = ?", String.class, app2);
            assertThat(status2).isEqualTo("SUSPENDED");

            String status3 = jdbcTemplate.queryForObject(
                    "SELECT status::text FROM fitness.trainer_applications WHERE id = ?", String.class, app3);
            assertThat(status3).isEqualTo("SUSPENDED");

            String status4 = jdbcTemplate.queryForObject(
                    "SELECT status::text FROM fitness.trainer_applications WHERE id = ?", String.class, app4);
            assertThat(status4).isEqualTo("REJECTED");

            String status5 = jdbcTemplate.queryForObject(
                    "SELECT status::text FROM fitness.trainer_applications WHERE id = ?", String.class, app5);
            assertThat(status5).isEqualTo("PENDING");

            // 5. Verify history from_status and to_status
            Map<String, Object> hist1 = jdbcTemplate.queryForMap(
                    "SELECT from_status, to_status FROM fitness.trainer_application_status_history WHERE id = ?", h1);
            assertThat(hist1.get("from_status")).isNull();
            assertThat(hist1.get("to_status")).isEqualTo("PENDING");

            Map<String, Object> hist2 = jdbcTemplate.queryForMap(
                    "SELECT from_status, to_status FROM fitness.trainer_application_status_history WHERE id = ?", h2);
            assertThat(hist2.get("from_status")).isEqualTo("PENDING");
            assertThat(hist2.get("to_status")).isEqualTo("VERIFIED");

            // 6. Verify junction table exists
            Boolean junctionExists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema = 'fitness' AND table_name = 'trainer_application_certificates')",
                    Boolean.class);
            assertThat(junctionExists).isTrue();

            // 7. Verify unique index uq_trainer_pending_application blocks duplicate PENDING
            UUID dupPendingApp = UUID.randomUUID();
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO fitness.trainer_applications (id, trainer_id, status)
                    VALUES (?, ?, 'PENDING'::fitness.trainer_verification_state)
                    """, dupPendingApp, user3))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_trainer_pending_application");

            // But inserting a non-PENDING application for the same trainer is allowed
            UUID nonPendingApp = UUID.randomUUID();
            int inserted = jdbcTemplate.update("""
                    INSERT INTO fitness.trainer_applications (id, trainer_id, status)
                    VALUES (?, ?, 'REJECTED'::fitness.trainer_verification_state)
                    """, nonPendingApp, user3);
            assertThat(inserted).isEqualTo(1);
        }
    }
}
