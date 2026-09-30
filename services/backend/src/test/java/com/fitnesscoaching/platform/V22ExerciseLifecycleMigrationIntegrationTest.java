package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V22ExerciseLifecycleMigrationIntegrationTest {

    @Test
    void v22MigratesInactiveDataAndEnforcesLifecycleCanonicalPermissionIndexesAndRepeatableSeed() throws IOException {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("exercise_v22")
                .withUsername("test_user")
                .withPassword("test_pass")) {
            postgres.start();
            DataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            Flyway.configure().dataSource(dataSource).schemas("fitness").defaultSchema("fitness")
                    .target("21").load().migrate();

            jdbc.update("INSERT INTO fitness.exercise_categories(code,name) VALUES ('TEST','Test')");
            UUID inactiveId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.exercises(id,code,name,admin_status)
                    VALUES (?, 'LEGACY_INACTIVE', 'Legacy Inactive', 'INACTIVE')
                    """, inactiveId);

            var result = Flyway.configure().dataSource(dataSource).schemas("fitness").defaultSchema("fitness")
                    .target("22").load().migrate();
            assertThat(result.success).isTrue();

            assertThat(jdbc.queryForObject(
                    "SELECT admin_status FROM fitness.exercises WHERE id=?", String.class, inactiveId))
                    .isEqualTo("ARCHIVED");
            assertThat(jdbc.queryForObject(
                    "SELECT version FROM fitness.exercises WHERE id=?", Long.class, inactiveId)).isZero();

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO fitness.exercises(code,name,admin_status)
                    VALUES ('NO_LONGER_VALID','Invalid','INACTIVE')
                    """))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(jdbc.queryForObject("""
                    SELECT EXISTS(
                      SELECT 1 FROM pg_indexes
                      WHERE schemaname='fitness' AND indexname='idx_exercises_admin_status_name_id')
                    """, Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT EXISTS(
                      SELECT 1 FROM fitness.roles r
                      JOIN fitness.role_permissions rp ON rp.role_id=r.id
                      JOIN fitness.permissions p ON p.id=rp.permission_id
                      WHERE r.code='ADMIN' AND p.code='CATALOG_MANAGE')
                    """, Boolean.class)).isTrue();

            UUID activeId = UUID.randomUUID();
            UUID draftId = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status) VALUES (?, 'ACTIVE_TARGET','Active','ACTIVE')", activeId);
            jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status) VALUES (?, 'DRAFT_TARGET','Draft','DRAFT')", draftId);
            jdbc.update("""
                    INSERT INTO fitness.exercise_canonical_mappings(
                      duplicate_exercise_id,canonical_exercise_id,reason)
                    VALUES (?,?,'Replacement')
                    """, inactiveId, activeId);

            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE fitness.exercise_canonical_mappings
                    SET canonical_exercise_id=? WHERE duplicate_exercise_id=?
                    """, draftId, inactiveId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fitness.exercises SET admin_status='ARCHIVED' WHERE id=?", activeId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            Flyway.configure().dataSource(dataSource).schemas("fitness").defaultSchema("fitness")
                    .target("23").load().migrate();

            String developmentSeed = Files.readString(
                    Path.of("..", "..", "database", "seed", "dev_seed.sql"));
            jdbc.execute(developmentSeed);
            jdbc.execute(developmentSeed);

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.exercises
                    WHERE code='SYNTH_DRAFT_HINGE' AND admin_status='DRAFT'
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.exercises
                    WHERE code='SYNTH_BODYWEIGHT_SQUAT' AND admin_status='ACTIVE'
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.exercises
                    WHERE code IN ('SYNTH_ARCHIVED_CARRY','SYNTH_ARCHIVED_STEP')
                      AND admin_status='ARCHIVED'
                    """, Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*)
                    FROM fitness.exercise_canonical_mappings m
                    JOIN fitness.exercises source ON source.id=m.duplicate_exercise_id
                    JOIN fitness.exercises target ON target.id=m.canonical_exercise_id
                    WHERE source.code='SYNTH_ARCHIVED_CARRY'
                      AND target.code='SYNTH_BODYWEIGHT_SQUAT'
                    """, Integer.class)).isEqualTo(1);
        }
    }
}
