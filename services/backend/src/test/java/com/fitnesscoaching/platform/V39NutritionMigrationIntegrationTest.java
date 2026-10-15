package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V39NutritionMigrationIntegrationTest {
    @Test void cleanInstallHasB07ConstraintsWithoutCatalogIdentityAssumptions() {
        try (var db = database("nutrition_v39_clean")) {
            db.start();
            var source = source(db);
            migrate(source, "39");
            var jdbc = new JdbcTemplate(source);
            assertThat(version(jdbc)).isEqualTo("39");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.nutrients WHERE code LIKE 'B07_%'",
                    Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.nutrition_command_receipts",
                    Integer.class)).isZero();
            UUID student = UUID.randomUUID(), food = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                    VALUES (?,?,'hash','Draft','ACTIVE','vi-VN','Etc/UTC')
                    """, student, student + "@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
            jdbc.update("INSERT INTO fitness.student_private_foods(id,student_id,name,serving_label) VALUES (?,?,?,?)",
                    food, student, "Draft food", "bowl");
            assertThat(jdbc.queryForObject("SELECT quantification_status FROM fitness.student_private_foods WHERE id=?",
                    String.class, food)).isEqualTo("DRAFT");
            jdbc.update("UPDATE fitness.student_private_foods SET protein_g_per_serving=0 WHERE id=?", food);
            assertThat(jdbc.queryForObject("SELECT quantification_status FROM fitness.student_private_foods WHERE id=?",
                    String.class, food)).isEqualTo("LOGGABLE");
        }
    }

    @Test void upgradeRetainsLegacyMealItemAndKnownSummaryWithoutInventedTimezone() {
        try (var db = database("nutrition_v39_upgrade")) {
            db.start();
            var source = source(db);
            migrate(source, "38");
            var jdbc = new JdbcTemplate(source);
            UUID student = UUID.randomUUID(), meal = UUID.randomUUID(), item = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                    VALUES (?,?,'hash','Legacy','ACTIVE','vi-VN','Etc/UTC')
                    """, student, student + "@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
            jdbc.update("""
                    INSERT INTO fitness.measurement_units(code,symbol,dimension)
                    VALUES ('LEGACY_UNIT_B07','u','COUNT')
                    """);
            jdbc.update("""
                    INSERT INTO fitness.nutrients(code,name,default_unit_id)
                    SELECT 'LEGACY_NUTRIENT_B07','Legacy nutrient',id
                    FROM fitness.measurement_units WHERE code='LEGACY_UNIT_B07'
                    """);
            jdbc.update("""
                    INSERT INTO fitness.meals(id,student_id,meal_type,consumed_at,created_by)
                    VALUES (?,?,'OTHER',?,?)
                    """, meal, student, java.sql.Timestamp.from(Instant.parse("2026-10-13T12:00:00Z")), student);
            jdbc.update("""
                    INSERT INTO fitness.food_log_items(id,meal_id,food_name_snapshot,quantity,unit_id,entry_source)
                    VALUES (?,?,?,2,(SELECT min(id) FROM fitness.measurement_units),'MANUAL')
                    """, item, meal, "Legacy meal");
            jdbc.update("""
                    INSERT INTO fitness.daily_nutrition_summaries
                    (student_id,summary_date,nutrient_id,consumed_amount,unit_id)
                    SELECT ?,'2026-10-13',n.id,10,n.default_unit_id FROM fitness.nutrients n ORDER BY n.id LIMIT 1
                    """, student);
            migrate(source, "39");
            assertThat(version(jdbc)).isEqualTo("39");
            assertThat(jdbc.queryForObject("SELECT entry_kind FROM fitness.food_log_items WHERE id=?",
                    String.class, item)).isEqualTo("LEGACY");
            assertThat(jdbc.queryForObject("SELECT timezone FROM fitness.meals WHERE id=?",
                    String.class, meal)).isNull();
            assertThat(jdbc.queryForObject("SELECT quantity FROM fitness.food_log_items WHERE id=?",
                    BigDecimal.class, item)).isEqualByComparingTo("2");
            assertThat(jdbc.queryForObject("SELECT consumed_amount FROM fitness.daily_nutrition_summaries WHERE student_id=?",
                    BigDecimal.class, student)).isEqualByComparingTo("10");
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
