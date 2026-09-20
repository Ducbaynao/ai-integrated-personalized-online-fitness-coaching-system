package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class V17MigrationPreSeededRolesIntegrationTest {

    @Test
    @DisplayName("V17 migration applies cleanly and idempotently when STUDENT, TRAINER, ADMIN roles already exist")
    void v17_withPreSeededRoles_succeedsAndPreservesData() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("digital_fitness_v17_test")
                .withUsername("test_user")
                .withPassword("test_pass")) {

            postgres.start();

            DataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword()
            );
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

            // 1. Run migrations up to V16
            Flyway flywayV16 = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas("fitness")
                    .defaultSchema("fitness")
                    .target("16")
                    .load();
            flywayV16.migrate();

            // 2. Pre-seed custom roles before V17 runs
            jdbcTemplate.update("""
                    INSERT INTO fitness.roles (code, name, description)
                    VALUES 
                        ('STUDENT', 'Pre-existing Student Role', 'Custom student description'),
                        ('TRAINER', 'Pre-existing Trainer Role', 'Custom trainer description'),
                        ('ADMIN', 'Pre-existing Admin Role', 'Custom admin description')
                    """);

            // 3. Run migration to V17
            Flyway flywayV17 = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas("fitness")
                    .defaultSchema("fitness")
                    .target("17")
                    .load();
            var migrateResult = flywayV17.migrate();

            assertThat(migrateResult.success).isTrue();

            // 4. Verify each role code has exactly ONE record
            for (String code : List.of("STUDENT", "TRAINER", "ADMIN")) {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM fitness.roles WHERE code = ?", Integer.class, code);
                assertThat(count).as("Role count for " + code).isEqualTo(1);
            }

            // 5. Verify custom name and description were preserved (not overwritten)
            List<Map<String, Object>> roles = jdbcTemplate.queryForList(
                    "SELECT code, name, description FROM fitness.roles WHERE code IN ('STUDENT', 'TRAINER', 'ADMIN')");
            for (Map<String, Object> role : roles) {
                String code = (String) role.get("code");
                String name = (String) role.get("name");
                String desc = (String) role.get("description");

                assertThat(name).isEqualTo("Pre-existing " + code.substring(0, 1) + code.substring(1).toLowerCase() + " Role");
                assertThat(desc).isEqualTo("Custom " + code.toLowerCase() + " description");
            }
        }
    }
}
