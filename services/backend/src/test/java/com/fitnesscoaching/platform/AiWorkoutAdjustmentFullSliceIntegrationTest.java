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
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.ShareAdjustmentDraftApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.ShareAdjustmentDraftApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.UpdateAdjustmentDraftApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.UpdateAdjustmentDraftApiResponse;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutAdjustmentUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionSnapshotPersistencePort;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import static org.mockito.ArgumentMatchers.any;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
        registry.add("ai.agent.proposals-enabled", () -> "true");
    }

    @Autowired
    private AiWorkoutAdjustmentUseCase adjustmentService;

    @Autowired private com.fitnesscoaching.platform.modules.ai.application.service.AgentToolGatewayService agentGateway;
    @Autowired private com.fitnesscoaching.platform.modules.ai.application.port.in.AiRunF01UseCase agentRuns;
    @Autowired private com.fitnesscoaching.platform.modules.ai.application.service.AiAgentConversationService conversations;
    @Autowired private com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase sharing;

    @Autowired
    private AiRecommendationRepository recommendationRepository;

    @Autowired
    private WorkoutPlanQueryUseCase workoutPlanQuery;

    @MockitoSpyBean
    private WorkoutPlanCommandUseCase workoutPlanCommands;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkoutExecutionSnapshotPersistencePort executionSnapshotPort;

    @MockitoSpyBean
    private AuditService auditService;


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

    private int kgUnitId() {
        return jdbc.queryForObject("SELECT id FROM fitness.measurement_units WHERE code = 'KG'", Integer.class);
    }

    private PlanDetail activePlan(UUID student, UUID actor) {
        return activePlanWithLoad(student, actor, kgUnitId(), BigDecimal.valueOf(80.0));
    }

    private PlanDetail activePlanWithLoad(UUID student, UUID actor, Integer loadUnitId, BigDecimal load) {
        UUID rxId = UUID.randomUUID();
        var rx = new ExercisePrescription(rxId, exerciseVariation1, 1, 3, 8, 12, load, 90, null, "Standard");
        var session = new WorkoutSessionTemplate(UUID.randomUUID(), 1, 1, 1, "Day 1", "Legs", 60, null, List.of(rx));
        var draft = workoutPlanCommands.createDraft(new CreateDraftCommand(student, actor, "Test Plan", "Desc", List.of(session), "k-draft-" + UUID.randomUUID()));
        if (loadUnitId != null) {
            jdbc.update("""
                    UPDATE fitness.workout_plan_session_exercises SET load_unit_id = ?
                    WHERE workout_plan_session_id IN (
                        SELECT s.id FROM fitness.workout_plan_sessions s
                        JOIN fitness.workout_plan_versions v ON v.id = s.workout_plan_version_id
                        WHERE v.workout_plan_id = ?
                    )
                    """, loadUnitId, draft.planId());
        }
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
    void agentGatewayCreatesPendingF03ForBoundStudentWithoutApplying() {
        UUID student = person("STUDENT");
        createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID prescription = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().getFirst().prescriptions().getFirst().id();
        var run = agentRuns.createF01Run(student,
                new com.fitnesscoaching.platform.modules.ai.application.port.in.CreateF01AiRunCommand(
                        "Adjust my current workout", "vi", null, UUID.randomUUID(), student, false));
        var args = Map.<String, Object>of("scope", "TEMPLATE_OR_FUTURE_OCCURRENCES",
                "targetPlanId", plan.plan().id().toString(), "planVersionNumber", plan.currentVersion().versionNumber(),
                "exercisePrescriptionId", prescription.toString(), "reason", "Equipment unavailable",
                "requestedChange", "Substitute exercise");
        var input = new com.fitnesscoaching.platform.modules.ai.application.service.AgentToolGatewayService.ToolCallInput(
                run.id(), student, "STUDENT", student, args);
        var result = agentGateway.prepareWorkoutAdjustment(input);
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.proposalDraft().status()).isEqualTo("PENDING");
        var recommendation = recommendationRepository.findById(result.proposalDraft().recommendationId()).orElseThrow();
        assertThat(recommendation.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(workoutPlanQuery.findActive(student, student).orElseThrow().currentVersion().id())
                .isEqualTo(plan.currentVersion().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?",
                Integer.class, recommendation.id())).isZero();

        var wrongVersion = new java.util.HashMap<>(args);
        wrongVersion.put("planVersionNumber", plan.currentVersion().versionNumber() + 1);
        assertThatThrownBy(() -> agentGateway.prepareWorkoutAdjustment(
                new com.fitnesscoaching.platform.modules.ai.application.service.AgentToolGatewayService.ToolCallInput(
                        run.id(), student, "STUDENT", student, wrongVersion))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> agentGateway.prepareWorkoutAdjustment(
                new com.fitnesscoaching.platform.modules.ai.application.service.AgentToolGatewayService.ToolCallInput(
                        run.id(), student, "STUDENT", UUID.randomUUID(), args))).isInstanceOf(AiRunAccessDeniedException.class);
    }

    @Test
    void revokedTrainerConsentBlocksConversationReadListAndSearchTool() {
        var setup = createHumanCoachSetup();
        UUID permission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id,relationship_id,student_id,trainer_id,data_scope,decision,access_level,valid_from,granted_by)
                VALUES (?,?,?,?, 'WORKOUT_HISTORY','ALLOW','VIEW'::fitness.data_access_level,
                        clock_timestamp()-interval '1 day',?)
                """, permission, setup.relationshipId(), setup.studentId(), setup.trainerId(), setup.studentId());
        var conversation = conversations.createConversation(setup.trainerId(), "TRAINER", setup.studentId(), "Review");
        conversations.getConversation(setup.trainerId(), conversation.conversationId());
        // A minimal authorized run binding is sufficient to test live tool authorization.
        var run = agentRuns.createF01Run(setup.trainerId(),
                new com.fitnesscoaching.platform.modules.ai.application.port.in.CreateF01AiRunCommand(
                        "General fitness knowledge", "vi", null, UUID.randomUUID(), setup.studentId(), false));
        var call = new com.fitnesscoaching.platform.modules.ai.application.service.AgentToolGatewayService.ToolCallInput(
                run.id(), setup.trainerId(), "TRAINER", setup.studentId(), Map.of());
        assertThat(agentGateway.authorizeKnowledgeSearch(call).status()).isEqualTo("OK");
        long permissionVersion = jdbc.queryForObject("SELECT version FROM fitness.data_sharing_permissions WHERE id = ?", Long.class, permission);
        sharing.revoke(setup.studentId(), setup.relationshipId(), permission, permissionVersion, "Consent revoked", UUID.randomUUID());
        assertThatThrownBy(() -> conversations.getConversation(setup.trainerId(), conversation.conversationId()))
                .isInstanceOf(AiRunAccessDeniedException.class);
        assertThat(conversations.listConversations(setup.trainerId())).noneMatch(c -> c.conversationId().equals(conversation.conversationId()));
        assertThatThrownBy(() -> agentGateway.authorizeKnowledgeSearch(call)).isInstanceOf(AiRunAccessDeniedException.class);
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
    @Test
    @DisplayName("VOLUME_ADJUSTMENT sets+reps applies full before/after values, increments version and prevents duplicate adjustments on replay")
    void volumeAdjustment_occurrenceScope_withBothSetsAndReps_appliesBothAdjustmentsToDatabase() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-vol-gen-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.VOLUME_ADJUSTMENT,
                "Deload reduction in sets and reps",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);
        UUID recId = genRes.recommendationId();

        // Multi-action payload with sets and reps
        String multiActionPayload = String.format(java.util.Locale.ROOT, """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Deload adjustment",
          "reasoning": ["fatigue"],
          "usedData": ["rpe"],
          "missingData": [],
          "limitations": [],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "VOLUME_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetSets": 3, "targetRepsMin": 8, "targetRepsMax": 12 },
              "after": { "targetSets": 2, "targetRepsMin": 6, "targetRepsMax": 10, "deltaSets": -1, "deltaRepsMin": -2, "deltaRepsMax": -2 },
              "reason": "Deload reduction in sets and reps",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """, UUID.randomUUID(), occurrenceId, rxId);

        jdbc.update("UPDATE fitness.ai_recommendations SET structured_payload = ?::jsonb WHERE id = ?", multiActionPayload, recId);

        String acceptCommandKey = "cmd-f03-vol-acc-" + UUID.randomUUID();
        var acceptReq = new AcceptAdjustmentApiRequest(acceptCommandKey, "Accept both sets and reps", 1, 1L);
        AcceptAdjustmentApiResponse acceptRes = adjustmentService.acceptAdjustment(student, recId, acceptReq);
        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(acceptRes.replayed()).isFalse();

        // 1. Verify two separate adjustments recorded with exact values
        Integer adjCount = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Integer.class, occurrenceId);
        assertThat(adjCount).isEqualTo(2);

        List<Map<String, Object>> adjustments = jdbc.queryForList(
                "SELECT adjustment_type, before_value::text, after_value::text FROM fitness.workout_session_adjustments WHERE planned_workout_id = ? ORDER BY created_at ASC",
                occurrenceId
        );
        assertThat(adjustments).hasSize(2);

        // First adjustment: SETS
        assertThat(adjustments.get(0).get("adjustment_type")).isEqualTo("SETS");
        assertThat((String) adjustments.get(0).get("before_value")).contains("\"targetSets\": 3");
        assertThat((String) adjustments.get(0).get("after_value")).contains("\"targetSets\": 2");

        // Second adjustment: REPS
        assertThat(adjustments.get(1).get("adjustment_type")).isEqualTo("REPS");
        assertThat((String) adjustments.get(1).get("before_value")).contains("\"targetRepsMin\": 8");
        assertThat((String) adjustments.get(1).get("before_value")).contains("\"targetRepsMax\": 12");
        assertThat((String) adjustments.get(1).get("after_value")).contains("\"targetRepsMin\": 6");
        assertThat((String) adjustments.get(1).get("after_value")).contains("\"targetRepsMax\": 10");

        // 2. Planned workout version incremented sequentially from 1 to 3
        Integer finalOccVer = jdbc.queryForObject("SELECT version FROM fitness.planned_workouts WHERE id = ?", Integer.class, occurrenceId);
        assertThat(finalOccVer).isEqualTo(3);

        // 3. Recommendation status is APPLIED
        AiRecommendation rec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        // 4. Exactly 1 application receipt
        Integer appCount = jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?", Integer.class, recId);
        assertThat(appCount).isEqualTo(1);

        // 5. Idempotent Replay: verify replay does NOT create duplicate adjustments or receipts
        AcceptAdjustmentApiResponse replayRes = adjustmentService.acceptAdjustment(student, recId, acceptReq);
        assertThat(replayRes.replayed()).isTrue();
        assertThat(replayRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        Integer adjCountAfterReplay = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Integer.class, occurrenceId);
        assertThat(adjCountAfterReplay).isEqualTo(2);

        Integer appCountAfterReplay = jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?", Integer.class, recId);
        assertThat(appCountAfterReplay).isEqualTo(1);

        Integer occVerAfterReplay = jdbc.queryForObject("SELECT version FROM fitness.planned_workouts WHERE id = ?", Integer.class, occurrenceId);
        assertThat(occVerAfterReplay).isEqualTo(3);
    }

    @Test
    @DisplayName("VOLUME_ADJUSTMENT sets+duration applies full before/after values, increments version and prevents duplicate adjustments on replay")
    void volumeAdjustment_occurrenceScope_withBothSetsAndDuration_appliesBothValuesAndVerifiesReceipt() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-dur-gen-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.VOLUME_ADJUSTMENT,
                "Deload reduction in sets and duration",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);
        UUID recId = genRes.recommendationId();

        // Multi-action payload with sets and duration
        String multiActionPayload = String.format(java.util.Locale.ROOT, """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Duration deload adjustment",
          "reasoning": ["time constraints"],
          "usedData": ["rpe"],
          "missingData": [],
          "limitations": [],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "VOLUME_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetSets": 3, "durationSeconds": 60 },
              "after": { "targetSets": 2, "durationSeconds": 45, "deltaSets": -1, "deltaDurationSeconds": -15 },
              "reason": "Deload reduction in sets and duration",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """, UUID.randomUUID(), occurrenceId, rxId);

        jdbc.update("UPDATE fitness.ai_recommendations SET structured_payload = ?::jsonb WHERE id = ?", multiActionPayload, recId);

        String acceptCommandKey = "cmd-f03-dur-acc-" + UUID.randomUUID();
        var acceptReq = new AcceptAdjustmentApiRequest(acceptCommandKey, "Accept both sets and duration", 1, 1L);
        AcceptAdjustmentApiResponse acceptRes = adjustmentService.acceptAdjustment(student, recId, acceptReq);
        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(acceptRes.replayed()).isFalse();

        // 1. Verify two separate adjustments recorded: SETS and DURATION
        List<Map<String, Object>> adjustments = jdbc.queryForList(
                "SELECT adjustment_type, before_value::text, after_value::text FROM fitness.workout_session_adjustments WHERE planned_workout_id = ? ORDER BY created_at ASC",
                occurrenceId
        );
        assertThat(adjustments).hasSize(2);

        // First adjustment: SETS
        assertThat(adjustments.get(0).get("adjustment_type")).isEqualTo("SETS");
        assertThat((String) adjustments.get(0).get("before_value")).contains("\"targetSets\": 3");
        assertThat((String) adjustments.get(0).get("after_value")).contains("\"targetSets\": 2");

        // Second adjustment: DURATION
        assertThat(adjustments.get(1).get("adjustment_type")).isEqualTo("DURATION");
        assertThat((String) adjustments.get(1).get("before_value")).contains("\"durationSeconds\": 60");
        assertThat((String) adjustments.get(1).get("after_value")).contains("\"durationSeconds\": 45");

        // 2. Planned workout version incremented from 1 to 3
        Integer finalOccVer = jdbc.queryForObject("SELECT version FROM fitness.planned_workouts WHERE id = ?", Integer.class, occurrenceId);
        assertThat(finalOccVer).isEqualTo(3);

        // 3. Idempotent Replay preserves exact state
        AcceptAdjustmentApiResponse replayRes = adjustmentService.acceptAdjustment(student, recId, acceptReq);
        assertThat(replayRes.replayed()).isTrue();

        Integer adjCountAfterReplay = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Integer.class, occurrenceId);
        assertThat(adjCountAfterReplay).isEqualTo(2);
    }

    @Test
    @DisplayName("Rollback when second sub-command fails after first sub-command already executed")
    void volumeAdjustment_whenSecondSubCommandFails_rollsBackFirstCommandAndDatabaseCompletely() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-vol-fail2-gen-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.VOLUME_ADJUSTMENT,
                "Deload reduction in sets and reps",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);
        UUID recId = genRes.recommendationId();

        // Multi-action payload with sets and reps
        String multiActionPayload = String.format(java.util.Locale.ROOT, """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Deload adjustment",
          "reasoning": ["fatigue"],
          "usedData": ["rpe"],
          "missingData": [],
          "limitations": [],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "VOLUME_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetSets": 3, "targetRepsMin": 8, "targetRepsMax": 12 },
              "after": { "targetSets": 2, "targetRepsMin": 6, "targetRepsMax": 10, "deltaSets": -1, "deltaRepsMin": -2, "deltaRepsMax": -2 },
              "reason": "Deload reduction in sets and reps",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """, UUID.randomUUID(), occurrenceId, rxId);

        jdbc.update("UPDATE fitness.ai_recommendations SET structured_payload = ?::jsonb WHERE id = ?", multiActionPayload, recId);

        // Spy workoutPlanCommands: call 1 (SETS) succeeds and writes to DB; call 2 (REPS) throws RuntimeException
        AtomicInteger commandCounter = new AtomicInteger(0);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (commandCounter.incrementAndGet() == 2) {
                throw new RuntimeException("Simulated second sub-command domain failure");
            }
            return invocation.callRealMethod();
        }).when(workoutPlanCommands).adjustOccurrence(any());

        String acceptCommandKey = "cmd-f03-vol-fail2-acc-" + UUID.randomUUID();
        var acceptReq = new AcceptAdjustmentApiRequest(acceptCommandKey, "Accept sets and reps", 1, 1L);

        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, recId, acceptReq))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated second sub-command domain failure");

        // Verify PostgreSQL Database Rollback:
        // 1. Occurrence version rolled back to 1 (not 2 from command 1)
        Integer occVersion = jdbc.queryForObject("SELECT version FROM fitness.planned_workouts WHERE id = ?", Integer.class, occurrenceId);
        assertThat(occVersion).isEqualTo(1);

        // 2. Adjustments inserted by command 1 rolled back completely (count = 0)
        Integer adjCount = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Integer.class, occurrenceId);
        assertThat(adjCount).isEqualTo(0);

        // 3. Domain command receipts from command 1 rolled back completely (count = 0)
        Integer receiptCount = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_plan_command_receipts WHERE actor_id = ? AND command_key LIKE ?", Integer.class, student, acceptCommandKey + "%");
        assertThat(receiptCount).isEqualTo(0);

        // 4. Domain audit from command 1 rolled back completely (count = 0)
        Integer domainAuditCount = jdbc.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'PLANNED_WORKOUT_ADJUSTED'", Integer.class, occurrenceId);
        assertThat(domainAuditCount).isEqualTo(0);

        // 5. Recommendation status remains PENDING
        AiRecommendation rec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);

        // 6. No application receipt persisted
        Integer appCount = jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?", Integer.class, recId);
        assertThat(appCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Rollback specifically when final audit AI_RECOMMENDATION_APPLIED fails, allowing domain audit to run real")
    void acceptAdjustment_whenFinalRecommendationAuditFails_rollsBackDomainChangesAndDomainAudits() {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-audit-fail-gen-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Audit failure test",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);
        UUID recId = genRes.recommendationId();

        // Spy AuditService: Allow domain audits (e.g. PLANNED_WORKOUT_ADJUSTED) to call real method and write to DB!
        // Only throw exception when the final AI_RECOMMENDATION_APPLIED audit is recorded at step 6 of acceptAdjustment.
        org.mockito.Mockito.doAnswer(invocation -> {
            AuditRecord record = invocation.getArgument(0);
            if ("AI_RECOMMENDATION_APPLIED".equals(record.action())) {
                throw new RuntimeException("Simulated audit failure specifically during AI_RECOMMENDATION_APPLIED final audit");
            }
            return invocation.callRealMethod();
        }).when(auditService).recordAudit(any());

        String acceptCommandKey = "cmd-f03-audit-fail-acc-" + UUID.randomUUID();
        var acceptReq = new AcceptAdjustmentApiRequest(acceptCommandKey, "Accept should rollback on final audit", 1, 1L);

        // When accepting, it must throw RuntimeException from the final audit step
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, recId, acceptReq))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated audit failure specifically during AI_RECOMMENDATION_APPLIED final audit");

        // Verify PostgreSQL Database Rollback of both domain mutation AND domain audit:
        // 1. Occurrence version rolled back to 1
        Integer occVersion = jdbc.queryForObject("SELECT version FROM fitness.planned_workouts WHERE id = ?", Integer.class, occurrenceId);
        assertThat(occVersion).isEqualTo(1);

        // 2. Workout session adjustments rolled back
        Integer adjCount = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Integer.class, occurrenceId);
        assertThat(adjCount).isEqualTo(0);

        // 3. Domain command receipts rolled back
        Integer receiptCount = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_plan_command_receipts WHERE actor_id = ? AND command_key LIKE ?", Integer.class, student, acceptCommandKey + "%");
        assertThat(receiptCount).isEqualTo(0);

        // 4. Domain audit (which executed real method) rolled back by database transaction
        Integer domainAuditCount = jdbc.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'PLANNED_WORKOUT_ADJUSTED'", Integer.class, occurrenceId);
        assertThat(domainAuditCount).isEqualTo(0);

        // 5. Final AI recommendation audit was not persisted
        Integer appliedAuditCount = jdbc.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'AI_RECOMMENDATION_APPLIED'", Integer.class, recId);
        assertThat(appliedAuditCount).isEqualTo(0);

        // 6. Recommendation status remains PENDING
        AiRecommendation rec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);

        // 7. No application receipt persisted
        Integer appCount = jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?", Integer.class, recId);
        assertThat(appCount).isEqualTo(0);
    }

    @Test
    @DisplayName("F03: Trainer edits draft, shares with student; student reads snapshot, cannot accept in HUMAN_COACH; subsequent trainer edits isolated")
    void f03DraftLifecycle_trainerEdits_sharesWithStudent_studentReadsSharedSnapshot_concurrencyProtected() throws Exception {
        HumanCoachSetup setup = createHumanCoachSetup();
        UUID student = setup.studentId();
        UUID trainer = setup.trainerId();
        PlanDetail plan = activePlan(student, trainer);
        UUID occurrenceId = materializeOccurrence(student, setup.periodId(), plan);

        UUID rxId = workoutPlanQuery.version(trainer, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var genReq = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-lifecycle-gen-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Trainer requests load decrease proposal",
                List.of(),
                List.of(),
                true
        );

        // 1. Trainer generates adjustment proposal
        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(trainer, genReq);
        UUID recId = genRes.recommendationId();
        assertThat(recId).isNotNull();

        // 2. Student attempts to read unshared draft -> 403 Forbidden
        assertThatThrownBy(() -> adjustmentService.getAdjustment(student, recId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Private trainer draft has not been shared with the student");

        ObjectMapper mapper = new ObjectMapper();

        // 3. Trainer edits draft (V1 -> V2)
        String v2Payload = String.format(java.util.Locale.ROOT, """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Trainer customized load reduction to 70kg",
          "reasoning": ["Customized load reduction"],
          "usedData": ["fatigue"],
          "missingData": [],
          "limitations": [],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "LOAD_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetLoad": 80.0, "loadUnitId": %d },
              "after": { "targetLoad": 70.0, "deltaLoad": -10.0, "loadUnitId": %d, "direction": "DECREASE" },
              "reason": "Trainer customized load adjustment",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """, UUID.randomUUID(), occurrenceId, rxId, kgUnitId(), kgUnitId());

        UpdateAdjustmentDraftApiRequest editReqV2 = new UpdateAdjustmentDraftApiRequest(
                1, // expected revision 1
                "Customized Deload Proposal",
                "Trainer customized load reduction to 70kg",
                mapper.readTree(v2Payload)
        );

        UpdateAdjustmentDraftApiResponse editResV2 = adjustmentService.updateDraft(trainer, recId, editReqV2);
        assertThat(editResV2.revision()).isEqualTo(2);
        assertThat(editResV2.diff().hasChanges()).isTrue();

        // Still unshared with student
        assertThatThrownBy(() -> adjustmentService.getAdjustment(student, recId))
                .isInstanceOf(AiRunAccessDeniedException.class);

        // 4. Trainer shares draft with student
        ShareAdjustmentDraftApiResponse shareRes = adjustmentService.shareDraft(trainer, recId, new ShareAdjustmentDraftApiRequest(2));
        assertThat(shareRes.sharedWithStudent()).isTrue();
        assertThat(shareRes.sharedRevision()).isEqualTo(2);

        // 5. Student can now read the shared draft (gets revision 2 snapshot)
        AiRecommendation studentViewV2 = adjustmentService.getAdjustment(student, recId);
        assertThat(studentViewV2.revision()).isEqualTo(2);
        assertThat(studentViewV2.title()).isEqualTo("Customized Deload Proposal");
        assertThat(studentViewV2.explanation()).isEqualTo("Trainer customized load reduction to 70kg");
        assertThat(studentViewV2.structuredPayload()).contains("70.0");

        // 6. Trainer further edits draft (V2 -> V3) to 65kg
        String v3Payload = String.format(java.util.Locale.ROOT, """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Trainer further reduced load to 65kg",
          "reasoning": ["Further load reduction"],
          "usedData": ["fatigue"],
          "missingData": [],
          "limitations": [],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "LOAD_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetLoad": 80.0, "loadUnitId": %d },
              "after": { "targetLoad": 65.0, "deltaLoad": -15.0, "loadUnitId": %d, "direction": "DECREASE" },
              "reason": "Trainer further reduced load",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """, UUID.randomUUID(), occurrenceId, rxId, kgUnitId(), kgUnitId());

        UpdateAdjustmentDraftApiRequest editReqV3 = new UpdateAdjustmentDraftApiRequest(
                2, // expected revision 2
                "Further Load Reduction",
                "Trainer further reduced load to 65kg",
                mapper.readTree(v3Payload)
        );
        UpdateAdjustmentDraftApiResponse editResV3 = adjustmentService.updateDraft(trainer, recId, editReqV3);
        assertThat(editResV3.revision()).isEqualTo(3);

        // 7. Student reads again: student STILL gets revision 2 (isolation of unshared draft in progress!)
        AiRecommendation studentViewStillV2 = adjustmentService.getAdjustment(student, recId);
        assertThat(studentViewStillV2.revision()).isEqualTo(2);
        assertThat(studentViewStillV2.title()).isEqualTo("Customized Deload Proposal");
        assertThat(studentViewStillV2.explanation()).isEqualTo("Trainer customized load reduction to 70kg");
        assertThat(studentViewStillV2.title()).doesNotContain("Further");
        assertThat(studentViewStillV2.structuredPayload()).contains("70.0");

        // Trainer reads: trainer gets active work-in-progress revision 3
        AiRecommendation trainerViewV3 = adjustmentService.getAdjustment(trainer, recId);
        assertThat(trainerViewV3.revision()).isEqualTo(3);
        assertThat(trainerViewV3.title()).isEqualTo("Further Load Reduction");
        assertThat(trainerViewV3.explanation()).isEqualTo("Trainer further reduced load to 65kg");
        assertThat(trainerViewV3.structuredPayload()).contains("65.0");

        // 8. Student attempts to accept: 403 Forbidden (student has no decision authority in HUMAN_COACH)
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(student, recId, new AcceptAdjustmentApiRequest("key-stud-acc", null, 2, 1L)))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Only the currently assigned coach has decision authority");

        // 9. Trainer accepts revision 3
        String acceptCommandKey = "cmd-f03-trainer-acc-" + UUID.randomUUID();
        AcceptAdjustmentApiResponse acceptRes = adjustmentService.acceptAdjustment(
                trainer,
                recId,
                new AcceptAdjustmentApiRequest(acceptCommandKey, "Trainer approved 65kg load", 3, 1L)
        );
        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(acceptRes.resultingEntityType()).isEqualTo("WORKOUT_OCCURRENCE");

        // 10. Verify DB applied state and occurrence mutation
        AiRecommendation dbRec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(dbRec.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(dbRec.revision()).isEqualTo(3);

        Integer occVersion = jdbc.queryForObject("SELECT version FROM fitness.planned_workouts WHERE id = ?", Integer.class, occurrenceId);
        assertThat(occVersion).isEqualTo(2);

        // 11. Idempotent replay by trainer returns replayed=true
        AcceptAdjustmentApiResponse replayRes = adjustmentService.acceptAdjustment(
                trainer,
                recId,
                new AcceptAdjustmentApiRequest(acceptCommandKey, "Trainer approved 65kg load", 3, 1L)
        );
        assertThat(replayRes.replayed()).isTrue();

        // 12. Subsequent edits on APPLIED proposal throw 409 Conflict
        assertThatThrownBy(() -> adjustmentService.updateDraft(trainer, recId, editReqV3))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Recommendation is in status APPLIED and cannot be edited");
    }

    @Test
    @DisplayName("Concurrency: Two threads attempting to update draft from revision 1 simultaneously result in 1 success and 1 revision conflict")
    void concurrentEditEditRace_onlyOneSucceeds_otherFailsWithConflict() throws Exception {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-ee-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Deload",
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

        ObjectMapper mapper = new ObjectMapper();
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                String payload = String.format(java.util.Locale.ROOT, """
                {
                  "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
                  "summary": "Thread %d edit",
                  "reasoning": ["Thread edit"],
                  "usedData": ["fatigue"],
                  "missingData": [],
                  "limitations": [],
                  "confidence": 0.95,
                  "changes": [
                    {
                      "changeId": "%s",
                      "changeType": "LOAD_ADJUSTMENT",
                      "scope": "ONE_MATERIALIZED_OCCURRENCE",
                      "targetRef": {
                        "scope": "ONE_MATERIALIZED_OCCURRENCE",
                        "plannedWorkoutId": "%s",
                        "plannedSessionExerciseId": "%s"
                      },
                      "before": { "targetLoad": 80.0, "loadUnitId": %d },
                      "after": { "targetLoad": %f, "deltaLoad": %f, "loadUnitId": %d, "direction": "DECREASE" },
                      "reason": "Thread load adjustment",
                      "versionGuards": { "expectedOccurrenceVersion": 1 }
                    }
                  ]
                }
                """, index, UUID.randomUUID(), occurrenceId, rxId, kgUnitId(), 80.0 - (index + 1) * 5.0, -(index + 1) * 5.0, kgUnitId());

                var editReq = new UpdateAdjustmentDraftApiRequest(1, "Title " + index, "Exp " + index, mapper.readTree(payload));
                return adjustmentService.updateDraft(student, recId, editReq);
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int successes = 0;
        int conflicts = 0;

        for (Future<Object> f : futures) {
            try {
                Object res = f.get(10, TimeUnit.SECONDS);
                if (res instanceof UpdateAdjustmentDraftApiResponse) {
                    successes++;
                }
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof AiRunLifecycleConflictException) {
                    conflicts++;
                } else {
                    throw new AssertionError("Unexpected exception in concurrent edit", e);
                }
            }
        }

        executor.shutdown();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        AiRecommendation finalRec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(finalRec.revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("Concurrency: Simultaneous updateDraft and acceptAdjustment race resolves cleanly with 1 success and 1 conflict")
    void concurrentEditAcceptRace_resolvesCleanlyWithoutCorruption() throws Exception {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-ea-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Deload",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);
        UUID recId = genRes.recommendationId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        ObjectMapper mapper = new ObjectMapper();
        Future<Object> editFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            String payload = String.format(java.util.Locale.ROOT, """
            {
              "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
              "summary": "Edit during accept race",
              "reasoning": ["Fatigue"],
              "usedData": ["fatigue"],
              "missingData": [],
              "limitations": [],
              "confidence": 0.95,
              "changes": [
                {
                  "changeId": "%s",
                  "changeType": "LOAD_ADJUSTMENT",
                  "scope": "ONE_MATERIALIZED_OCCURRENCE",
                  "targetRef": {
                    "scope": "ONE_MATERIALIZED_OCCURRENCE",
                    "plannedWorkoutId": "%s",
                    "plannedSessionExerciseId": "%s"
                  },
                  "before": { "targetLoad": 80.0, "loadUnitId": %d },
                  "after": { "targetLoad": 75.0, "deltaLoad": -5.0, "loadUnitId": %d, "direction": "DECREASE" },
                  "reason": "Fatigue edit",
                  "versionGuards": { "expectedOccurrenceVersion": 1 }
                }
              ]
            }
            """, UUID.randomUUID(), occurrenceId, rxId, kgUnitId(), kgUnitId());
            return adjustmentService.updateDraft(student, recId, new UpdateAdjustmentDraftApiRequest(1, "Edit Title", "Edit Exp", mapper.readTree(payload)));
        });

        Future<Object> acceptFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return adjustmentService.acceptAdjustment(student, recId, new AcceptAdjustmentApiRequest("cmd-acc-race-" + UUID.randomUUID(), "Accept note", 1, 1L));
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        boolean editSucceeded = false;
        boolean acceptSucceeded = false;
        int conflicts = 0;

        try {
            editFuture.get(10, TimeUnit.SECONDS);
            editSucceeded = true;
        } catch (Exception e) {
            if (e.getCause() instanceof AiRunLifecycleConflictException) conflicts++;
        }

        try {
            acceptFuture.get(10, TimeUnit.SECONDS);
            acceptSucceeded = true;
        } catch (Exception e) {
            if (e.getCause() instanceof AiRunLifecycleConflictException) conflicts++;
        }

        executor.shutdown();
        assertThat(editSucceeded ^ acceptSucceeded).isTrue();
        assertThat(conflicts).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrency: Simultaneous updateDraft and shareDraft race resolves cleanly without data corruption")
    void concurrentEditShareRace_resolvesCleanlyWithoutCorruption() throws Exception {
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
                "cmd-f03-es-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Deload",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(trainer, req);
        UUID recId = genRes.recommendationId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        ObjectMapper mapper = new ObjectMapper();
        Future<Object> editFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            String payload = String.format(java.util.Locale.ROOT, """
            {
              "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
              "summary": "Edit during share race",
              "reasoning": ["Fatigue"],
              "usedData": ["fatigue"],
              "missingData": [],
              "limitations": [],
              "confidence": 0.95,
              "changes": [
                {
                  "changeId": "%s",
                  "changeType": "LOAD_ADJUSTMENT",
                  "scope": "ONE_MATERIALIZED_OCCURRENCE",
                  "targetRef": {
                    "scope": "ONE_MATERIALIZED_OCCURRENCE",
                    "plannedWorkoutId": "%s",
                    "plannedSessionExerciseId": "%s"
                  },
                  "before": { "targetLoad": 80.0, "loadUnitId": %d },
                  "after": { "targetLoad": 70.0, "deltaLoad": -10.0, "loadUnitId": %d, "direction": "DECREASE" },
                  "reason": "Fatigue edit",
                  "versionGuards": { "expectedOccurrenceVersion": 1 }
                }
              ]
            }
            """, UUID.randomUUID(), occurrenceId, rxId, kgUnitId(), kgUnitId());
            return adjustmentService.updateDraft(trainer, recId, new UpdateAdjustmentDraftApiRequest(1, "Trainer Edit", "Exp", mapper.readTree(payload)));
        });

        Future<Object> shareFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return adjustmentService.shareDraft(trainer, recId, new ShareAdjustmentDraftApiRequest(1));
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int successes = 0;
        int conflicts = 0;

        try {
            editFuture.get(10, TimeUnit.SECONDS);
            successes++;
        } catch (Exception e) {
            if (e.getCause() instanceof AiRunLifecycleConflictException) conflicts++;
        }

        try {
            shareFuture.get(10, TimeUnit.SECONDS);
            successes++;
        } catch (Exception e) {
            if (e.getCause() instanceof AiRunLifecycleConflictException) conflicts++;
        }

        executor.shutdown();
        assertThat(successes + conflicts).isEqualTo(2);
        AiRecommendation rec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(rec.revision()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Rollback: updateDraft rolls back completely when audit fails")
    void updateDraft_whenAuditFails_rollsBackEntireDraftUpdate() throws Exception {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);
        PlanDetail plan = activePlan(student, student);
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var req = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-audit-edit-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Deload",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, req);
        UUID recId = genRes.recommendationId();

        org.mockito.Mockito.doAnswer(invocation -> {
            AuditRecord record = invocation.getArgument(0);
            if ("AI_RECOMMENDATION_DRAFT_UPDATED".equals(record.action())) {
                throw new RuntimeException("Simulated audit failure during draft update");
            }
            return invocation.callRealMethod();
        }).when(auditService).recordAudit(any());

        ObjectMapper mapper = new ObjectMapper();
        String payload = String.format(java.util.Locale.ROOT, """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Custom load",
          "reasoning": ["Fatigue"],
          "usedData": ["fatigue"],
          "missingData": [],
          "limitations": [],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "LOAD_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetLoad": 80.0, "loadUnitId": %d },
              "after": { "targetLoad": 70.0, "deltaLoad": -10.0, "loadUnitId": %d, "direction": "DECREASE" },
              "reason": "Fatigue edit",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """, UUID.randomUUID(), occurrenceId, rxId, kgUnitId(), kgUnitId());

        var editReq = new UpdateAdjustmentDraftApiRequest(1, "Audit Fail Title", "Audit Fail Exp", mapper.readTree(payload));

        assertThatThrownBy(() -> adjustmentService.updateDraft(student, recId, editReq))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated audit failure during draft update");

        // Verify PostgreSQL Database Rollback:
        AiRecommendation rec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(rec.revision()).isEqualTo(1);
        assertThat(rec.title()).doesNotContain("Audit Fail");
        assertThat(rec.isCustomized()).isFalse();
    }

    @Test
    @DisplayName("Rollback: shareDraft rolls back completely when audit fails")
    void shareDraft_whenAuditFails_rollsBackEntireSharingSnapshot() {
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
                "cmd-f03-audit-share-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Deload",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(trainer, req);
        UUID recId = genRes.recommendationId();

        org.mockito.Mockito.doAnswer(invocation -> {
            AuditRecord record = invocation.getArgument(0);
            if ("AI_RECOMMENDATION_SHARED".equals(record.action())) {
                throw new RuntimeException("Simulated audit failure during share draft");
            }
            return invocation.callRealMethod();
        }).when(auditService).recordAudit(any());

        assertThatThrownBy(() -> adjustmentService.shareDraft(trainer, recId, new ShareAdjustmentDraftApiRequest(1)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated audit failure during share draft");

        // Verify PostgreSQL Database Rollback:
        AiRecommendation rec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(rec.sharedWithStudent()).isFalse();
        assertThat(rec.sharedRevision()).isNull();
        assertThat(rec.sharedTitle()).isNull();
        assertThat(rec.sharedExplanation()).isNull();
    }

    @Test
    @DisplayName("Concurrency: Simultaneous updateDraft and rejectAdjustment race resolves cleanly without data corruption")
    void concurrentEditRejectRace_resolvesCleanlyWithoutCorruption() throws Exception {
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
                "cmd-f03-er-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Deload",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(trainer, req);
        UUID recId = genRes.recommendationId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        ObjectMapper mapper = new ObjectMapper();
        Future<Object> editFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            String payload = String.format(java.util.Locale.ROOT, """
            {
              "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
              "summary": "Edit during reject race",
              "reasoning": ["Fatigue"],
              "usedData": ["fatigue"],
              "missingData": [],
              "limitations": [],
              "confidence": 0.95,
              "changes": [
                {
                  "changeId": "%s",
                  "changeType": "LOAD_ADJUSTMENT",
                  "scope": "ONE_MATERIALIZED_OCCURRENCE",
                  "targetRef": {
                    "scope": "ONE_MATERIALIZED_OCCURRENCE",
                    "plannedWorkoutId": "%s",
                    "plannedSessionExerciseId": "%s"
                  },
                  "before": { "targetLoad": 80.0, "loadUnitId": %d },
                  "after": { "targetLoad": 70.0, "deltaLoad": -10.0, "loadUnitId": %d, "direction": "DECREASE" },
                  "reason": "Fatigue edit",
                  "versionGuards": { "expectedOccurrenceVersion": 1 }
                }
              ]
            }
            """, UUID.randomUUID(), occurrenceId, rxId, kgUnitId(), kgUnitId());
            return adjustmentService.updateDraft(trainer, recId, new UpdateAdjustmentDraftApiRequest(1, "Edit Race Title", "Exp", mapper.readTree(payload)));
        });

        Future<Object> rejectFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return adjustmentService.rejectAdjustment(trainer, recId, new RejectAdjustmentApiRequest("Trainer decided to reject"));
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int successes = 0;
        int conflicts = 0;

        try {
            editFuture.get(10, TimeUnit.SECONDS);
            successes++;
        } catch (Exception e) {
            if (e.getCause() instanceof AiRunLifecycleConflictException) conflicts++;
        }

        try {
            rejectFuture.get(10, TimeUnit.SECONDS);
            successes++;
        } catch (Exception e) {
            if (e.getCause() instanceof AiRunLifecycleConflictException) conflicts++;
        }

        executor.shutdown();
        assertThat(successes + conflicts).isEqualTo(2);

        AiRecommendation rec = recommendationRepository.findById(recId).orElseThrow();
        if (rec.status() == AiRecommendationStatus.REJECTED) {
            assertThat(rec.decidedBy()).isEqualTo(trainer);
        } else {
            assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);
            assertThat(rec.revision()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("Concurrency: Controlled order 1 (share wins first) -> Student keeps frozen shared snapshot while Trainer edits v2")
    void concurrentEditShareRace_order1_shareWinsFirst_studentKeepsFrozenSnapshot() throws Exception {
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
                "cmd-f03-order1-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Deload",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(trainer, req);
        UUID recId = genRes.recommendationId();

        // 1. Share draft at revision 1
        ShareAdjustmentDraftApiResponse shareRes = adjustmentService.shareDraft(trainer, recId, new ShareAdjustmentDraftApiRequest(1));
        assertThat(shareRes.sharedWithStudent()).isTrue();
        assertThat(shareRes.sharedRevision()).isEqualTo(1);

        // 2. Trainer subsequently modifies draft to revision 2 with distinct private content
        ObjectMapper mapper = new ObjectMapper();
        String payloadV2 = String.format(java.util.Locale.ROOT, """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Trainer Private Edit v2",
          "reasoning": ["Secret adjustment"],
          "usedData": ["fatigue"],
          "missingData": [],
          "limitations": [],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "LOAD_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetLoad": 80.0, "loadUnitId": %d },
              "after": { "targetLoad": 65.0, "deltaLoad": -15.0, "loadUnitId": %d, "direction": "DECREASE" },
              "reason": "Secret load cut",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """, UUID.randomUUID(), occurrenceId, rxId, kgUnitId(), kgUnitId());

        UpdateAdjustmentDraftApiResponse editRes = adjustmentService.updateDraft(
                trainer, recId, new UpdateAdjustmentDraftApiRequest(1, "Private Trainer Title v2", "Private Explanation v2", mapper.readTree(payloadV2))
        );
        assertThat(editRes.revision()).isEqualTo(2);

        // 3. Verify Database State
        AiRecommendation liveRec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(liveRec.revision()).isEqualTo(2);
        assertThat(liveRec.title()).isEqualTo("Private Trainer Title v2");
        assertThat(liveRec.sharedRevision()).isEqualTo(1);

        // 4. Verify Student View: completely isolated, sees frozen snapshot (revision 1, NOT 2)
        AiRecommendation studentView = adjustmentService.getAdjustment(student, recId);
        assertThat(studentView.revision()).isEqualTo(1);
        assertThat(studentView.title()).doesNotContain("Private Trainer Title");
        assertThat(studentView.explanation()).doesNotContain("Private Explanation");
        assertThat(studentView.structuredPayload()).doesNotContain("Trainer Private Edit v2");
        assertThat(studentView.sharedRevision()).isEqualTo(1);
        assertThat(studentView.sharedWithStudent()).isTrue();
    }

    @Test
    @DisplayName("Concurrency: Controlled order 2 (edit wins first) -> share with old revision conflicts (409)")
    void concurrentEditShareRace_order2_editWinsFirst_shareWithOldRevisionConflicts() throws Exception {
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
                "cmd-f03-order2-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Deload",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(trainer, req);
        UUID recId = genRes.recommendationId();

        // 1. Edit wins first: increments revision to 2
        ObjectMapper mapper = new ObjectMapper();
        String payloadV2 = String.format(java.util.Locale.ROOT, """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Trainer Early Edit v2",
          "reasoning": ["Early edit"],
          "usedData": ["fatigue"],
          "missingData": [],
          "limitations": [],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "LOAD_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetLoad": 80.0, "loadUnitId": %d },
              "after": { "targetLoad": 75.0, "deltaLoad": -5.0, "loadUnitId": %d, "direction": "DECREASE" },
              "reason": "Early adjust",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """, UUID.randomUUID(), occurrenceId, rxId, kgUnitId(), kgUnitId());

        adjustmentService.updateDraft(trainer, recId, new UpdateAdjustmentDraftApiRequest(1, "Title v2", "Exp v2", mapper.readTree(payloadV2)));

        // 2. Share request submitted with stale expectedRevision = 1 MUST conflict
        assertThatThrownBy(() -> adjustmentService.shareDraft(trainer, recId, new ShareAdjustmentDraftApiRequest(1)))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Revision conflict: expected revision 1 but current is 2");

        // 3. Verify Draft remains unshared in database and inaccessible to student
        AiRecommendation rec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(rec.sharedWithStudent()).isFalse();
        assertThat(rec.sharedRevision()).isNull();

        assertThatThrownBy(() -> adjustmentService.getAdjustment(student, recId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Private trainer draft has not been shared with the student.");
    }

    @Test
    @DisplayName("End-to-end Accept of Load Adjustment: enforces code-ID consistency, rejects mismatch, and persists valid matching LB unit with domain readback")
    void acceptLoadAdjustment_withAuthoritativeLoadUnit_persistsCorrectly() throws Exception {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);

        // Query authoritative LB and KG unit ids from DB
        Integer lbUnitId = jdbc.queryForObject("SELECT id FROM fitness.measurement_units WHERE code = 'LB'", Integer.class);
        Integer kgUnitId = jdbc.queryForObject("SELECT id FROM fitness.measurement_units WHERE code = 'KG'", Integer.class);
        assertThat(lbUnitId).isNotNull();
        assertThat(kgUnitId).isNotNull();
        assertThat(lbUnitId).isNotEqualTo(kgUnitId);

        PlanDetail plan = activePlanWithLoad(student, student, kgUnitId, BigDecimal.valueOf(80.0));
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var genReq = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-unit-gen-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Testing unit fidelity",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, genReq);
        UUID recId = genRes.recommendationId();
        ObjectMapper mapper = new ObjectMapper();

        // 1. Case: Conflicting loadUnit 'KG' with loadUnitId of LB -> must be rejected
        String payloadConflictKgWithLb = """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Conflict KG code with LB ID",
          "reasoning": ["Conflict test"],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "LOAD_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetLoad": 80.0, "loadUnit": "KG", "loadUnitId": %d },
              "after": { "targetLoad": 75.0, "deltaLoad": -5.0, "loadUnit": "KG", "loadUnitId": %d, "direction": "DECREASE" },
              "reason": "Mismatched unit id",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """.formatted(UUID.randomUUID(), occurrenceId, rxId, kgUnitId, lbUnitId);

        assertThatThrownBy(() -> adjustmentService.updateDraft(student, recId,
                new UpdateAdjustmentDraftApiRequest(1, "Conflict KG-LB Draft", "Exp", mapper.readTree(payloadConflictKgWithLb))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Conflicting after loadUnit code and ID: code 'KG' does not match unit ID " + lbUnitId + " ('LB')");

        // Verify no mutation written to DB and status remains PENDING
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Integer.class, occurrenceId)).isZero();
        assertThat(recommendationRepository.findById(recId).orElseThrow().status()).isEqualTo(AiRecommendationStatus.PENDING);

        // 2. Case: Conflicting loadUnit 'LB' with loadUnitId of KG -> must be rejected
        String payloadConflictLbWithKg = """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Conflict LB code with KG ID",
          "reasoning": ["Conflict test 2"],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "LOAD_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetLoad": 80.0, "loadUnit": "KG", "loadUnitId": %d },
              "after": { "targetLoad": 180.0, "deltaLoad": 3.63, "loadUnit": "LB", "loadUnitId": %d, "direction": "INCREASE" },
              "reason": "Mismatched unit id reverse",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """.formatted(UUID.randomUUID(), occurrenceId, rxId, kgUnitId, kgUnitId);

        assertThatThrownBy(() -> adjustmentService.updateDraft(student, recId,
                new UpdateAdjustmentDraftApiRequest(1, "Conflict LB-KG Draft", "Exp", mapper.readTree(payloadConflictLbWithKg))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Conflicting after loadUnit code and ID: code 'LB' does not match unit ID " + kgUnitId + " ('KG')");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Integer.class, occurrenceId)).isZero();
        assertThat(recommendationRepository.findById(recId).orElseThrow().status()).isEqualTo(AiRecommendationStatus.PENDING);

        // 3. Case: Valid matching loadUnit 'LB' with loadUnitId of LB (80 KG = 176.37 LB, target = 170.0 LB, delta = -6.37, direction DECREASE)
        String payloadValidLb = """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Switch to LB and adjust",
          "reasoning": ["Unit customization test"],
          "usedData": [],
          "limitations": [],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "LOAD_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetLoad": 80.0, "loadUnit": "KG", "loadUnitId": %d },
              "after": { "targetLoad": 170.0, "deltaLoad": -6.37, "loadUnit": "LB", "loadUnitId": %d, "direction": "DECREASE" },
              "reason": "Test authoritative loadUnitId for LB",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """.formatted(UUID.randomUUID(), occurrenceId, rxId, kgUnitId, lbUnitId);

        UpdateAdjustmentDraftApiResponse updateRes = adjustmentService.updateDraft(
                student, recId, new UpdateAdjustmentDraftApiRequest(1, "Updated Draft Title", "Updated Exp", mapper.readTree(payloadValidLb))
        );
        assertThat(updateRes.revision()).isEqualTo(2);

        // Accept revision 2
        var acceptReq = new AcceptAdjustmentApiRequest("cmd-accept-unit-" + UUID.randomUUID(), "Accepted with custom unit", 2, 1L);
        AcceptAdjustmentApiResponse acceptRes = adjustmentService.acceptAdjustment(student, recId, acceptReq);
        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        // Verify stored in fitness.workout_session_adjustments JSON and typed columns
        String afterJson = jdbc.queryForObject("SELECT after_value::text FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", String.class, occurrenceId);
        assertThat(afterJson).isNotNull();
        tools.jackson.databind.JsonNode storedNode = mapper.readTree(afterJson);
        assertThat(storedNode.path("loadUnitId").intValue()).isEqualTo(lbUnitId);
        assertThat(storedNode.path("targetLoad").decimalValue()).isEqualByComparingTo(new BigDecimal("170.0"));

        Short storedTypedUnitId = jdbc.queryForObject("SELECT typed_load_unit_id FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Short.class, occurrenceId);
        assertThat(storedTypedUnitId).isNotNull();
        assertThat(storedTypedUnitId.intValue()).isEqualTo(lbUnitId);

        BigDecimal storedTypedLoad = jdbc.queryForObject("SELECT typed_target_load FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", BigDecimal.class, occurrenceId);
        assertThat(storedTypedLoad).isNotNull();
        assertThat(storedTypedLoad).isEqualByComparingTo(new BigDecimal("170.0"));

        // Domain readback via execution snapshot port
        var domainAdjustments = executionSnapshotPort.adjustments(occurrenceId);
        assertThat(domainAdjustments).hasSize(1);
        assertThat(domainAdjustments.get(0).loadUnitId()).isNotNull();
        assertThat(domainAdjustments.get(0).loadUnitId().intValue()).isEqualTo(lbUnitId);
        assertThat(domainAdjustments.get(0).targetLoad()).isEqualByComparingTo(new BigDecimal("170.0"));
    }

    @Test
    @DisplayName("End-to-end Accept of Load Adjustment reverse: baseline in LB, updated to KG with authoritative kgUnitId, verified via DB and domain readback")
    void acceptLoadAdjustment_reverseBaselineLbToKg_persistsCorrectly() throws Exception {
        UUID student = person("STUDENT");
        UUID periodId = createSelfDirectedPeriod(student);

        Integer lbUnitId = jdbc.queryForObject("SELECT id FROM fitness.measurement_units WHERE code = 'LB'", Integer.class);
        Integer kgUnitId = jdbc.queryForObject("SELECT id FROM fitness.measurement_units WHERE code = 'KG'", Integer.class);
        assertThat(lbUnitId).isNotNull();
        assertThat(kgUnitId).isNotNull();

        PlanDetail plan = activePlanWithLoad(student, student, lbUnitId, BigDecimal.valueOf(100.0));
        UUID occurrenceId = materializeOccurrence(student, periodId, plan);

        UUID rxId = workoutPlanQuery.version(student, plan.plan().id(), plan.currentVersion().id())
                .sessions().get(0).prescriptions().get(0).id();

        var targetRef = new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, occurrenceId, rxId, null, null, null, null);
        var genReq = new WorkoutAdjustmentApiRequest(
                student,
                "cmd-f03-rev-gen-" + UUID.randomUUID(),
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                targetRef,
                ChangeType.LOAD_ADJUSTMENT,
                "Testing reverse unit fidelity",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse genRes = adjustmentService.generateAdjustment(student, genReq);
        UUID recId = genRes.recommendationId();
        ObjectMapper mapper = new ObjectMapper();

        // 1. Conflict test: loadUnit 'KG' with loadUnitId of LB -> rejected
        String payloadConflictKgWithLb = """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Conflict KG with LB id in reverse test",
          "reasoning": ["Conflict test"],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "LOAD_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetLoad": 100.0, "loadUnit": "LB", "loadUnitId": %d },
              "after": { "targetLoad": 40.0, "deltaLoad": -5.36, "loadUnit": "KG", "loadUnitId": %d, "direction": "DECREASE" },
              "reason": "Mismatched unit id in reverse",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """.formatted(UUID.randomUUID(), occurrenceId, rxId, lbUnitId, lbUnitId);

        assertThatThrownBy(() -> adjustmentService.updateDraft(student, recId,
                new UpdateAdjustmentDraftApiRequest(1, "Conflict Reverse Draft", "Exp", mapper.readTree(payloadConflictKgWithLb))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Conflicting after loadUnit code and ID: code 'KG' does not match unit ID " + lbUnitId + " ('LB')");

        // 2. Valid update: baseline 100.0 LB (45.36 KG) -> target 40.0 KG, delta = -5.36 KG, direction: DECREASE, unitId: kgUnitId
        String payloadValidKg = """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Switch from LB to KG and adjust",
          "reasoning": ["Valid reverse unit update"],
          "confidence": 0.95,
          "changes": [
            {
              "changeId": "%s",
              "changeType": "LOAD_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetLoad": 100.0, "loadUnit": "LB", "loadUnitId": %d },
              "after": { "targetLoad": 40.0, "deltaLoad": -5.36, "loadUnit": "KG", "loadUnitId": %d, "direction": "DECREASE" },
              "reason": "Test authoritative loadUnitId for KG",
              "versionGuards": { "expectedOccurrenceVersion": 1 }
            }
          ]
        }
        """.formatted(UUID.randomUUID(), occurrenceId, rxId, lbUnitId, kgUnitId);

        UpdateAdjustmentDraftApiResponse updateRes = adjustmentService.updateDraft(
                student, recId, new UpdateAdjustmentDraftApiRequest(1, "Draft in KG", "Exp", mapper.readTree(payloadValidKg))
        );
        assertThat(updateRes.revision()).isEqualTo(2);

        // Accept revision 2
        var acceptReq = new AcceptAdjustmentApiRequest("cmd-accept-rev-unit-" + UUID.randomUUID(), "Accepted with KG unit", 2, 1L);
        AcceptAdjustmentApiResponse acceptRes = adjustmentService.acceptAdjustment(student, recId, acceptReq);
        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        // Verify stored in fitness.workout_session_adjustments JSON and typed columns
        String afterJson = jdbc.queryForObject("SELECT after_value::text FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", String.class, occurrenceId);
        assertThat(afterJson).isNotNull();
        tools.jackson.databind.JsonNode storedNode = mapper.readTree(afterJson);
        assertThat(storedNode.path("loadUnitId").intValue()).isEqualTo(kgUnitId);
        assertThat(storedNode.path("targetLoad").decimalValue()).isEqualByComparingTo(new BigDecimal("40.0"));

        Short storedTypedUnitId = jdbc.queryForObject("SELECT typed_load_unit_id FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", Short.class, occurrenceId);
        assertThat(storedTypedUnitId).isNotNull();
        assertThat(storedTypedUnitId.intValue()).isEqualTo(kgUnitId);

        BigDecimal storedTypedLoad = jdbc.queryForObject("SELECT typed_target_load FROM fitness.workout_session_adjustments WHERE planned_workout_id = ?", BigDecimal.class, occurrenceId);
        assertThat(storedTypedLoad).isNotNull();
        assertThat(storedTypedLoad).isEqualByComparingTo(new BigDecimal("40.0"));

        // Domain readback via execution snapshot port
        var domainAdjustments = executionSnapshotPort.adjustments(occurrenceId);
        assertThat(domainAdjustments).hasSize(1);
        assertThat(domainAdjustments.get(0).loadUnitId()).isNotNull();
        assertThat(domainAdjustments.get(0).loadUnitId().intValue()).isEqualTo(kgUnitId);
        assertThat(domainAdjustments.get(0).targetLoad()).isEqualByComparingTo(new BigDecimal("40.0"));
    }
}
