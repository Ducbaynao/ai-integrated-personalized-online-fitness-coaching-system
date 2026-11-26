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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V64GoalTypesSeedMigrationTest {

    private static final List<String> EXPECTED_CODES = List.of(
            "MUSCLE_GAIN",
            "FAT_LOSS",
            "WEIGHT_GAIN",
            "WEIGHT_LOSS",
            "STRENGTH",
            "IMPROVE_FITNESS",
            "MAINTAIN"
    );

    @Test
    @DisplayName("V64 seeds all 7 goal_types including distinct FAT_LOSS and WEIGHT_LOSS on clean migration")
    void v64_cleanMigrate_seedsAllAuthoritativeGoalTypes() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("digital_fitness_v64_clean")
                .withUsername("test_user")
                .withPassword("test_pass")) {
            postgres.start();

            DataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            var result = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas("fitness")
                    .defaultSchema("fitness")
                    .target("64")
                    .load()
                    .migrate();

            assertThat(result.success).isTrue();

            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, code, name, is_active FROM fitness.goal_types ORDER BY id");
            assertThat(rows).hasSize(7);
            assertThat(rows.stream().map(r -> (String) r.get("code")).toList())
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_CODES);
            assertThat(rows).allMatch(r -> Boolean.TRUE.equals(r.get("is_active")));

            Map<String, Object> fatLoss = rows.stream()
                    .filter(r -> "FAT_LOSS".equals(r.get("code")))
                    .findFirst()
                    .orElseThrow();
            Map<String, Object> weightLoss = rows.stream()
                    .filter(r -> "WEIGHT_LOSS".equals(r.get("code")))
                    .findFirst()
                    .orElseThrow();
            assertThat(fatLoss.get("id")).isNotEqualTo(weightLoss.get("id"));
        }
    }

    @Test
    @DisplayName("V64 upgrade preserves non-fixed IDs, metadata, inactive rows, and existing goal/proposal FKs while seeding missing codes")
    void v64_upgradeFromV63WithNonFixedIdsInactiveAndFkReferences_preservesExistingAndSeedsMissing() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("digital_fitness_v64_upgrade")
                .withUsername("test_user")
                .withPassword("test_pass")) {
            postgres.start();

            DataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas("fitness")
                    .defaultSchema("fitness")
                    .target("63")
                    .load()
                    .migrate();

            // Shift identity sequence so pre-existing rows get non-fixed, non-canonical IDs.
            jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('fitness.goal_types', 'id'), 40)", Long.class);

            Short weightLossId = jdbc.queryForObject("""
                    INSERT INTO fitness.goal_types (code, name, description, is_active)
                    VALUES ('WEIGHT_LOSS', 'Pre-existing Weight Loss', 'Custom weight desc', true)
                    RETURNING id
                    """, Short.class);
            Short fatLossId = jdbc.queryForObject("""
                    INSERT INTO fitness.goal_types (code, name, description, is_active)
                    VALUES ('FAT_LOSS', 'Pre-existing Fat Loss', 'Custom fat desc', true)
                    RETURNING id
                    """, Short.class);
            Short strengthId = jdbc.queryForObject("""
                    INSERT INTO fitness.goal_types (code, name, description, is_active)
                    VALUES ('STRENGTH', 'Pre-existing Inactive Strength', 'Disabled strength desc', false)
                    RETURNING id
                    """, Short.class);

            UUID studentId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.users (id, email, password_hash, display_name, status)
                    VALUES (?, 'v64.fk@example.com', 'hash', 'FK Student', 'ACTIVE'::fitness.account_status)
                    """, studentId);
            jdbc.update("INSERT INTO fitness.student_profiles (user_id) VALUES (?)", studentId);

            UUID goalId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.fitness_goals (id, student_id, title, status, created_by)
                    VALUES (?, ?, 'Pre-V64 Weight Goal', 'DRAFT'::fitness.lifecycle_status, ?)
                    """, goalId, studentId, studentId);
            jdbc.update("""
                    INSERT INTO fitness.fitness_goal_versions (id, fitness_goal_id, version_number, title, start_date, created_by)
                    VALUES (?, ?, 1, 'Pre-V64 Weight Goal', CURRENT_DATE, ?)
                    """, versionId, goalId, studentId);
            jdbc.update("""
                    INSERT INTO fitness.goal_objectives (goal_version_id, goal_type_id, priority, sort_order)
                    VALUES (?, ?, 'PRIMARY'::fitness.objective_priority, 0)
                    """, versionId, weightLossId);
            jdbc.update("""
                    UPDATE fitness.fitness_goal_versions
                    SET locked_at = now(), locked_by = ?, lock_reason = 'MIGRATED'::fitness.version_lock_reason
                    WHERE id = ?
                    """, studentId, versionId);
            jdbc.update("UPDATE fitness.fitness_goals SET status = 'ACTIVE'::fitness.lifecycle_status WHERE id = ?", goalId);

            UUID proposalId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.goal_proposals (id, student_id, fitness_goal_id, base_goal_version_id, source, created_by, reason)
                    VALUES (?, ?, ?, ?, 'STUDENT'::fitness.proposal_source, ?, 'Pre-V64 FK check')
                    """, proposalId, studentId, goalId, versionId, studentId);
            jdbc.update("""
                    INSERT INTO fitness.goal_proposal_objectives (goal_proposal_id, goal_type_id, priority, sort_order)
                    VALUES (?, ?, 'PRIMARY'::fitness.objective_priority, 0)
                    """, proposalId, fatLossId);

            var result = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas("fitness")
                    .defaultSchema("fitness")
                    .target("64")
                    .load()
                    .migrate();

            assertThat(result.success).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.goal_types", Integer.class)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT code) FROM fitness.goal_types", Integer.class)).isEqualTo(7);

            Map<String, Object> weightLossRow = jdbc.queryForMap(
                    "SELECT id, name, description, is_active FROM fitness.goal_types WHERE code = 'WEIGHT_LOSS'");
            assertThat(((Number) weightLossRow.get("id")).shortValue()).isEqualTo(weightLossId);
            assertThat(weightLossRow.get("name")).isEqualTo("Pre-existing Weight Loss");
            assertThat(weightLossRow.get("description")).isEqualTo("Custom weight desc");
            assertThat(weightLossRow.get("is_active")).isEqualTo(true);

            Map<String, Object> fatLossRow = jdbc.queryForMap(
                    "SELECT id, name, description, is_active FROM fitness.goal_types WHERE code = 'FAT_LOSS'");
            assertThat(((Number) fatLossRow.get("id")).shortValue()).isEqualTo(fatLossId);
            assertThat(fatLossRow.get("name")).isEqualTo("Pre-existing Fat Loss");
            assertThat(fatLossRow.get("description")).isEqualTo("Custom fat desc");
            assertThat(fatLossRow.get("is_active")).isEqualTo(true);

            Map<String, Object> strengthRow = jdbc.queryForMap(
                    "SELECT id, name, description, is_active FROM fitness.goal_types WHERE code = 'STRENGTH'");
            assertThat(((Number) strengthRow.get("id")).shortValue()).isEqualTo(strengthId);
            assertThat(strengthRow.get("name")).isEqualTo("Pre-existing Inactive Strength");
            assertThat(strengthRow.get("description")).isEqualTo("Disabled strength desc");
            assertThat(strengthRow.get("is_active")).isEqualTo(false);

            for (String missingCode : List.of("MUSCLE_GAIN", "WEIGHT_GAIN", "IMPROVE_FITNESS", "MAINTAIN")) {
                Boolean active = jdbc.queryForObject(
                        "SELECT is_active FROM fitness.goal_types WHERE code = ?", Boolean.class, missingCode);
                assertThat(active).isTrue();
            }

            Map<String, Object> goalObjectiveFk = jdbc.queryForMap("""
                    SELECT gt.id, gt.code
                    FROM fitness.goal_objectives go
                    JOIN fitness.goal_types gt ON gt.id = go.goal_type_id
                    WHERE go.goal_version_id = ?
                    """, versionId);
            assertThat(((Number) goalObjectiveFk.get("id")).shortValue()).isEqualTo(weightLossId);
            assertThat(goalObjectiveFk.get("code")).isEqualTo("WEIGHT_LOSS");

            Map<String, Object> proposalObjectiveFk = jdbc.queryForMap("""
                    SELECT gt.id, gt.code
                    FROM fitness.goal_proposal_objectives gpo
                    JOIN fitness.goal_types gt ON gt.id = gpo.goal_type_id
                    WHERE gpo.goal_proposal_id = ?
                    """, proposalId);
            assertThat(((Number) proposalObjectiveFk.get("id")).shortValue()).isEqualTo(fatLossId);
            assertThat(proposalObjectiveFk.get("code")).isEqualTo("FAT_LOSS");
        }
    }
}
