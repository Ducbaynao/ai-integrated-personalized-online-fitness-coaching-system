package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V41NutritionCatalogMigrationIntegrationTest {
    @Test void cleanInstallHasCatalogAndCorrectionTables() {
        try (var db = database("nutrition_v41_clean")) {
            db.start();
            var source = source(db);
            migrate(source, "41");
            var jdbc = new JdbcTemplate(source);
            assertThat(version(jdbc)).isEqualTo("41");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.nutrition_catalog_foods", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.nutrition_frozen_target_corrections",
                    Integer.class)).isZero();
        }
    }

    @Test void upgradePreservesLegacyFoodFactsWithoutInventingCatalogProvenance() {
        try (var db = database("nutrition_v41_upgrade")) {
            db.start();
            var source = source(db);
            migrate(source, "40");
            var jdbc = new JdbcTemplate(source);
            UUID student = UUID.randomUUID(), meal = UUID.randomUUID(), item = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                    VALUES (?,?,'hash','Legacy','ACTIVE','vi-VN','Etc/UTC')
                    """, student, student + "@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
            Integer unit = jdbc.queryForObject("SELECT id FROM fitness.measurement_units WHERE code='B07_GRAM'",
                    Integer.class);
            jdbc.update("""
                    INSERT INTO fitness.meals(id,student_id,meal_type,consumed_at,created_by)
                    VALUES (?,?,'LUNCH',?,?)
                    """, meal, student, Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")), student);
            jdbc.update("""
                    INSERT INTO fitness.food_log_items
                      (id,meal_id,food_name_snapshot,quantity,unit_id,entry_source)
                    VALUES (?,?,?,1,?,'MANUAL')
                    """, item, meal, "Historic food", unit);
            migrate(source, "41");
            assertThat(version(jdbc)).isEqualTo("41");
            assertThat(jdbc.queryForObject("SELECT food_name_snapshot FROM fitness.food_log_items WHERE id=?",
                    String.class, item)).isEqualTo("Historic food");
            assertThat(jdbc.queryForObject("SELECT quantity FROM fitness.food_log_items WHERE id=?",
                    BigDecimal.class, item)).isEqualByComparingTo("1");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.food_log_items
                    WHERE id=? AND nutrition_catalog_revision_id IS NULL
                      AND nutrition_catalog_portion_id IS NULL AND quantity_basis IS NULL
                    """, Integer.class, item)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.nutrition_frozen_target_corrections",
                    Integer.class)).isZero();
        }
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
