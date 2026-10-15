package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.nutrition.NutritionBasicService;
import com.fitnesscoaching.platform.modules.nutrition.NutritionFailure;
import com.fitnesscoaching.platform.modules.nutrition.NutritionStrategyService;
import com.fitnesscoaching.platform.modules.nutrition.NutritionStrategyService.*;
import com.fitnesscoaching.platform.modules.nutrition.NutritionTargetDayService;
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
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NutritionStrategyIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("nutrition_strategy_b07").withUsername("fitness_app")
            .withPassword("testpass123");
    static { postgres.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired NutritionStrategyService strategy;
    @Autowired NutritionTargetDayService days;
    @Autowired NutritionBasicService basic;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired CoachingSharingUseCase sharing;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void studentPublishesVersionAndFirstLogFreezesTargetWithoutChangingActual() throws Exception {
        UUID student = student();
        LocalDate first = today().plusDays(1);
        Goal goal = strategy.confirmChange(student, change("NEW_GOAL", null, null, null,
                first, "goal-create", targets("2000", "100")));
        assertThat(goal.status()).isEqualTo("ACTIVE");
        assertThat(goal.versions()).hasSize(1);
        assertThatThrownBy(() -> strategy.confirmChange(student, change("NEW_GOAL", null, null, null,
                first, "goal-create", targets("2100", "100"))))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_IDEMPOTENCY_CONFLICT");
        var preview = days.preview(student, first, "Etc/UTC");
        assertThat(preview.frozen()).isFalse();
        assertThat(preview.dayType()).isEqualTo("DEFAULT");
        assertThat(preview.targets()).hasSize(2);
        assertThat(preview.targets().getFirst().targetAmount()).isEqualByComparingTo("2000");
        assertThat(basic.readMine(student, first, "Etc/UTC").nutrients().getFirst().total()).isNull();
        var food = basic.createFood(student, new NutritionBasicService.FoodInput("Oats", "bowl",
                new BigDecimal("100"), null, null, null, null, "food-" + student));
        basic.addItem(student, first, new NutritionBasicService.ItemInput("Etc/UTC",
                first.atTime(12, 0).toInstant(ZoneOffset.UTC), "LUNCH", null, food.id(),
                BigDecimal.ONE, "log-" + student));
        assertThat(days.preview(student, first, "Etc/UTC").frozen()).isTrue();
        Goal changed = strategy.confirmChange(student, change("CHANGE_TARGET", goal.id(),
                goal.aggregateVersion(), goal.versions().getLast().id(), first.plusDays(1),
                "goal-change", targets("1800", "110")));
        assertThat(changed.versions()).hasSize(2);
        assertThat(days.preview(student, first, "Etc/UTC").targets().getFirst().targetAmount())
                .isEqualByComparingTo("2000");
        assertThat(days.preview(student, first.plusDays(1), "Etc/UTC").targets().getFirst().targetAmount())
                .isEqualByComparingTo("1800");
        assertThat(basic.readMine(student, first, "Etc/UTC").nutrients().getFirst().total())
                .isEqualByComparingTo("100");
        mvc.perform(get("/api/v1/nutrition/strategy/days/{day}", first)
                        .param("timezone", "Etc/UTC").with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.frozen").value(true));
    }

    @Test void explicitRestOverridePauseAndReplayKeepUnknownDistinctFromZero() {
        UUID student = student();
        LocalDate day = today().plusDays(2);
        Goal goal = strategy.confirmChange(student, change("NEW_GOAL", null, null, null,
                day, "create-" + student, targets("0", "100")));
        var rest = days.chooseDayType(student, day,
                new NutritionTargetDayService.DayTypeInput("Etc/UTC", "REST_DAY", "rest-" + student));
        assertThat(rest.dayType()).isEqualTo("REST_DAY");
        assertThat(rest.targets().getFirst().targetAmount()).isEqualByComparingTo("0");
        var override = new NutritionTargetDayService.OverrideInput("Etc/UTC",
                goal.versions().getFirst().id(), energy(), kcal(), new BigDecimal("1500"),
                "Special day", null, "override-" + student);
        var result = days.upsertOverride(student, day, override);
        assertThat(result.targets().getFirst().source()).isEqualTo("OVERRIDE");
        assertThat(days.upsertOverride(student, day, override)).isEqualTo(result);
        Goal paused = strategy.transition(student, goal.id(),
                new TransitionInput("PAUSE", goal.aggregateVersion(), "Recovery", "pause-" + student));
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(days.preview(student, day, "Etc/UTC").targets()).isEmpty();
        Goal resumed = strategy.transition(student, goal.id(),
                new TransitionInput("RESUME", paused.aggregateVersion(), "Ready", "resume-" + student));
        assertThat(resumed.status()).isEqualTo("ACTIVE");
        assertThat(days.preview(student, day, "Etc/UTC").targets().getFirst().targetAmount())
                .isEqualByComparingTo("1500");
        assertThatThrownBy(() -> strategy.confirmChange(student, change("NEW_GOAL", null, null,
                null, day, "second-" + student, targets("2000", "100"))))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_ACTIVE_GOAL_EXISTS");
    }

    @Test void trainerRequiresSeparateScopeAndStudentCanRejectAfterRevocation() {
        UUID student = student(), trainer = trainer();
        Goal goal = strategy.confirmChange(student, change("NEW_GOAL", null, null, null,
                today().plusDays(1), "initial-" + student, targets("2000", "100")));
        var pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        var active = lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT",
                0, null, UUID.randomUUID());
        ChangeInput draft = change("CHANGE_TARGET", goal.id(), goal.aggregateVersion(),
                goal.versions().getFirst().id(), today().plusDays(2), "proposal-" + student,
                targets("1800", "110"));
        ChangeInput change = new ChangeInput(draft.kind(), draft.goalId(), draft.expectedGoalVersion(),
                draft.baseVersionId(), draft.title(), draft.goalTypeId(), draft.timezone(),
                draft.effectiveFrom(), draft.targets(), false, draft.commandKey());
        assertThatThrownBy(() -> strategy.propose(trainer, student, change, "Adjust strategy"))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_PROPOSAL_NOT_FOUND");
        var grant = sharing.grantOrReplace(student, active.relationship().id(), DataScope.NUTRITION_GOAL,
                SharingDecision.ALLOW, DataAccessLevel.CONTRIBUTE, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, UUID.randomUUID());
        Proposal proposal = strategy.propose(trainer, student, change, "Adjust strategy");
        assertThat(proposal.status()).isEqualTo("PENDING");
        assertThat(strategy.proposalsForTrainer(trainer, student)).hasSize(1);
        sharing.revoke(student, active.relationship().id(), grant.id(), grant.version(),
                "Stop sharing", UUID.randomUUID());
        assertThatThrownBy(() -> strategy.proposalsForTrainer(trainer, student))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_PROPOSAL_NOT_FOUND");
        assertThatThrownBy(() -> strategy.decide(student, proposal.id(),
                new DecisionInput("ACCEPT", 0, "accept-" + student)))
                .isInstanceOf(NutritionFailure.class).extracting("code")
                .isEqualTo("NUTRITION_PROPOSAL_STALE");
        assertThat(strategy.proposalDetail(student, proposal.id()).status()).isEqualTo("PENDING");
        Proposal rejected = strategy.decide(student, proposal.id(),
                new DecisionInput("REJECT", 0, "reject-" + student));
        assertThat(rejected.status()).isEqualTo("REJECTED");
    }

    @Test void trainerProposalAcceptPublishesOnlyAfterStudentDecisionAndStaleRemainsPending() {
        UUID student = student(), trainer = trainer();
        Goal initial = strategy.confirmChange(student, change("NEW_GOAL", null, null, null,
                today().plusDays(1), "initial-" + student, targets("2000", "100")));
        var pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        var active = lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT",
                0, null, UUID.randomUUID());
        sharing.grantOrReplace(student, active.relationship().id(), DataScope.NUTRITION_GOAL,
                SharingDecision.ALLOW, DataAccessLevel.CONTRIBUTE, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, UUID.randomUUID());
        ChangeInput draft = change("CHANGE_TARGET", initial.id(), initial.aggregateVersion(),
                initial.versions().getFirst().id(), today().plusDays(2), "proposal-" + student,
                targets("1800", "110"));
        ChangeInput change = new ChangeInput(draft.kind(), draft.goalId(), draft.expectedGoalVersion(),
                draft.baseVersionId(), draft.title(), draft.goalTypeId(), draft.timezone(),
                draft.effectiveFrom(), draft.targets(), false, draft.commandKey());
        Proposal proposal = strategy.propose(trainer, student, change, "Adjust strategy");
        assertThat(strategy.mine(student).goal().versions()).hasSize(1);
        Proposal accepted = strategy.decide(student, proposal.id(),
                new DecisionInput("ACCEPT", proposal.proposalVersion(), "accept-" + student));
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(strategy.decide(student, proposal.id(),
                new DecisionInput("ACCEPT", proposal.proposalVersion(), "accept-" + student)))
                .isEqualTo(accepted);
        assertThat(strategy.mine(student).goal().versions()).hasSize(2);
        assertThat(strategy.mine(student).goal().versions().getLast().targets().getFirst().targetAmount())
                .isEqualByComparingTo("1800");
    }

    @Test void httpCatalogAndStrategyBodyUseAuthoritativeUnitIdentities() throws Exception {
        UUID student = student();
        int typeId = goalType(), energyId = energy(), unitId = kcal();
        mvc.perform(get("/api/v1/nutrition/strategy/target-nutrients")
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='B07_ENERGY_KCAL')].defaultUnitId")
                        .value(org.hamcrest.Matchers.hasItem(unitId)));
        String body = """
                {"kind":"NEW_GOAL","title":"Nutrition target","goalTypeId":%d,
                 "timezone":"Etc/UTC","effectiveFrom":"%s","confirmed":true,
                 "commandKey":"http-%s","targets":[{"nutrientId":%d,"unitId":%d,
                 "dayType":"DEFAULT","targetAmount":0}]}
                """.formatted(typeId, today().plusDays(1), student, energyId, unitId);
        mvc.perform(post("/api/v1/nutrition/strategy/goals")
                        .with(jwt().jwt(j -> j.subject(student.toString())))
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].targets[0].unitId").value(unitId))
                .andExpect(jsonPath("$.versions[0].targets[0].targetAmount").value(0));
    }

    @Test void workoutOwnedSignalChangesOnlyUnfrozenPreview() {
        UUID student = student();
        LocalDate day = today().plusDays(3);
        Goal goal = strategy.confirmChange(student, change("NEW_GOAL", null, null, null,
                day, "signal-" + student, List.of(
                        new TargetSpec(energy(), kcal(), "DEFAULT", new BigDecimal("2000")),
                        new TargetSpec(energy(), kcal(), "TRAINING_DAY", new BigDecimal("2400")))));
        UUID occurrence = UUID.randomUUID();
        Instant start = day.atTime(12, 0).toInstant(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                  (id,student_id,planned_start_at,planned_end_at,original_planned_start_at,created_by)
                VALUES (?,?,?,?,?,?)
                """, occurrence, student, Timestamp.from(start), Timestamp.from(start.plusSeconds(3600)),
                Timestamp.from(start), student);
        assertThat(days.preview(student, day, "Etc/UTC").dayType()).isEqualTo("TRAINING_DAY");
        assertThat(days.preview(student, day, "Etc/UTC").targets().getFirst().targetAmount())
                .isEqualByComparingTo("2400");
        jdbc.update("UPDATE fitness.planned_workouts SET status='CANCELLED',version=version+1 WHERE id=?", occurrence);
        assertThat(days.preview(student, day, "Etc/UTC").dayType()).isEqualTo("DEFAULT");
        assertThat(days.preview(student, day, "Etc/UTC").targets().getFirst().targetAmount())
                .isEqualByComparingTo("2000");
        days.chooseDayType(student, day, new NutritionTargetDayService.DayTypeInput("Etc/UTC", "REST_DAY", "rest-" + student));
        assertThat(days.preview(student, day, "Etc/UTC").dayType()).isEqualTo("REST_DAY");
        assertThat(goal.versions()).hasSize(1);
    }

    @Test void explicitRestChoiceDoesNotSilentlyMoveToANewCalendarTimezone() {
        UUID student = student();
        LocalDate start = today().plusDays(1), day = start.plusDays(2);
        Goal first = strategy.confirmChange(student, change("NEW_GOAL", null, null, null,
                start, "zone-first-" + student, targets("2000", "100")));
        days.chooseDayType(student, day, new NutritionTargetDayService.DayTypeInput(
                "Etc/UTC", "REST_DAY", "zone-rest-" + student));
        assertThat(days.preview(student, day, "Etc/UTC").dayType()).isEqualTo("REST_DAY");
        ChangeInput replacement = new ChangeInput("CHANGE_TARGET", first.id(), first.aggregateVersion(),
                first.versions().getFirst().id(), "Nutrition goal", goalType(), "Asia/Bangkok", day,
                targets("1800", "100"), true, "zone-next-" + student);
        strategy.confirmChange(student, replacement);
        assertThat(days.preview(student, day, "Asia/Bangkok").dayType()).isEqualTo("DEFAULT");
    }

    private ChangeInput change(String kind, UUID goalId, Long expected, UUID base,
                               LocalDate from, String key, List<TargetSpec> targets) {
        return new ChangeInput(kind, goalId, expected, base, "Nutrition goal", goalType(),
                "Etc/UTC", from, targets, true, key);
    }
    private List<TargetSpec> targets(String energy, String protein) {
        return List.of(new TargetSpec(energy(), kcal(), "DEFAULT", new BigDecimal(energy)),
                new TargetSpec(protein(), gram(), "DEFAULT", new BigDecimal(protein)));
    }
    private int goalType() {
        jdbc.update("""
                INSERT INTO fitness.nutrition_goal_types(code,name)
                VALUES ('GENERAL_HEALTH','General Health') ON CONFLICT(code) DO NOTHING
                """);
        return jdbc.queryForObject("SELECT id FROM fitness.nutrition_goal_types WHERE code='GENERAL_HEALTH'",
                Integer.class);
    }
    private int energy() { return jdbc.queryForObject("SELECT id FROM fitness.nutrients WHERE code='B07_ENERGY_KCAL'", Integer.class); }
    private int protein() { return jdbc.queryForObject("SELECT id FROM fitness.nutrients WHERE code='B07_PROTEIN_G'", Integer.class); }
    private int kcal() { return jdbc.queryForObject("SELECT id FROM fitness.measurement_units WHERE code='B07_KCAL'", Integer.class); }
    private int gram() { return jdbc.queryForObject("SELECT id FROM fitness.measurement_units WHERE code='B07_GRAM'", Integer.class); }
    private LocalDate today() { return jdbc.queryForObject("SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date", LocalDate.class); }

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
}
