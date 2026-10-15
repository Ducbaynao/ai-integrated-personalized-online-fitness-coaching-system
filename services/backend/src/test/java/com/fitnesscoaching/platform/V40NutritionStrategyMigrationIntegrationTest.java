package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V40NutritionStrategyMigrationIntegrationTest {
    @Test void cleanInstallAddsSeparateScopeAndHalfOpenStructure() {
        try (var db = database("nutrition_v40_clean")) {
            db.start();
            var source = source(db);
            migrate(source, "40");
            var jdbc = new JdbcTemplate(source);
            assertThat(version(jdbc)).isEqualTo("40");
            assertThat(jdbc.queryForObject("""
                    SELECT enumlabel FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid
                    WHERE t.typname='data_scope_code' AND enumlabel='NUTRITION_GOAL'
                    """, String.class)).isEqualTo("NUTRITION_GOAL");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.nutrition_target_day_snapshots",
                    Integer.class)).isZero();
        }
    }

    @Test void upgradePreservesLegacyGoalDateAndResolvedTargetWithoutInventingTimezone() {
        try (var db = database("nutrition_v40_upgrade")) {
            db.start();
            var source = source(db);
            migrate(source, "39");
            var jdbc = new JdbcTemplate(source);
            UUID student = user(jdbc), goal = UUID.randomUUID(), goalVersion = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.nutrition_goals(id,student_id,title,status,created_by)
                    VALUES (?,?,'Historical','DRAFT',?)
                    """, goal, student, student);
            jdbc.update("""
                    INSERT INTO fitness.nutrition_goal_versions
                      (id,nutrition_goal_id,version_number,effective_from,effective_until,created_by)
                    VALUES (?,?,1,?,?,?)
                    """, goalVersion, goal, LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 1), student);
            migrate(source, "40");
            assertThat(version(jdbc)).isEqualTo("40");
            assertThat(jdbc.queryForObject("""
                    SELECT date_semantics FROM fitness.nutrition_goal_versions WHERE id=?
                    """, String.class, goalVersion)).isEqualTo("LEGACY");
            assertThat(jdbc.queryForObject("""
                    SELECT effective_until FROM fitness.nutrition_goal_versions WHERE id=?
                    """, LocalDate.class, goalVersion)).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(jdbc.queryForObject("""
                    SELECT calendar_timezone FROM fitness.nutrition_goals WHERE id=?
                    """, String.class, goal)).isNull();
            assertThat(jdbc.queryForObject("""
                    SELECT strategy_managed FROM fitness.nutrition_goals WHERE id=?
                    """, Boolean.class, goal)).isFalse();
        }
    }

    @Test void conflictingLegacyActiveGoalsAbortWithoutMergingOrDeletingHistory() {
        try (var db = database("nutrition_v40_conflict")) {
            db.start();
            var source = source(db);
            migrate(source, "39");
            var jdbc = new JdbcTemplate(source);
            UUID student = user(jdbc);
            legacyActive(jdbc, student);
            legacyActive(jdbc, student);
            assertThatThrownBy(() -> migrate(source, "40"))
                    .hasMessageContaining("multiple legacy ACTIVE nutrition goals");
            assertThat(version(jdbc)).isEqualTo("39");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.nutrition_goals WHERE student_id=? AND status='ACTIVE'
                    """, Integer.class, student)).isEqualTo(2);
        }
    }

    private static void legacyActive(JdbcTemplate jdbc, UUID student) {
        UUID goal = UUID.randomUUID(), version = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.nutrition_goals(id,student_id,title,status,created_by)
                VALUES (?,?,'Legacy active','DRAFT',?)
                """, goal, student, student);
        jdbc.update("""
                INSERT INTO fitness.nutrition_goal_versions
                  (id,nutrition_goal_id,version_number,effective_from,created_by)
                VALUES (?,?,1,'2026-01-01',?)
                """, version, goal, student);
        jdbc.update("""
                UPDATE fitness.nutrition_goal_versions
                SET locked_at=clock_timestamp(),locked_by=?,lock_reason='MIGRATED' WHERE id=?
                """, student, version);
        jdbc.update("UPDATE fitness.nutrition_goals SET status='ACTIVE' WHERE id=?", goal);
    }

    private static UUID user(JdbcTemplate jdbc) {
        UUID student = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'hash','Legacy','ACTIVE','vi-VN','Etc/UTC')
                """, student, student + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
        return student;
    }
    private static PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18")
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(name).withUsername("fitness_app").withPassword("testpass123");
    }
    private static DriverManagerDataSource source(PostgreSQLContainer<?> db) {
        return new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }
    private static void migrate(DriverManagerDataSource source, String target) {
        Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                .target(target).load().migrate();
    }
    private static String version(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version::text FROM fitness.flyway_schema_history
                WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
    }
}
