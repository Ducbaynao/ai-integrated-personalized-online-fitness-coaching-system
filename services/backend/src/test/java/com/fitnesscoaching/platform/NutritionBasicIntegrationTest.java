package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.nutrition.NutritionBasicService;
import com.fitnesscoaching.platform.modules.nutrition.NutritionBasicService.*;
import com.fitnesscoaching.platform.modules.nutrition.NutritionFailure;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NutritionBasicIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("nutrition_b07").withUsername("fitness_app").withPassword("testpass123");
    static { postgres.start(); }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired NutritionBasicService nutrition;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired CoachingSharingUseCase sharing;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final Instant AT = Instant.parse("2026-10-13T12:00:00Z");

    @Test void privateFoodFactsAreFrozenAndCorrectionsRecalculate() {
        UUID student = student();
        FoodInput firstInput = new FoodInput("Oats", "bowl", new BigDecimal("100.000000"),
                new BigDecimal("6"), new BigDecimal("0"), null, null, "food-1");
        Food food = nutrition.createFood(student, firstInput);
        assertThat(nutrition.createFood(student, firstInput).id()).isEqualTo(food.id());
        Day initial = nutrition.addItem(student, DAY, new ItemInput("Etc/UTC", AT, "BREAKFAST", null,
                food.id(), new BigDecimal("0.5"), "add-1"));
        assertThat(initial.completeness()).isEqualTo("PARTIAL");
        assertThat(nutrient(initial, "B07_ENERGY_KCAL").total()).isEqualByComparingTo("50");
        assertThat(nutrient(initial, "B07_CARBOHYDRATE_G").total()).isEqualByComparingTo("0");
        assertThat(nutrient(initial, "B07_FAT_G").total()).isNull();
        assertThat(nutrition.addItem(student, DAY, new ItemInput("Etc/UTC", AT, "BREAKFAST", null,
                food.id(), new BigDecimal("0.5"), "add-1")).items()).hasSize(1);
        nutrition.updateFood(student, food.id(), new FoodInput("Oats changed", "bowl",
                new BigDecimal("200"), new BigDecimal("6"), BigDecimal.ZERO, null, 0L, "food-2"));
        assertThat(nutrient(nutrition.readMine(student, DAY, "Etc/UTC"), "B07_ENERGY_KCAL").total())
                .isEqualByComparingTo("50");
        UUID itemId = initial.items().getFirst().id();
        Day corrected = nutrition.correctItem(student, itemId,
                new CorrectionInput(0, null, food.id(), BigDecimal.ONE, false, "correct-1"));
        assertThat(nutrient(corrected, "B07_ENERGY_KCAL").total()).isEqualByComparingTo("200");
        assertThat(nutrition.history(student, itemId)).hasSize(1);
        assertThat(nutrition.history(student, itemId).getFirst().beforeJson())
                .contains("B07_ENERGY_KCAL").contains("50.000000");
        assertThat(nutrition.correctItem(student, itemId,
                new CorrectionInput(0, null, food.id(), BigDecimal.ONE, false, "correct-1")).items())
                .hasSize(1);
        assertThatThrownBy(() -> nutrition.correctItem(student, itemId,
                new CorrectionInput(0, "unknown", null, null, false, "correct-2")))
                .isInstanceOf(NutritionFailure.class)
                .extracting("code").isEqualTo("NUTRITION_VERSION_STALE");
        assertThatThrownBy(() -> nutrition.createFood(student, new FoodInput("Changed payload", "bowl",
                BigDecimal.ONE, null, null, null, null, "food-1")))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_IDEMPOTENCY_CONFLICT");
    }

    @Test void draftFoodCannotBecomeAQuantifiedServingUntilStudentAddsAtLeastOneNutrient() throws Exception {
        UUID student = student();
        Food draft = nutrition.createFood(student, new FoodInput("Unmeasured oats", "bowl",
                null, null, null, null, null, "draft-create"));
        assertThat(draft.quantificationStatus()).isEqualTo("DRAFT");
        mvc.perform(get("/api/v1/nutrition/private-foods")
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quantificationStatus").value("DRAFT"));
        assertThatThrownBy(() -> nutrition.addItem(student, DAY, new ItemInput("Etc/UTC", AT,
                "BREAKFAST", null, draft.id(), BigDecimal.ONE, "draft-add")))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_FOOD_DRAFT");
        mvc.perform(post("/api/v1/nutrition/days/2026-10-13/items")
                        .with(jwt().jwt(j -> j.subject(student.toString())))
                        .contentType("application/json")
                        .content("""
                                {"timezone":"Etc/UTC","consumedAt":"2026-10-13T12:00:00Z",
                                 "mealType":"BREAKFAST","privateFoodId":"%s","servings":1,
                                 "commandKey":"draft-http"}
                                """.formatted(draft.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NUTRITION_FOOD_DRAFT"));
        assertThat(nutrition.readMine(student, DAY, "Etc/UTC").items()).isEmpty();

        Food partial = nutrition.updateFood(student, draft.id(), new FoodInput("Unmeasured oats", "bowl",
                null, BigDecimal.ZERO, null, null, draft.version(), "draft-update"));
        assertThat(partial.quantificationStatus()).isEqualTo("LOGGABLE");
        Day logged = nutrition.addItem(student, DAY, new ItemInput("Etc/UTC", AT,
                "BREAKFAST", null, partial.id(), new BigDecimal("1.5"), "partial-add"));
        assertThat(nutrient(logged, "B07_PROTEIN_G").total()).isEqualByComparingTo("0");
        assertThat(nutrient(logged, "B07_ENERGY_KCAL").total()).isNull();

        Food draftAgain = nutrition.updateFood(student, partial.id(), new FoodInput("Unmeasured oats", "bowl",
                null, null, null, null, partial.version(), "draft-again"));
        assertThat(draftAgain.quantificationStatus()).isEqualTo("DRAFT");
        assertThat(nutrient(nutrition.readMine(student, DAY, "Etc/UTC"), "B07_PROTEIN_G").total())
                .isEqualByComparingTo("0");
        assertThatThrownBy(() -> nutrition.correctItem(student, logged.items().getFirst().id(),
                new CorrectionInput(0, null, draftAgain.id(), BigDecimal.ONE, false, "draft-correct")))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_FOOD_DRAFT");
    }

    @Test void descriptionOnlyMeansUnknownAndNoIntakeRequiresExplicitConfirmation() {
        UUID student = student();
        Day empty = nutrition.readMine(student, DAY, "Asia/Bangkok");
        assertThat(empty.completeness()).isEqualTo("NOT_LOGGED");
        assertThat(nutrient(empty, "B07_ENERGY_KCAL").total()).isNull();
        Day noIntake = nutrition.confirmDay(student, DAY,
                new ConfirmInput("Asia/Bangkok", 0, true, "none-1"));
        assertThat(noIntake.completeness()).isEqualTo("COMPLETE");
        assertThat(nutrient(noIntake, "B07_ENERGY_KCAL").total()).isEqualByComparingTo("0");
        Day described = nutrition.addItem(student, DAY, new ItemInput("Asia/Bangkok",
                Instant.parse("2026-10-13T01:00:00Z"), "OTHER", "Homemade dish", null, null, "desc-1"));
        assertThat(described.completeness()).isEqualTo("PARTIAL");
        assertThat(nutrient(described, "B07_ENERGY_KCAL").total()).isNull();
        assertThat(nutrient(described, "B07_ENERGY_KCAL").unknownItems()).isEqualTo(1);
        assertThatThrownBy(() -> nutrition.confirmDay(student, DAY,
                new ConfirmInput("Asia/Bangkok", described.version(), true, "none-2")))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_DAY_CONFIRM_INVALID");
    }

    @Test void explicitTimezoneControlsDayBoundaryAndDecimalRounding() {
        UUID student = student();
        Food food = nutrition.createFood(student, new FoodInput("Measured food", "serving",
                new BigDecimal("1.234567"), null, null, null, null, "fraction-food"));
        Instant insideBangkokDay = Instant.parse("2026-10-12T18:00:00Z");
        Day logged = nutrition.addItem(student, DAY, new ItemInput("Asia/Bangkok",
                insideBangkokDay, "OTHER", null, food.id(), new BigDecimal("0.333333"), "fraction-add"));
        assertThat(logged.timezone()).isEqualTo("Asia/Bangkok");
        assertThat(nutrient(logged, "B07_ENERGY_KCAL").total()).isEqualByComparingTo("0.411522");
        assertThatThrownBy(() -> nutrition.addItem(student, DAY, new ItemInput("Asia/Bangkok",
                Instant.parse("2026-10-13T18:00:00Z"), "OTHER", null, food.id(),
                BigDecimal.ONE, "wrong-local-day")))
                .isInstanceOf(NutritionFailure.class).extracting("code").isEqualTo("VALIDATION_FAILED");
        assertThatThrownBy(() -> nutrition.readMine(student, DAY, "Etc/UTC"))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_TIMEZONE_CONFLICT");
        Food large = nutrition.createFood(student, new FoodInput("Large fact", "serving",
                new BigDecimal("999999999999.999999"), null, null, null, null, "large-food"));
        assertThatThrownBy(() -> nutrition.addItem(student, DAY, new ItemInput("Asia/Bangkok",
                insideBangkokDay, "OTHER", null, large.id(), new BigDecimal("2"), "overflow-add")))
                .isInstanceOf(NutritionFailure.class).extracting("code").isEqualTo("VALIDATION_FAILED");
        assertThat(nutrition.readMine(student, DAY, "Asia/Bangkok").items()).hasSize(1);
    }

    @Test void ownerAndFormerTrainerCannotReadOtherStudentsDay() throws Exception {
        UUID student = student(), other = student();
        nutrition.addItem(student, DAY, new ItemInput("Etc/UTC", AT, "LUNCH", "Private meal",
                null, null, "private-1"));
        assertThat(nutrition.readMine(other, DAY, "Etc/UTC").items()).isEmpty();
        mvc.perform(get("/api/v1/students/{studentId}/nutrition/days/{day}", student, DAY)
                        .param("timezone", "Etc/UTC").with(jwt().jwt(j -> j.subject(other.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NUTRITION_DAY_NOT_FOUND"));
        mvc.perform(get("/api/v1/nutrition/days/{day}", DAY).param("timezone", "Etc/UTC")
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("Private meal"))
                .andExpect(jsonPath("$.nutrients[0].total").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(post("/api/v1/nutrition/days/{day}/items", DAY)
                        .contentType("application/json").content("{}")
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test void legacyMealWithoutTimezoneRemainsVisibleAndMakesNewSummaryUnknown() {
        UUID student = student();
        UUID meal = UUID.randomUUID(), item = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.meals(id,student_id,meal_type,consumed_at,created_by)
                VALUES (?,?,'OTHER',?,?)
                """, meal, student, java.sql.Timestamp.from(AT), student);
        jdbc.update("""
                INSERT INTO fitness.food_log_items(id,meal_id,food_name_snapshot,quantity,unit_id,entry_source)
                VALUES (?,?,?,1,(SELECT min(id) FROM fitness.measurement_units),'MANUAL')
                """, item, meal, "Historical meal");
        Day legacy = nutrition.readMine(student, DAY, "Etc/UTC");
        assertThat(legacy.items()).extracting(Item::name).contains("Historical meal");
        assertThat(nutrient(legacy, "B07_ENERGY_KCAL").total()).isNull();
        assertThat(nutrient(legacy, "B07_ENERGY_KCAL").unknownItems()).isEqualTo(1);
        assertThatThrownBy(() -> nutrition.correctItem(student, item,
                new CorrectionInput(0, "Changed", null, null, false, "legacy-correction")))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_LEGACY_SOURCE_UNKNOWN");
        Food food = nutrition.createFood(student, new FoodInput("Rice", "bowl", new BigDecimal("100"),
                null, null, null, null, "legacy-food"));
        Day mixed = nutrition.addItem(student, DAY, new ItemInput("Etc/UTC", AT, "OTHER", null,
                food.id(), BigDecimal.ONE, "legacy-add"));
        assertThat(mixed.items()).hasSize(2);
        assertThat(nutrient(mixed, "B07_ENERGY_KCAL").total()).isNull();
        assertThat(nutrient(mixed, "B07_ENERGY_KCAL").knownSubtotal()).isEqualByComparingTo("100");
    }

    @Test void trainerViewRequiresLivePeriodGrantAndHistoryWindow() {
        UUID student = student(), trainer = trainer();
        var pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        var active = lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT",
                0, null, UUID.randomUUID());
        nutrition.addItem(student, DAY, new ItemInput("Etc/UTC", AT, "LUNCH", "Private meal",
                null, null, "grant-item"));
        assertThatThrownBy(() -> nutrition.readForTrainer(trainer, student, DAY, "Etc/UTC"))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_DAY_NOT_FOUND");
        var grant = sharing.grantOrReplace(student, active.relationship().id(), DataScope.NUTRITION_LOGS,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, UUID.randomUUID());
        assertThat(nutrition.readForTrainer(trainer, student, DAY, "Etc/UTC").items()).hasSize(1);
        sharing.revoke(student, active.relationship().id(), grant.id(), grant.version(),
                "Stop sharing", UUID.randomUUID());
        assertThatThrownBy(() -> nutrition.readForTrainer(trainer, student, DAY, "Etc/UTC"))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_DAY_NOT_FOUND");
        lifecycle.relationshipAction(student, active.relationship().id(), "END", 1,
                "Coaching ended", UUID.randomUUID());
        assertThatThrownBy(() -> nutrition.readForTrainer(trainer, student, DAY, "Etc/UTC"))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_DAY_NOT_FOUND");
    }

    private UUID student() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'hash','Student','ACTIVE','vi-VN','Etc/UTC')
                """, id, id + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='STUDENT'
                """, id, id);
        return id;
    }
    private UUID trainer() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'hash','Trainer','ACTIVE','vi-VN','Etc/UTC')
                """, id, id + "@example.com");
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='TRAINER'
                """, id, id);
        jdbc.update("""
                INSERT INTO fitness.trainer_profiles
                (user_id,verification_status,verified_at,is_accepting_students,is_active)
                VALUES (?,'VERIFIED',clock_timestamp(),true,true)
                """, id);
        return id;
    }
    private static Nutrient nutrient(Day day, String code) {
        return day.nutrients().stream().filter(n -> code.equals(n.code())).findFirst().orElseThrow();
    }
}
