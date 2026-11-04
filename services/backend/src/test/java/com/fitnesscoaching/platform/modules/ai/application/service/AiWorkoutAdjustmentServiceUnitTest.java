package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.ChangeType;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetRef;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F03AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationApplication;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.F03SourceSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.CurrentCoachingContext;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.Mode;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanSummary;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PrescriptionView;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.SessionView;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.VersionDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.VersionSummary;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.domain.DecisionOwnerType;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionAdjustment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiWorkoutAdjustmentServiceUnitTest {

    @Mock
    private AiRunRepository aiRunRepository;
    @Mock
    private AiRecommendationRepository recommendationRepository;
    @Mock
    private F03AiServiceClient aiServiceClient;
    @Mock
    private F03ContextBuilder contextBuilder;
    @Mock
    private F03RuntimeOutputValidator outputValidator;
    @Mock
    private AuditService auditService;
    @Mock
    private CurrentCoachingContextQuery coachingContextQuery;
    @Mock
    private UserAccountStatusQuery accountStatusQuery;
    @Mock
    private WorkoutPlanCommandUseCase workoutPlanCommandUseCase;
    @Mock
    private WorkoutPlanQueryUseCase workoutPlanQueryUseCase;
    @Mock
    private ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiWorkoutAdjustmentService adjustmentService;

    private static final UUID STUDENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRAINER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OCCURRENCE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PRESCRIPTION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PERIOD_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID PLAN_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID VERSION_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");

    @BeforeEach
    void setUp() {
        adjustmentService = new AiWorkoutAdjustmentService(
                aiRunRepository,
                recommendationRepository,
                aiServiceClient,
                contextBuilder,
                outputValidator,
                auditService,
                objectMapper,
                coachingContextQuery,
                accountStatusQuery,
                workoutPlanCommandUseCase,
                workoutPlanQueryUseCase,
                scheduleCalendarProvider
        );
    }

    private F03SourceSnapshot buildSnapshot(String mode, UUID trainerId) {
        return new F03SourceSnapshot(
                STUDENT_ID,
                PERIOD_ID,
                mode,
                trainerId,
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                PLAN_ID,
                1L,
                1,
                OCCURRENCE_ID,
                2L,
                PRESCRIPTION_ID,
                null,
                UUID.randomUUID(),
                BigDecimal.valueOf(20.0),
                1,
                3,
                8,
                12,
                null,
                true,
                TrainingContinuityState.RECENT_CONSISTENT,
                Instant.now()
        );
    }

    private F03SourceSnapshot buildTemplateSnapshot(String mode, UUID trainerId) {
        return new F03SourceSnapshot(
                STUDENT_ID,
                PERIOD_ID,
                mode,
                trainerId,
                TargetScope.TEMPLATE_OR_FUTURE_OCCURRENCES,
                PLAN_ID,
                1L,
                1,
                null,
                null,
                null,
                PRESCRIPTION_ID,
                UUID.randomUUID(),
                BigDecimal.valueOf(20.0),
                1,
                3,
                8,
                12,
                null,
                true,
                TrainingContinuityState.RECENT_CONSISTENT,
                Instant.now()
        );
    }

    @Test
    @DisplayName("Successful adjustment flow creates SUCCEEDED run and PENDING recommendation")
    void generateAdjustment_successfulFlow_createsRunAndRecommendation() {
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F03SourceSnapshot snapshot = buildSnapshot("SELF_DIRECTED", null);
        var envelopeResult = new F03ContextBuilder.ContextEnvelopeResult(null, snapshot);
        when(contextBuilder.buildContextEnvelope(eq(STUDENT_ID), eq(STUDENT_ID), any())).thenReturn(envelopeResult);

        String rawJson = "{\"recommendationType\":\"WORKOUT_ADJUSTMENT_PROPOSAL\",\"summary\":\"Load adjusted\"}";
        when(aiServiceClient.generateAdjustmentJson(any())).thenReturn(rawJson);
        when(outputValidator.validate(rawJson, snapshot)).thenReturn(F03RuntimeOutputValidator.ValidationResult.valid());

        when(recommendationRepository.save(any(AiRecommendation.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new WorkoutAdjustmentApiRequest(
                STUDENT_ID,
                "cmd-1",
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, OCCURRENCE_ID, PRESCRIPTION_ID, null, null, null, null),
                ChangeType.LOAD_ADJUSTMENT,
                "reason",
                List.of(),
                List.of(),
                true
        );

        WorkoutAdjustmentApiResponse response = adjustmentService.generateAdjustment(STUDENT_ID, req);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(response.decisionAuthority()).isEqualTo(AiDecisionAuthority.STUDENT);

        verify(aiRunRepository).create(any(AiRun.class));
        verify(aiRunRepository).completeRun(any(), eq(AiRunStatus.SUCCEEDED), eq(rawJson), any(), any(), any(), any(), any(Integer.class), any());
        verify(recommendationRepository).save(any(AiRecommendation.class));
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("Validator failure marks AI run REJECTED_BY_VALIDATOR and throws exception")
    void generateAdjustment_validatorFailure_marksRunRejected() {
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F03SourceSnapshot snapshot = buildSnapshot("SELF_DIRECTED", null);
        var envelopeResult = new F03ContextBuilder.ContextEnvelopeResult(null, snapshot);
        when(contextBuilder.buildContextEnvelope(eq(STUDENT_ID), eq(STUDENT_ID), any())).thenReturn(envelopeResult);

        String rawJson = "{\"recommendationType\":\"WORKOUT_ADJUSTMENT_PROPOSAL\"}";
        when(aiServiceClient.generateAdjustmentJson(any())).thenReturn(rawJson);
        when(outputValidator.validate(rawJson, snapshot))
                .thenReturn(F03RuntimeOutputValidator.ValidationResult.invalid("LOAD_INCREASE_NOT_PERMITTED"));

        var req = new WorkoutAdjustmentApiRequest(
                STUDENT_ID,
                "cmd-1",
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, OCCURRENCE_ID, PRESCRIPTION_ID, null, null, null, null),
                ChangeType.LOAD_ADJUSTMENT,
                "reason",
                List.of(),
                List.of(),
                false
        );

        assertThatThrownBy(() -> adjustmentService.generateAdjustment(STUDENT_ID, req))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("LOAD_INCREASE_NOT_PERMITTED");

        verify(aiRunRepository).completeRun(any(), eq(AiRunStatus.REJECTED_BY_VALIDATOR), any(), eq("VALIDATION_FAILED"), any(), any(), any(), any(Integer.class), any());
    }

    @Test
    @DisplayName("Post-provider coaching mode revocation marks run FAILED and throws exception")
    void generateAdjustment_postProviderAuthorityRevoked_marksRunFailed() {
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F03SourceSnapshot snapshot = buildSnapshot("SELF_DIRECTED", null);
        var envelopeResult = new F03ContextBuilder.ContextEnvelopeResult(null, snapshot);
        when(contextBuilder.buildContextEnvelope(eq(STUDENT_ID), eq(STUDENT_ID), any())).thenReturn(envelopeResult);

        String rawJson = "{\"recommendationType\":\"WORKOUT_ADJUSTMENT_PROPOSAL\"}";
        when(aiServiceClient.generateAdjustmentJson(any())).thenReturn(rawJson);
        when(outputValidator.validate(rawJson, snapshot)).thenReturn(F03RuntimeOutputValidator.ValidationResult.valid());

        when(coachingContextQuery.findEffectiveContext(STUDENT_ID, false)).thenReturn(Optional.of(
                new CurrentCoachingContext(UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), TRAINER_ID, Instant.now(), null)
        ));

        var req = new WorkoutAdjustmentApiRequest(
                STUDENT_ID,
                "cmd-1",
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, OCCURRENCE_ID, PRESCRIPTION_ID, null, null, null, null),
                ChangeType.LOAD_ADJUSTMENT,
                "reason",
                List.of(),
                List.of(),
                true
        );

        assertThatThrownBy(() -> adjustmentService.generateAdjustment(STUDENT_ID, req))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Coaching mode transitioned to HUMAN_COACH during generation");

        verify(aiRunRepository).completeRun(any(), eq(AiRunStatus.FAILED), any(), eq("AUTHORITY_REVOKED"), any(), any(), any(), any(Integer.class), any());
    }

    @Test
    @DisplayName("getAdjustment returns valid F03 recommendation for owner")
    void getAdjustment_successful_returnsRecommendation() {
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        UUID recId = UUID.randomUUID();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(STUDENT_ID)
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .structuredPayload("{}")
                .originalPayload("{}")
                .build();
        when(recommendationRepository.findById(recId)).thenReturn(Optional.of(rec));

        AiRecommendation result = adjustmentService.getAdjustment(STUDENT_ID, recId);
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(recId);
    }

    @Test
    @DisplayName("acceptAdjustment succeeds for ONE_MATERIALIZED_OCCURRENCE")
    void acceptAdjustment_occurrenceScope_success() throws Exception {
        UUID recId = UUID.randomUUID();
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F03SourceSnapshot snapshot = buildSnapshot("SELF_DIRECTED", null);
        String snapshotJson = objectMapper.writeValueAsString(snapshot);

        String payloadJson = """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "confidence": 0.95,
          "targetRef": {
            "scope": "ONE_MATERIALIZED_OCCURRENCE",
            "plannedWorkoutId": "%s",
            "plannedSessionExerciseId": "%s"
          },
          "changes": [
            {
              "changeId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "changeType": "LOAD_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetLoad": 20.0, "loadUnit": "KG", "loadUnitId": 1 },
              "after": { "targetLoad": 22.5, "loadUnit": "KG", "loadUnitId": 1, "direction": "INCREASE", "deltaLoad": 2.5, "deltaPercent": 12.5 },
              "reason": "Strength progression"
            }
          ]
        }
        """.formatted(OCCURRENCE_ID, PRESCRIPTION_ID, OCCURRENCE_ID, PRESCRIPTION_ID);

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(STUDENT_ID)
                .coachingPeriodId(PERIOD_ID)
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .revision(1)
                .structuredPayload(payloadJson)
                .sourceSnapshot(snapshotJson)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(STUDENT_ID, false)).thenReturn(Optional.of(
                new CurrentCoachingContext(PERIOD_ID, Mode.SELF_DIRECTED, null, null, Instant.now(), null)
        ));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(eq(payloadJson), any())).thenReturn(F03RuntimeOutputValidator.ValidationResult.valid());

        WorkoutScheduleCalendarQuery calendarQuery = mock(WorkoutScheduleCalendarQuery.class);
        when(scheduleCalendarProvider.getIfAvailable()).thenReturn(calendarQuery);
        WorkoutScheduleCalendarQuery.Occurrence occurrence = new WorkoutScheduleCalendarQuery.Occurrence(
                OCCURRENCE_ID, 2L, STUDENT_ID, "SCHEDULED",
                Instant.now(), Instant.now(), null, null, Instant.now().plus(1, ChronoUnit.HOURS),
                "UTC", "10:00", "11:00", "+00:00", "+00:00", null, null, List.of(), null, null
        );
        when(calendarQuery.detail(STUDENT_ID, OCCURRENCE_ID)).thenReturn(occurrence);

        when(workoutPlanCommandUseCase.adjustOccurrence(any())).thenReturn(
                new WorkoutPlanCommandUseCase.OccurrenceResult(OCCURRENCE_ID, UUID.randomUUID(), 3L, false)
        );

        AcceptAdjustmentApiRequest req = new AcceptAdjustmentApiRequest("key-1", "Approved", 1, 2L);
        AcceptAdjustmentApiResponse res = adjustmentService.acceptAdjustment(STUDENT_ID, recId, req);

        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(res.resultingEntityType()).isEqualTo("WORKOUT_OCCURRENCE");
        assertThat(res.resultingEntityId()).isEqualTo(OCCURRENCE_ID);
        assertThat(res.replayed()).isFalse();

        verify(recommendationRepository).updateDecision(eq(recId), eq(AiRecommendationStatus.APPLIED), eq(STUDENT_ID), any(), eq("Approved"), eq("WORKOUT_OCCURRENCE"), eq(OCCURRENCE_ID));
        verify(recommendationRepository).saveApplication(any());
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("acceptAdjustment succeeds for TEMPLATE_OR_FUTURE_OCCURRENCES")
    void acceptAdjustment_templateScope_success() throws Exception {
        UUID recId = UUID.randomUUID();
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F03SourceSnapshot snapshot = buildTemplateSnapshot("SELF_DIRECTED", null);
        String snapshotJson = objectMapper.writeValueAsString(snapshot);

        UUID newVariationId = UUID.fromString("99999999-9999-4999-8999-999999999999");
        String payloadJson = """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "confidence": 0.95,
          "targetRef": {
            "scope": "TEMPLATE_OR_FUTURE_OCCURRENCES",
            "planId": "%s",
            "planVersionNumber": 1,
            "exercisePrescriptionId": "%s"
          },
          "changes": [
            {
              "changeId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              "changeType": "EXERCISE_SUBSTITUTION",
              "scope": "TEMPLATE_OR_FUTURE_OCCURRENCES",
              "targetRef": {
                "scope": "TEMPLATE_OR_FUTURE_OCCURRENCES",
                "planId": "%s",
                "planVersionNumber": 1,
                "exercisePrescriptionId": "%s"
              },
              "before": { "exerciseVariationId": "%s", "exerciseName": "Barbell Squat" },
              "after": { "replacementVariationId": "%s", "replacementExerciseName": "Goblet Squat", "substitutionCategory": "EQUIPMENT_UNAVAILABLE" },
              "reason": "Rack occupied"
            }
          ]
        }
        """.formatted(PLAN_ID, PRESCRIPTION_ID, PLAN_ID, PRESCRIPTION_ID, UUID.randomUUID(), newVariationId);

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(STUDENT_ID)
                .coachingPeriodId(PERIOD_ID)
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .revision(1)
                .structuredPayload(payloadJson)
                .sourceSnapshot(snapshotJson)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(STUDENT_ID, false)).thenReturn(Optional.of(
                new CurrentCoachingContext(PERIOD_ID, Mode.SELF_DIRECTED, null, null, Instant.now(), null)
        ));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(eq(payloadJson), any())).thenReturn(F03RuntimeOutputValidator.ValidationResult.valid());

        PlanSummary planSummary = new PlanSummary(
                PLAN_ID, STUDENT_ID, UUID.randomUUID(), PERIOD_ID, "Plan", "Desc", "AI",
                WorkoutPlanStatus.ACTIVE, 1L, DecisionOwnerType.STUDENT, STUDENT_ID,
                null, null, Instant.now(), Instant.now(), null,
                VERSION_ID, 1, Instant.now(), null, WorkoutPlanViews.ReadContext.CURRENT
        );
        VersionSummary versionSummary = new VersionSummary(
                VERSION_ID, PLAN_ID, 1, Instant.now(), null, "INITIAL", "Created", "Summary",
                STUDENT_ID, Instant.now(), null, true, WorkoutPlanViews.ReadContext.CURRENT
        );
        when(workoutPlanQueryUseCase.findActive(STUDENT_ID, STUDENT_ID)).thenReturn(Optional.of(
                new PlanDetail(planSummary, versionSummary)
        ));

        PrescriptionView rx = new PrescriptionView(
                PRESCRIPTION_ID, UUID.randomUUID(), 1, 3, 8, 12, BigDecimal.valueOf(20.0), 90, null, "Focus on depth", null
        );
        SessionView session = new SessionView(
                UUID.randomUUID(), 1, 1, 1, "Day 1", "Legs", 60, null, null, List.of(rx)
        );
        when(workoutPlanQueryUseCase.version(STUDENT_ID, PLAN_ID, VERSION_ID)).thenReturn(
                new VersionDetail(versionSummary, List.of(session))
        );

        when(workoutPlanCommandUseCase.publishVersion(any())).thenReturn(
                new WorkoutPlanCommandUseCase.CommandResult(PLAN_ID, UUID.randomUUID(), 2, WorkoutPlanStatus.ACTIVE, 2L, Instant.now(), false)
        );

        AcceptAdjustmentApiRequest req = new AcceptAdjustmentApiRequest("key-2", "Switch to dumbbell", 1, 1L);
        AcceptAdjustmentApiResponse res = adjustmentService.acceptAdjustment(STUDENT_ID, recId, req);

        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(res.resultingEntityType()).isEqualTo("WORKOUT_PLAN");
        assertThat(res.resultingEntityId()).isEqualTo(PLAN_ID);
        assertThat(res.replayed()).isFalse();

        verify(workoutPlanCommandUseCase).publishVersion(any());
        verify(recommendationRepository).updateDecision(eq(recId), eq(AiRecommendationStatus.APPLIED), eq(STUDENT_ID), any(), eq("Switch to dumbbell"), eq("WORKOUT_PLAN"), eq(PLAN_ID));
    }

    @Test
    @DisplayName("acceptAdjustment replayed returns identical response when commandKey matches")
    void acceptAdjustment_idempotentReplay_returnsReplayedTrue() {
        UUID recId = UUID.randomUUID();
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(STUDENT_ID)
                .coachingPeriodId(PERIOD_ID)
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .status(AiRecommendationStatus.APPLIED)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .revision(1)
                .structuredPayload("{}")
                .build();
        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(STUDENT_ID, false)).thenReturn(Optional.of(
                new CurrentCoachingContext(PERIOD_ID, Mode.SELF_DIRECTED, null, null, Instant.now(), null)
        ));

        String appPayload = "{\"commandKey\":\"key-replay\",\"actorId\":\"" + STUDENT_ID + "\",\"appliedRevision\":1}";
        AiRecommendationApplication application = new AiRecommendationApplication(
                UUID.randomUUID(), recId, STUDENT_ID, "WORKOUT_OCCURRENCE", OCCURRENCE_ID, appPayload, Instant.now()
        );
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.of(application));

        AcceptAdjustmentApiRequest req = new AcceptAdjustmentApiRequest("key-replay", null, 1, null);
        AcceptAdjustmentApiResponse res = adjustmentService.acceptAdjustment(STUDENT_ID, recId, req);

        assertThat(res).isNotNull();
        assertThat(res.replayed()).isTrue();
        assertThat(res.resultingEntityType()).isEqualTo("WORKOUT_OCCURRENCE");
    }

    @Test
    @DisplayName("acceptAdjustment throws 403 when student in HUMAN_COACH attempts to accept")
    void acceptAdjustment_forbiddenWhenStudentInHumanCoach_throwsAccessDenied() {
        UUID recId = UUID.randomUUID();
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(STUDENT_ID)
                .coachingPeriodId(PERIOD_ID)
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.TRAINER)
                .build();
        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));

        when(coachingContextQuery.findEffectiveContext(STUDENT_ID, false)).thenReturn(Optional.of(
                new CurrentCoachingContext(PERIOD_ID, Mode.HUMAN_COACH, UUID.randomUUID(), TRAINER_ID, Instant.now(), null)
        ));

        AcceptAdjustmentApiRequest req = new AcceptAdjustmentApiRequest("key-forbidden", null, null, null);
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(STUDENT_ID, recId, req))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Only the currently assigned coach has decision authority");
    }

    @Test
    @DisplayName("rejectAdjustment successfully transitions recommendation to REJECTED")
    void rejectAdjustment_success() {
        UUID recId = UUID.randomUUID();
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(STUDENT_ID)
                .coachingPeriodId(PERIOD_ID)
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .structuredPayload("{}")
                .targetEntityType("WORKOUT_OCCURRENCE")
                .targetEntityId(OCCURRENCE_ID)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(STUDENT_ID, false)).thenReturn(Optional.of(
                new CurrentCoachingContext(PERIOD_ID, Mode.SELF_DIRECTED, null, null, Instant.now(), null)
        ));

        RejectAdjustmentApiRequest req = new RejectAdjustmentApiRequest("Don't want to change weight");
        RejectAdjustmentApiResponse res = adjustmentService.rejectAdjustment(STUDENT_ID, recId, req);

        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo(AiRecommendationStatus.REJECTED);
        assertThat(res.reason()).isEqualTo("Don't want to change weight");

        verify(recommendationRepository).updateDecision(eq(recId), eq(AiRecommendationStatus.REJECTED), eq(STUDENT_ID), any(), eq("Don't want to change weight"), eq("WORKOUT_OCCURRENCE"), eq(OCCURRENCE_ID));
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("rejectAdjustment throws conflict when proposal is expired")
    void rejectAdjustment_expired_throwsConflict() {
        UUID recId = UUID.randomUUID();
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(STUDENT_ID)
                .coachingPeriodId(PERIOD_ID)
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();
        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));

        RejectAdjustmentApiRequest req = new RejectAdjustmentApiRequest("Too late");
        assertThatThrownBy(() -> adjustmentService.rejectAdjustment(STUDENT_ID, recId, req))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("acceptAdjustment throws conflict when recommendation type is not WORKOUT_ADJUSTMENT_PROPOSAL")
    void acceptAdjustment_crossFeatureMismatch_throwsConflict() {
        UUID recId = UUID.randomUUID();
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(STUDENT_ID)
                .recommendationType("WORKOUT_PLAN_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .build();
        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));

        AcceptAdjustmentApiRequest req = new AcceptAdjustmentApiRequest("key-mismatch", null, null, null);
        assertThatThrownBy(() -> adjustmentService.acceptAdjustment(STUDENT_ID, recId, req))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("not a workout adjustment proposal");
    }
    @Test
    void acceptAdjustment_occurrenceScope_volumeAdjustment_bothSetsAndReps_executesBothCommandsSequentially() throws Exception {
        UUID recId = UUID.randomUUID();
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F03SourceSnapshot snapshot = buildSnapshot("SELF_DIRECTED", null);
        String snapshotJson = objectMapper.writeValueAsString(snapshot);

        String payloadJson = """
        {
          "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
          "summary": "Volume adjustment",
          "reasoning": ["progression"],
          "usedData": ["rpe"],
          "missingData": [],
          "limitations": [],
          "confidence": 0.9,
          "targetRef": {
            "scope": "ONE_MATERIALIZED_OCCURRENCE",
            "plannedWorkoutId": "%s",
            "plannedSessionExerciseId": "%s"
          },
          "changes": [
            {
              "changeId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              "changeType": "VOLUME_ADJUSTMENT",
              "scope": "ONE_MATERIALIZED_OCCURRENCE",
              "targetRef": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": "%s",
                "plannedSessionExerciseId": "%s"
              },
              "before": { "targetSets": 3, "targetRepsMin": 10, "targetRepsMax": 12 },
              "after": { "targetSets": 4, "targetRepsMin": 8, "targetRepsMax": 10, "deltaSets": 1, "deltaRepsMin": -2, "deltaRepsMax": -2 },
              "reason": "Volume and rep range progression"
            }
          ]
        }
        """.formatted(OCCURRENCE_ID, PRESCRIPTION_ID, OCCURRENCE_ID, PRESCRIPTION_ID);

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(STUDENT_ID)
                .coachingPeriodId(PERIOD_ID)
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .revision(1)
                .structuredPayload(payloadJson)
                .sourceSnapshot(snapshotJson)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(STUDENT_ID, false)).thenReturn(Optional.of(
                new CurrentCoachingContext(PERIOD_ID, Mode.SELF_DIRECTED, null, null, Instant.now(), null)
        ));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(eq(payloadJson), any())).thenReturn(F03RuntimeOutputValidator.ValidationResult.valid());

        WorkoutScheduleCalendarQuery calendarQuery = mock(WorkoutScheduleCalendarQuery.class);
        when(scheduleCalendarProvider.getIfAvailable()).thenReturn(calendarQuery);
        WorkoutScheduleCalendarQuery.Occurrence occurrence = new WorkoutScheduleCalendarQuery.Occurrence(
                OCCURRENCE_ID, 2L, STUDENT_ID, "SCHEDULED",
                Instant.now(), Instant.now(), null, null, Instant.now().plus(1, ChronoUnit.HOURS),
                "UTC", "10:00", "11:00", "+00:00", "+00:00", null, null, List.of(), null, null
        );
        when(calendarQuery.detail(STUDENT_ID, OCCURRENCE_ID)).thenReturn(occurrence);

        // Sequence of adjustments: 1st returns version 3L, 2nd returns version 4L
        when(workoutPlanCommandUseCase.adjustOccurrence(any()))
                .thenReturn(new WorkoutPlanCommandUseCase.OccurrenceResult(OCCURRENCE_ID, UUID.randomUUID(), 3L, false))
                .thenReturn(new WorkoutPlanCommandUseCase.OccurrenceResult(OCCURRENCE_ID, UUID.randomUUID(), 4L, false));

        AcceptAdjustmentApiRequest req = new AcceptAdjustmentApiRequest("key-vol-both", "Approved volume", 1, 2L);
        AcceptAdjustmentApiResponse res = adjustmentService.acceptAdjustment(STUDENT_ID, recId, req);

        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        org.mockito.ArgumentCaptor<WorkoutPlanCommandUseCase.AdjustOccurrenceCommand> captor =
                org.mockito.ArgumentCaptor.forClass(WorkoutPlanCommandUseCase.AdjustOccurrenceCommand.class);
        verify(workoutPlanCommandUseCase, org.mockito.Mockito.times(2)).adjustOccurrence(captor.capture());

        List<WorkoutPlanCommandUseCase.AdjustOccurrenceCommand> capturedCommands = captor.getAllValues();
        assertThat(capturedCommands).hasSize(2);

        // First command: SETS
        WorkoutPlanCommandUseCase.AdjustOccurrenceCommand cmd1 = capturedCommands.get(0);
        assertThat(cmd1.type()).isEqualTo(WorkoutSessionAdjustment.Type.SETS);
        assertThat(cmd1.expectedVersion()).isEqualTo(2L);
        assertThat(cmd1.commandKey()).endsWith(":sets");
        assertThat(cmd1.afterJson()).contains("\"targetSets\":4");

        // Second command: REPS (chained on resulting version 3L)
        WorkoutPlanCommandUseCase.AdjustOccurrenceCommand cmd2 = capturedCommands.get(1);
        assertThat(cmd2.type()).isEqualTo(WorkoutSessionAdjustment.Type.REPS);
        assertThat(cmd2.expectedVersion()).isEqualTo(3L);
        assertThat(cmd2.commandKey()).endsWith(":reps");
        assertThat(cmd2.afterJson()).contains("\"targetRepsMin\":8");
        assertThat(cmd2.afterJson()).contains("\"targetRepsMax\":10");
    }
}
