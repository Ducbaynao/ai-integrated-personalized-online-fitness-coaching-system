package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.ChangeType;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetRef;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutAdjustmentUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.ActivateCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.CreateDraftCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.ExercisePrescription;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AiWorkoutAdjustmentFullSliceIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_f03_slice_test").withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired
    private AiWorkoutAdjustmentUseCase adjustmentService;

    @Autowired
    private AiRecommendationRepository recommendationRepository;

    @Autowired
    private WorkoutPlanQueryUseCase workoutPlanQuery;

    @Autowired
    private WorkoutPlanCommandUseCase workoutPlanCommands;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID exerciseVariation1;
    private UUID exerciseVariation2;

    @BeforeEach
    void seedExercises() {
        UUID adminId = person("ADMIN");
        exerciseVariation1 = UUID.fromString("b0000000-0000-4000-8000-000000000001");
        exerciseVariation2 = UUID.fromString("b0000000-0000-4000-8000-000000000002");

        UUID exId1 = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?) ON CONFLICT (id) DO NOTHING",
                exId1, "f03-ex-1-" + UUID.randomUUID(), "Barbell Back Squat", adminId);
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true) ON CONFLICT (id) DO UPDATE SET is_active = true",
                exerciseVariation1, exId1, "f03-var-1-" + UUID.randomUUID(), "Standard Back Squat");

        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true) ON CONFLICT (id) DO UPDATE SET is_active = true",
                exerciseVariation2, exId1, "f03-var-2-" + UUID.randomUUID(), "Low Bar Back Squat");
    }

    private UUID person(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at) " +
                "VALUES (?, ?, 'hash', 'Test User', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())", id, "user-" + id + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id, role_id, assigned_by, assigned_at) SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = ?",
                id, id, role);
        if ("STUDENT".equals(role)) {
            jdbc.update("INSERT INTO fitness.student_profiles(user_id, training_experience_level, available_days_per_week, preferred_session_minutes, created_at, updated_at) " +
                    "VALUES (?, 'BEGINNER', 3, 60, now(), now())", id);
        } else if ("TRAINER".equals(role)) {
            jdbc.update("""
                    INSERT INTO fitness.trainer_profiles
                    (user_id, verification_status, verified_at, is_accepting_students, is_active, activity_status, created_at, updated_at)
                    VALUES (?, 'VERIFIED', clock_timestamp(), true, true, 'ACTIVE', now(), now())
                    """, id);
        }
        return id;
    }

    private UUID createSelfDirectedPeriod(UUID student) {
        UUID period = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,created_by) VALUES (?,?,'SELF_DIRECTED',clock_timestamp()-interval '1 day',?)",
                period, student, student);
        return period;
    }

    private record HumanCoachSetup(UUID studentId, UUID trainerId, UUID relationshipId, UUID periodId) {}

    private HumanCoachSetup createHumanCoachSetup() {
        UUID student = person("STUDENT");
        UUID trainer = person("TRAINER");
        UUID relationship = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                (id,student_id,trainer_id,status,requested_by,requested_at,accepted_at,started_at)
                VALUES (?,?,?,'ACTIVE',?,clock_timestamp()-interval '2 days',
                        clock_timestamp()-interval '2 days',clock_timestamp()-interval '2 days')
                """, relationship, student, trainer, student);
        UUID period = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_periods
                (id,student_id,mode,coaching_relationship_id,trainer_id,started_at,created_by)
                VALUES (?,?,'HUMAN_COACH',?,?,clock_timestamp()-interval '2 days',?)
                """, period, student, relationship, trainer, trainer);
        UUID permission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id,relationship_id,student_id,trainer_id,data_scope,decision,access_level,valid_from,granted_by)
                VALUES (?,?,?,?, 'WORKOUT_PLAN','ALLOW','MANAGE'::fitness.data_access_level,
                        clock_timestamp()-interval '1 day',?)
                """, permission, relationship, student, trainer, student);
        return new HumanCoachSetup(student, trainer, relationship, period);
    }

    private PlanDetail activePlan(UUID student, UUID actor) {
        UUID rxId = UUID.randomUUID();
        var rx = new ExercisePrescription(rxId, exerciseVariation1, 1, 3, 8, 12, BigDecimal.valueOf(80.0), 90, null, "Standard");
        var session = new WorkoutSessionTemplate(UUID.randomUUID(), 1, 1, 1, "Day 1", "Legs", 60, null, List.of(rx));
        var draft = workoutPlanCommands.createDraft(new CreateDraftCommand(student, actor, "Test Plan", "Desc", List.of(session), "k-draft-" + UUID.randomUUID()));
        workoutPlanCommands.activate(new ActivateCommand(draft.planId(), actor, 0L, "k-act-" + UUID.randomUUID()));
        return workoutPlanQuery.findActive(actor, student).orElseThrow();
    }

    private UUID materializeOccurrence(UUID student, UUID periodId, PlanDetail plan) {
        UUID occId = UUID.randomUUID();
        UUID sessionId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).id();
        Instant plannedStart = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant plannedEnd = plannedStart.plus(1, ChronoUnit.HOURS);

        jdbc.update("""
            INSERT INTO fitness.planned_workouts(
                id, student_id, coaching_period_id, workout_plan_session_id, planned_start_at,
                planned_end_at, original_planned_start_at, status, version, created_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'SCHEDULED'::fitness.planned_workout_status, 1, ?)
            """, occId, student, periodId, sessionId,
                java.sql.Timestamp.from(plannedStart), java.sql.Timestamp.from(plannedEnd),
                java.sql.Timestamp.from(plannedStart), student);

        return occId;
    }

    @Test
    @DisplayName("Generate, Accept and Apply Workout Occurrence Adjustment end-to-end")
    void generateAndAcceptOccurrenceAdjustment_fullSlice() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-gen-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Progressing well",
                List.of(),
                List.of(),
                true
        );

        // 1. Generate Adjustment Proposal
        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);
        assertThat(genRes).isNotNull();
        assertThat(genRes.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(genRes.recommendationId()).isNotNull();

        // Verify recommendation persisted in DB
        AiRecommendation rec = recommendationRepository.findById(genRes.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(rec.recommendationType()).isEqualTo("WORKOUT_ADJUSTMENT_PROPOSAL");

        // 2. Accept Adjustment
        String acceptCommandKey = "cmd-f03-accept-" + UUID.randomUUID();
        var acceptReq = new AcceptAdjustmentApiRequest(acceptCommandKey, "Looks good", 1, 1L);
        AcceptAdjustmentApiResponse acceptRes = adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq);

        assertThat(acceptRes).isNotNull();
        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(acceptRes.resultingEntityType()).isEqualTo("WORKOUT_OCCURRENCE");
        assertThat(acceptRes.resultingEntityId()).isEqualTo(occurrenceId);
        assertThat(acceptRes.replayed()).isFalse();

        // 3. Verify Database State
        Integer occVer = jdbc.queryForObject("SELECT version FROM fitness.planned_workouts WHERE id = ?", Integer.class, occurrenceId);
        assertThat(occVer).isGreaterThanOrEqualTo(2);

        Integer adjCount = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Integer.class, occurrenceId);
        assertThat(adjCount).isEqualTo(1);

        AiRecommendation appliedRec = recommendationRepository.findById(genRes.recommendationId()).orElseThrow();
        assertThat(appliedRec.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        Integer appCount = jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?", Integer.class, genRes.recommendationId());
        assertThat(appCount).isEqualTo(1);

        // 4. Idempotent Replay returns identical response without duplicate adjustment insertion
        AcceptAdjustmentApiResponse replayRes = adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq);
        assertThat(replayRes.replayed()).isTrue();
        assertThat(replayRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(replayRes.resultingEntityId()).isEqualTo(occurrenceId);

        Integer adjCountAfterReplay = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Integer.class, occurrenceId);
        assertThat(adjCountAfterReplay).isEqualTo(1);
    }

    @Test
    @DisplayName("Generate and Reject Workout Adjustment Proposal end-to-end")
    void generateAndRejectAdjustment_fullSlice() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-gen-rej-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Progressing well",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);

        var rejectReq = new RejectAdjustmentApiRequest("Prefer keeping current weight");
        RejectAdjustmentApiResponse rejectRes = adjustmentService.rejectAdjustment(student, genRes.recommendationId(), rejectReq);

        assertThat(rejectRes).isNotNull();
        assertThat(rejectRes.status()).isEqualTo(AiRecommendationStatus.REJECTED);
        assertThat(rejectRes.reason()).isEqualTo("Prefer keeping current weight");

        AiRecommendation rejectedRec = recommendationRepository.findById(genRes.recommendationId()).orElseThrow();
        assertThat(rejectedRec.status()).isEqualTo(AiRecommendationStatus.REJECTED);

        var acceptReq = new AcceptAdjustmentApiRequest("cmd-late", null, 1, 1L);
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("cannot be accepted");
    }

    @Test
    @DisplayName("In HUMAN_COACH mode, Student cannot accept adjustment proposal (403 Forbidden)")
    void humanCoachMode_studentCannotAcceptAdjustment_forbidden() {
        HumanCoachSetup setup = createHumanCoachSetup();
        UUID student = setup.studentId();
        UUID trainer = setup.trainerId();
        PlanDetail plan = activePlan(student, trainer);
        UUID occurrenceId = materializeOccurrence(student, setup.periodId(), plan);

        UUID rxId = workoutPlanQuery.version(trainer, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-trainer-gen-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Trainer progressive overload",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(trainer, req);
        assertThat(genRes.status()).isEqualTo(AiRecommendationStatus.PENDING);

        var studentAcceptReq = new AcceptAdjustmentApiRequest("cmd-student-hack", "Student approve", 1, 1L);
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, genRes.recommendationId(), studentAcceptReq))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Only the currently assigned coach has decision authority");

        var trainerAcceptReq = new AcceptAdjustmentApiRequest("cmd-trainer-ok", "Trainer approved", 1, 1L);
        AcceptAdjustmentApiResponse trainerAcceptRes = adjustmentService.acceptAdjustment(trainer, genRes.recommendationId(), trainerAcceptReq);
        assertThat(trainerAcceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);
    }


    @Test
    @DisplayName("Generate, Accept and Apply Workout Template Adjustment end-to-end")
    void generateAndAcceptTemplateAdjustment_fullSlice() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.TEMPLATE_OR_FUTURE_OCCURRENCES, null, null, plan.plan().id(), plan.currentVersion().versionNumber(), null, rxId);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-gen-tpl-" + UUID.randomUUID(),
                TargetScope.TEMPLATE_OR_FUTURE_OCCURRENCES,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Progressing template load",
                List.of(),
                List.of(),
                true
        );

        // 1. Generate Adjustment Proposal
        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);
        assertThat(genRes).isNotNull();
        assertThat(genRes.status()).isEqualTo(AiRecommendationStatus.PENDING);

        // 2. Accept Adjustment
        String acceptCommandKey = "cmd-f03-accept-tpl-" + UUID.randomUUID();
        var acceptReq = new AcceptAdjustmentApiRequest(acceptCommandKey, "Template load looks good", 1, plan.plan().aggregateVersion());
        AcceptAdjustmentApiResponse acceptRes = adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq);

        assertThat(acceptRes).isNotNull();
        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(acceptRes.resultingEntityType()).isEqualTo("WORKOUT_PLAN");
        assertThat(acceptRes.resultingEntityId()).isEqualTo(plan.plan().id());
        assertThat(acceptRes.replayed()).isFalse();

        // 3. Verify Database State
        PlanDetail updatedPlan = workoutPlanQuery.findActive(student, student).orElseThrow();
        assertThat(updatedPlan.plan().aggregateVersion()).isGreaterThan(plan.plan().aggregateVersion());

        // Check new version prescriptions (CONFIRMED_NEW trainee safely receives conservative load delta)
        var newRx = workoutPlanQuery.version(student, plan.plan().id(), updatedPlan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0);
        assertThat(newRx.targetLoad()).isEqualByComparingTo(BigDecimal.valueOf(77.5));

        // 4. Verify Application Receipt
        Integer appCount = jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?", Integer.class, genRes.recommendationId());
        assertThat(appCount).isEqualTo(1);
    }

    @Test
    @DisplayName("When occurrence has already started or completed, adjustment accept is rejected with conflict")
    void occurrenceStarted_cannotAdjust_lifecycleConflict() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-started-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Adjust load",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);

        // Transition occurrence to COMPLETED directly in DB (with valid version increment)
        jdbc.update("UPDATE fitness.planned_workouts SET status = 'COMPLETED'::fitness.planned_workout_status, version = version + 1 WHERE id = ?", occurrenceId);

        var acceptReq = new AcceptAdjustmentApiRequest("cmd-f03-accept-started", "Accept started", 1, 1L);
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("only SCHEDULED occurrences can be adjusted");

        // Verify recommendation is still PENDING
        AiRecommendation rec = recommendationRepository.findById(genRes.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);
    }

    @Test
    @DisplayName("When occurrence version changed after generation, accept is rejected due to version conflict")
    void staleOccurrenceVersion_cannotAdjust_lifecycleConflict() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-stale-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Adjust load",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);

        // Increment version in planned_workouts to simulate concurrent modification
        jdbc.update("UPDATE fitness.planned_workouts SET version = version + 1 WHERE id = ?", occurrenceId);

        var acceptReq = new AcceptAdjustmentApiRequest("cmd-f03-accept-stale", "Accept stale", 1, 1L);
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("version conflict");
    }

    @Test
    @DisplayName("Replay with same commandKey but differing decisionNote or revision is rejected as idempotency conflict")
    void replayWithDifferingFingerprint_idempotencyConflict() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-fingerprint-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Adjust load",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);

        String commandKey = "cmd-key-fingerprint-" + UUID.randomUUID();
        var acceptReq = new AcceptAdjustmentApiRequest(commandKey, "Initial note", 1, 1L);
        AcceptAdjustmentApiResponse acceptRes = adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq);
        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        // Same commandKey, but different note
        var tamperedReq = new AcceptAdjustmentApiRequest(commandKey, "Different modified note", 1, 1L);
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, genRes.recommendationId(), tamperedReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Idempotency conflict: commandKey matches a previous command with differing parameters");
    }

    @Test
    @DisplayName("Replaying accept after user account is suspended is forbidden (403)")
    void replayAfterAuthorityRevoked_forbidden() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-revoked-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Adjust load",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);

        String commandKey = "cmd-key-revoked-" + UUID.randomUUID();
        var acceptReq = new AcceptAdjustmentApiRequest(commandKey, "Good note", 1, 1L);
        AcceptAdjustmentApiResponse acceptRes = adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq);
        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        // Deactivate student account
        jdbc.update("UPDATE fitness.users SET status = 'SUSPENDED' WHERE id = ?", student);

        // Replay attempt must be rejected with 403 Access Denied
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Actor account is not active");
    }

    @Test
    @DisplayName("Rejecting proposal updates recommendation lifecycle to REJECTED without mutating workout domain")
    void rejectRecommendation_doesNotMutateWorkout() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-reject-clean-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Adjust load",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);

        Integer adjustmentsBefore = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Integer.class, occurrenceId);
        Integer occurrenceVersionBefore = jdbc.queryForObject("SELECT version FROM fitness.planned_workouts WHERE id = ?", Integer.class, occurrenceId);

        var rejectReq = new RejectAdjustmentApiRequest("Trainee prefers current load");
        RejectAdjustmentApiResponse rejectRes = adjustmentService.rejectAdjustment(student, genRes.recommendationId(), rejectReq);

        assertThat(rejectRes.status()).isEqualTo(AiRecommendationStatus.REJECTED);
        assertThat(rejectRes.rejectedBy()).isEqualTo(student);

        // Verify zero mutations in workout domain
        Integer adjustmentsAfter = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Integer.class, occurrenceId);
        Integer occurrenceVersionAfter = jdbc.queryForObject("SELECT version FROM fitness.planned_workouts WHERE id = ?", Integer.class, occurrenceId);
        Integer appCount = jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?", Integer.class, genRes.recommendationId());

        assertThat(adjustmentsAfter).isEqualTo(adjustmentsBefore);
        assertThat(occurrenceVersionAfter).isEqualTo(occurrenceVersionBefore);
        assertThat(appCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Concurrency: Two threads attempting to accept the same proposal simultaneously result in exactly one APPLIED and one conflict")
    void concurrentDoubleAccept_onlyOneSucceeds_otherFailsWithConflict() throws Exception {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-conc-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Progressive overload",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);
        UUID recId = genRes.recommendationId();

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                var acceptReq = new AcceptAdjustmentApiRequest("cmd-f03-conc-accept-" + index + "-" + UUID.randomUUID(), "Note " + index, 1, 1L);
                return adjustmentService.acceptAdjustment(student, recId, acceptReq);
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int successes = 0;
        int conflicts = 0;

        for (Future<Object> f : futures) {
            try {
                Object res = f.get(10, TimeUnit.SECONDS);
                if (res instanceof AcceptAdjustmentApiResponse aRes && aRes.status() == AiRecommendationStatus.APPLIED) {
                    successes++;
                }
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof AiRunLifecycleConflictException) {
                    conflicts++;
                } else {
                    throw e;
                }
            }
        }
        executor.shutdown();

        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        AiRecommendation finalRec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(finalRec.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        Integer appCount = jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?", Integer.class, recId);
        assertThat(appCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrency: Simultaneous Accept and Reject calls on the same proposal resolve cleanly without dirty state")
    void concurrentAcceptAndReject_raceResolvesCleanly() throws Exception {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-race-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Progressive overload",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);
        UUID recId = genRes.recommendationId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Object> acceptFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            var acceptReq = new AcceptAdjustmentApiRequest("cmd-f03-race-accept-" + UUID.randomUUID(), "Accept in race", 1, 1L);
            return adjustmentService.acceptAdjustment(student, recId, acceptReq);
        });

        Future<Object> rejectFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            var rejectReq = new RejectAdjustmentApiRequest("Reject in race");
            return adjustmentService.rejectAdjustment(student, recId, rejectReq);
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        boolean acceptSuccess = false;
        boolean rejectSuccess = false;

        try {
            Object res = acceptFuture.get(10, TimeUnit.SECONDS);
            if (res instanceof AcceptAdjustmentApiResponse aRes && aRes.status() == AiRecommendationStatus.APPLIED) {
                acceptSuccess = true;
            }
        } catch (Exception e) {
            assertThat(e.getCause()).isInstanceOf(AiRunLifecycleConflictException.class);
        }

        try {
            Object res = rejectFuture.get(10, TimeUnit.SECONDS);
            if (res instanceof RejectAdjustmentApiResponse rRes && rRes.status() == AiRecommendationStatus.REJECTED) {
                rejectSuccess = true;
            }
        } catch (Exception e) {
            assertThat(e.getCause()).isInstanceOf(AiRunLifecycleConflictException.class);
        }

        executor.shutdown();

        assertThat(acceptSuccess ^ rejectSuccess).isTrue();

        AiRecommendation finalRec = recommendationRepository.findById(recId).orElseThrow();
        if (acceptSuccess) {
            assertThat(finalRec.status()).isEqualTo(AiRecommendationStatus.APPLIED);
            Integer appCount = jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?", Integer.class, recId);
            assertThat(appCount).isEqualTo(1);
        } else {
            assertThat(finalRec.status()).isEqualTo(AiRecommendationStatus.REJECTED);
            Integer appCount = jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?", Integer.class, recId);
            assertThat(appCount).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Target Conflict: Two distinct recommendations for the same occurrence version; first accept succeeds, second is rejected on version guard")
    void twoRecommendationsTargetingSameOccurrence_secondAcceptRejectedDueToStaleVersion() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req1 = new WorkoutAdjustmentApiRequest(student, "cmd-f03-two-1-" + UUID.randomUUID(), TargetScope.ONE_MATERIALIZED_OCCURRENCE, targetRef, ChangeType.LOAD_ADJUSTMENT, "Rec 1", List.of(), List.of(), true);
        var req2 = new WorkoutAdjustmentApiRequest(student, "cmd-f03-two-2-" + UUID.randomUUID(), TargetScope.ONE_MATERIALIZED_OCCURRENCE, targetRef, ChangeType.LOAD_ADJUSTMENT, "Rec 2", List.of(), List.of(), true);

        WorkoutAdjustmentApiResponse res1 = adjustmentService.generateAdjustment(student, req1);
        WorkoutAdjustmentApiResponse res2 = adjustmentService.generateAdjustment(student, req2);

        var accept1 = new AcceptAdjustmentApiRequest("cmd-acc-two-1-" + UUID.randomUUID(), "Accept 1", 1, 1L);
        AcceptAdjustmentApiResponse accRes1 = adjustmentService.acceptAdjustment(student, res1.recommendationId(), accept1);
        assertThat(accRes1.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        var accept2 = new AcceptAdjustmentApiRequest("cmd-acc-two-2-" + UUID.randomUUID(), "Accept 2", 1, 1L);
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, res2.recommendationId(), accept2))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("version conflict");

        AiRecommendation rec2 = recommendationRepository.findById(res2.recommendationId()).orElseThrow();
        assertThat(rec2.status()).isEqualTo(AiRecommendationStatus.PENDING);
    }

    @Test
    @DisplayName("Lifecycle Guard: Occurrence with status ATTEMPTED (in-progress/started but incomplete) cannot be adjusted")
    void attemptedOccurrence_cannotAdjust_lifecycleConflict() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(student, "cmd-f03-att-" + UUID.randomUUID(), TargetScope.ONE_MATERIALIZED_OCCURRENCE, targetRef, ChangeType.LOAD_ADJUSTMENT, "Adjust load", List.of(), List.of(), true);

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);

        jdbc.update("UPDATE fitness.planned_workouts SET status = 'ATTEMPTED'::fitness.planned_workout_status, version = version + 1 WHERE id = ?", occurrenceId);

        var acceptReq = new AcceptAdjustmentApiRequest("cmd-f03-accept-att", "Accept attempted", 1, 1L);
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("only SCHEDULED occurrences can be adjusted");
    }

    @Test
    @DisplayName("Catalog Guard: When replacement exercise variation is deactivated before accept, revalidation rejects accept")
    void exerciseSubstitution_replacementDeactivatedBeforeAccept_rejectedByRevalidation() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-sub-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.EXERCISE_SUBSTITUTION,
                "Equipment unavailable, swap to goblet squat",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);
        assertThat(genRes.status()).isEqualTo(AiRecommendationStatus.PENDING);

        jdbc.update("UPDATE fitness.exercise_variations SET is_active = false WHERE id = ?", exerciseVariation2);

        var acceptReq = new AcceptAdjustmentApiRequest("cmd-f03-sub-accept", "Accept sub", 1, 1L);
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("WORKOUT_PLAN_EXERCISE_UNAVAILABLE");

        AiRecommendation rec = recommendationRepository.findById(genRes.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);
    }

    @Test
    @DisplayName("Idempotency: Replaying accept with same commandKey but differing expectedTargetVersion throws conflict")
    void replayWithDifferingExpectedTargetVersion_idempotencyConflict() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(student, "cmd-f03-idem-ver-" + UUID.randomUUID(), TargetScope.ONE_MATERIALIZED_OCCURRENCE, targetRef, ChangeType.LOAD_ADJUSTMENT, "Adjust load", List.of(), List.of(), true);

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);

        String commandKey = "cmd-key-idem-ver-" + UUID.randomUUID();
        var acceptReq = new AcceptAdjustmentApiRequest(commandKey, "Accept note", 1, 1L);
        AcceptAdjustmentApiResponse acceptRes = adjustmentService.acceptAdjustment(student, genRes.recommendationId(), acceptReq);
        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        var tamperedReq = new AcceptAdjustmentApiRequest(commandKey, "Accept note", 1, 999L);
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, genRes.recommendationId(), tamperedReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Idempotency conflict: commandKey matches a previous command with differing parameters");
    }
}
