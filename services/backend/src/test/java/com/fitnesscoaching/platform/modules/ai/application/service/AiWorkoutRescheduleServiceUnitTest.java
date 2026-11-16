package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F05AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F05AiServiceClient.F05ServiceRequest;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationApplication;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.F05SourceSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.CurrentCoachingContext;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.Mode;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery.Occurrence;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.ChangeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiWorkoutRescheduleServiceUnitTest {

    @Mock private AiRunRepository aiRunRepository;
    @Mock private AiRecommendationRepository recommendationRepository;
    @Mock private F05AiServiceClient aiServiceClient;
    @Mock private F05ContextBuilder contextBuilder;
    @Mock private F05RuntimeOutputValidator outputValidator;
    @Mock private AuditService auditService;
    @Mock private CurrentCoachingContextQuery coachingContextQuery;
    @Mock private WorkoutScheduleChangeUseCase workoutScheduleChangeUseCase;
    @Mock private ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider;
    @Mock private WorkoutScheduleCalendarQuery calendarQuery;

    private ObjectMapper objectMapper;
    private Clock clock;
    private Instant fixedNow;
    private AiWorkoutRescheduleService service;

    private UUID studentId;
    private UUID trainerId;
    private UUID occurrenceId;
    private UUID periodId;
    private F05SourceSnapshot defaultSnapshot;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fixedNow = Instant.parse("2026-11-16T10:00:00Z");
        clock = Clock.fixed(fixedNow, ZoneId.of("UTC"));

        studentId = UUID.randomUUID();
        trainerId = UUID.randomUUID();
        occurrenceId = UUID.randomUUID();
        periodId = UUID.randomUUID();

        lenient().when(scheduleCalendarProvider.getIfAvailable()).thenReturn(calendarQuery);

        service = new AiWorkoutRescheduleService(
                aiRunRepository,
                recommendationRepository,
                aiServiceClient,
                contextBuilder,
                outputValidator,
                auditService,
                objectMapper,
                coachingContextQuery,
                workoutScheduleChangeUseCase,
                scheduleCalendarProvider,
                clock
        );

        defaultSnapshot = new F05SourceSnapshot(
                studentId,
                periodId,
                "SELF_DIRECTED",
                null,
                occurrenceId,
                0L,
                "SCHEDULED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Upper Body Push",
                fixedNow.plus(1, ChronoUnit.DAYS),
                fixedNow.plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS),
                fixedNow.plus(1, ChronoUnit.DAYS),
                "Asia/Ho_Chi_Minh",
                "2026-11-17T09:00:00",
                "2026-11-17T10:00:00",
                "+07:00",
                "+07:00",
                "SELF_PERFORMABLE",
                null,
                null,
                TrainingContinuityState.RECENT_CONSISTENT,
                fixedNow
        );
    }

    private String validPayloadJson() {
        return """
            {
              "recommendationType": "WORKOUT_RESCHEDULE_PROPOSAL",
              "summary": "Reschedule workout to tomorrow morning at 09:00",
              "reasoning": ["Slot provides adequate rest."],
              "usedData": ["occurrenceId: %s"],
              "missingData": [],
              "limitations": ["Single occurrence only."],
              "confidence": 0.95,
              "reschedule": {
                "occurrenceId": "%s",
                "previousPlannedStartAt": "2026-11-16T02:00:00Z",
                "supervisionRequirement": "SELF_PERFORMABLE",
                "destination": {
                  "localStart": "2026-11-17T09:00:00",
                  "localEnd": "2026-11-17T10:00:00",
                  "timezone": "Asia/Ho_Chi_Minh",
                  "startUtcOffset": "+07:00",
                  "endUtcOffset": "+07:00",
                  "proposedStartAt": "2026-11-17T02:00:00Z",
                  "proposedEndAt": "2026-11-17T03:00:00Z"
                }
              }
            }
            """.formatted(occurrenceId, occurrenceId);
    }

    @Test
    @DisplayName("generateReschedule: successful generation in SELF_DIRECTED mode")
    void generateReschedule_success() throws Exception {
        var req = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, "cmd-1", "Conflict", "MORNING");
        var serviceReq = new F05ServiceRequest(
                studentId, periodId, "SELF_DIRECTED", occurrenceId, 0L,
                UUID.randomUUID(), UUID.randomUUID(), "Push", "SELF_PERFORMABLE",
                "Asia/Ho_Chi_Minh", null, fixedNow.toString(), null, "Conflict", "MORNING",
                "RECENT_CONSISTENT", false, null, defaultSnapshot
        );
        var envelope = new F05ContextBuilder.ContextEnvelopeResult(
                serviceReq, defaultSnapshot, AiDecisionAuthority.STUDENT, studentId, periodId
        );

        when(contextBuilder.buildContextEnvelope(studentId, req)).thenReturn(envelope);
        when(aiServiceClient.generateRescheduleJson(serviceReq)).thenReturn(validPayloadJson());
        when(outputValidator.validate(any(), any())).thenReturn(F05RuntimeOutputValidator.ValidationResult.valid());
        when(recommendationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkoutRescheduleApiResponse response = service.generateReschedule(studentId, req);

        assertThat(response.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(response.decisionAuthority()).isEqualTo(AiDecisionAuthority.STUDENT);
        assertThat(response.expiresAt()).isEqualTo(fixedNow.plus(30, ChronoUnit.MINUTES));
        assertThat(response.title()).contains("Reschedule workout");

        verify(aiRunRepository).create(any(AiRun.class));
        verify(aiRunRepository).completeRun(any(), eq(AiRunStatus.SUCCEEDED), any(), isNull(), isNull(), any(), any(), any(), eq(fixedNow));
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("getReschedule: student can view own recommendation")
    void getReschedule_studentView_success() {
        UUID recId = UUID.randomUUID();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .build();

        when(recommendationRepository.findById(recId)).thenReturn(Optional.of(rec));

        AiRecommendation found = service.getReschedule(studentId, recId);
        assertThat(found.id()).isEqualTo(recId);
    }

    @Test
    @DisplayName("getReschedule: unrelated actor is denied (403)")
    void getReschedule_unrelatedActor_denied() {
        UUID recId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .build();

        when(recommendationRepository.findById(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReschedule(strangerId, recId))
                .isInstanceOf(AiRunAccessDeniedException.class);
    }

    @Test
    @DisplayName("T02: Student in HUMAN_COACH mode cannot unilaterally accept AI reschedule")
    void acceptReschedule_whenHumanCoachMode_studentAcceptBlocked_T02() {
        UUID recId = UUID.randomUUID();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.TRAINER)
                .expiresAt(fixedNow.plus(30, ChronoUnit.MINUTES))
                .revision(1)
                .sourceSnapshot("{}")
                .structuredPayload(validPayloadJson())
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, fixedNow, null))
        );

        AcceptRescheduleApiRequest acceptReq = new AcceptRescheduleApiRequest("cmd-acc", "Accepting", 1, 0L, null, null);

        assertThatThrownBy(() -> service.acceptReschedule(studentId, recId, acceptReq))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Student in HUMAN_COACH mode cannot unilaterally accept AI workout rescheduling");
    }

    @Test
    @DisplayName("T03: Unrelated trainer cannot accept reschedule for another student")
    void acceptReschedule_whenUnrelatedTrainer_blocked_T03() {
        UUID recId = UUID.randomUUID();
        UUID unrelatedTrainer = UUID.randomUUID();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.TRAINER)
                .expiresAt(fixedNow.plus(30, ChronoUnit.MINUTES))
                .revision(1)
                .sourceSnapshot("{}")
                .structuredPayload(validPayloadJson())
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, fixedNow, null))
        );

        AcceptRescheduleApiRequest acceptReq = new AcceptRescheduleApiRequest("cmd-acc", "Accepting", 1, 0L, null, null);

        assertThatThrownBy(() -> service.acceptReschedule(unrelatedTrainer, recId, acceptReq))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Only the currently assigned coach has decision authority");
    }

    @Test
    @DisplayName("T05: Occurrence version conflict between snapshot and live state throws conflict (409)")
    void acceptReschedule_whenOccurrenceVersionConflict_throwsLifecycleConflict_T05() throws Exception {
        UUID recId = UUID.randomUUID();
        String snapshotJson = objectMapper.writeValueAsString(defaultSnapshot);

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .expiresAt(fixedNow.plus(30, ChronoUnit.MINUTES))
                .revision(1)
                .sourceSnapshot(snapshotJson)
                .structuredPayload(validPayloadJson())
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null))
        );
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any(), any())).thenReturn(F05RuntimeOutputValidator.ValidationResult.valid());

        // Live occurrence has version 1L instead of snapshot's 0L
        Occurrence liveOccurrence = new Occurrence(
                occurrenceId, 1L, studentId, "SCHEDULED",
                fixedNow, null, null, null, null,
                "Asia/Ho_Chi_Minh", "2026-11-17T09:00:00", "2026-11-17T10:00:00",
                "+07:00", "+07:00", null, null, null, null, null
        );
        when(calendarQuery.detail(studentId, occurrenceId)).thenReturn(liveOccurrence);

        AcceptRescheduleApiRequest acceptReq = new AcceptRescheduleApiRequest("cmd-acc", "Accepting", 1, 0L, null, null);

        assertThatThrownBy(() -> service.acceptReschedule(studentId, recId, acceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Workout occurrence version conflict");
    }

    @Test
    @DisplayName("T05: Occurrence not in SCHEDULED status throws conflict (409)")
    void acceptReschedule_whenOccurrenceNotScheduled_throwsLifecycleConflict_T05() throws Exception {
        UUID recId = UUID.randomUUID();
        String snapshotJson = objectMapper.writeValueAsString(defaultSnapshot);

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .expiresAt(fixedNow.plus(30, ChronoUnit.MINUTES))
                .revision(1)
                .sourceSnapshot(snapshotJson)
                .structuredPayload(validPayloadJson())
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null))
        );
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any(), any())).thenReturn(F05RuntimeOutputValidator.ValidationResult.valid());

        // Live occurrence already CANCELLED or COMPLETED
        Occurrence liveOccurrence = new Occurrence(
                occurrenceId, 0L, studentId, "CANCELLED",
                fixedNow, null, null, null, null,
                "Asia/Ho_Chi_Minh", "2026-11-17T09:00:00", "2026-11-17T10:00:00",
                "+07:00", "+07:00", null, null, null, null, null
        );
        when(calendarQuery.detail(studentId, occurrenceId)).thenReturn(liveOccurrence);

        AcceptRescheduleApiRequest acceptReq = new AcceptRescheduleApiRequest("cmd-acc", "Accepting", 1, 0L, null, null);

        assertThatThrownBy(() -> service.acceptReschedule(studentId, recId, acceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("no longer in SCHEDULED status");
    }

    @Test
    @DisplayName("T06: Idempotent replay with matching commandKey returns cached receipt with replayed=true")
    void acceptReschedule_idempotentReplay_T06() throws Exception {
        UUID recId = UUID.randomUUID();
        String payloadJson = validPayloadJson();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.APPLIED) // already applied
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .expiresAt(fixedNow.plus(30, ChronoUnit.MINUTES))
                .revision(1)
                .structuredPayload(payloadJson)
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null))
        );

        String appPayload = """
            {
              "commandKey": "cmd-replay-1",
              "actorId": "%s",
              "appliedRevision": 1,
              "expectedOccurrenceVersion": 0,
              "decisionNote": "Note 1"
            }
            """.formatted(studentId);

        AiRecommendationApplication app = new AiRecommendationApplication(
                UUID.randomUUID(), recId, studentId, "WORKOUT_OCCURRENCE",
                UUID.randomUUID(), appPayload, fixedNow
        );
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.of(app));

        AcceptRescheduleApiRequest acceptReq = new AcceptRescheduleApiRequest("cmd-replay-1", "Note 1", 1, 0L, null, null);

        AcceptRescheduleApiResponse response = service.acceptReschedule(studentId, recId, acceptReq);

        assertThat(response.replayed()).isTrue();
        assertThat(response.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        verify(workoutScheduleChangeUseCase, never()).reschedule(any());
    }

    @Test
    @DisplayName("T25: Differing parameters with same commandKey throws idempotency conflict (409)")
    void acceptReschedule_differingParametersSameKey_throwsConflict_T25() {
        UUID recId = UUID.randomUUID();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.APPLIED)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .revision(1)
                .structuredPayload(validPayloadJson())
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null))
        );

        String appPayload = """
            {
              "commandKey": "cmd-replay-1",
              "actorId": "%s",
              "appliedRevision": 1,
              "expectedOccurrenceVersion": 0,
              "decisionNote": "Original Note"
            }
            """.formatted(studentId);

        AiRecommendationApplication app = new AiRecommendationApplication(
                UUID.randomUUID(), recId, studentId, "WORKOUT_OCCURRENCE",
                UUID.randomUUID(), appPayload, fixedNow
        );
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.of(app));

        // Different note
        AcceptRescheduleApiRequest acceptReq = new AcceptRescheduleApiRequest("cmd-replay-1", "DIFFERENT Note", 1, 0L, null, null);

        assertThatThrownBy(() -> service.acceptReschedule(studentId, recId, acceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Idempotency conflict");
    }

    @Test
    @DisplayName("T23: Expired recommendation throws conflict (409) and marks recommendation EXPIRED")
    void acceptReschedule_whenExpired_marksExpiredAndThrows_T23() {
        UUID recId = UUID.randomUUID();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .targetEntityType("WORKOUT_OCCURRENCE")
                .targetEntityId(occurrenceId)
                .expiresAt(fixedNow.minus(5, ChronoUnit.MINUTES)) // expired!
                .revision(1)
                .structuredPayload(validPayloadJson())
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null))
        );
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());

        AcceptRescheduleApiRequest acceptReq = new AcceptRescheduleApiRequest("cmd-acc", "Accepting", 1, 0L, null, null);

        assertThatThrownBy(() -> service.acceptReschedule(studentId, recId, acceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Recommendation has expired");

        verify(recommendationRepository).updateDecision(eq(recId), eq(AiRecommendationStatus.EXPIRED), isNull(), eq(fixedNow), any(), eq("WORKOUT_OCCURRENCE"), eq(occurrenceId));
    }

    @Test
    @DisplayName("T01/T27: Student in SELF_DIRECTED mode accepts reschedule, applies atomically via workout change use case")
    void acceptReschedule_whenSelfDirected_studentAcceptSucceeds_T01_T27() throws Exception {
        UUID recId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();
        String snapshotJson = objectMapper.writeValueAsString(defaultSnapshot);

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .targetEntityType("WORKOUT_OCCURRENCE")
                .targetEntityId(occurrenceId)
                .expiresAt(fixedNow.plus(30, ChronoUnit.MINUTES))
                .revision(1)
                .sourceSnapshot(snapshotJson)
                .structuredPayload(validPayloadJson())
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null))
        );
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any(), any())).thenReturn(F05RuntimeOutputValidator.ValidationResult.valid());

        Occurrence liveOccurrence = new Occurrence(
                occurrenceId, 0L, studentId, "SCHEDULED",
                fixedNow, null, null, null, null,
                "Asia/Ho_Chi_Minh", "2026-11-17T09:00:00", "2026-11-17T10:00:00",
                "+07:00", "+07:00", null, null, null, null, null
        );
        when(calendarQuery.detail(studentId, occurrenceId)).thenReturn(liveOccurrence);

        ChangeResult changeResult = new ChangeResult(
                "RESCHEDULE", occurrenceId, 1L, "RESCHEDULED",
                fixedNow, fixedNow, fixedNow.plus(1, ChronoUnit.HOURS),
                replacementId, 0L,
                fixedNow.plus(1, ChronoUnit.DAYS), fixedNow.plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS),
                occurrenceId, fixedNow, false
        );
        when(workoutScheduleChangeUseCase.reschedule(any())).thenReturn(changeResult);

        AcceptRescheduleApiRequest acceptReq = new AcceptRescheduleApiRequest("cmd-acc-1", "Apply reschedule", 1, 0L, null, null);

        AcceptRescheduleApiResponse response = service.acceptReschedule(studentId, recId, acceptReq);

        assertThat(response.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(response.replayed()).isFalse();
        assertThat(response.resultingEntityId()).isEqualTo(replacementId);
        assertThat(response.changeResult()).isEqualTo(changeResult);

        verify(workoutScheduleChangeUseCase).reschedule(any());
        verify(recommendationRepository).updateDecision(eq(recId), eq(AiRecommendationStatus.APPLIED), eq(studentId), eq(fixedNow), eq("Apply reschedule"), eq("WORKOUT_OCCURRENCE"), eq(replacementId));
        verify(recommendationRepository).saveApplication(any(AiRecommendationApplication.class));
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("rejectReschedule: transitions status to REJECTED and records audit")
    void rejectReschedule_success() {
        UUID recId = UUID.randomUUID();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .targetEntityType("WORKOUT_OCCURRENCE")
                .targetEntityId(occurrenceId)
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null))
        );

        RejectRescheduleApiResponse response = service.rejectReschedule(studentId, recId, new RejectRescheduleApiRequest("Not convenient"));

        assertThat(response.status()).isEqualTo(AiRecommendationStatus.REJECTED);
        verify(recommendationRepository).updateDecision(eq(recId), eq(AiRecommendationStatus.REJECTED), eq(studentId), eq(fixedNow), eq("Not convenient"), eq("WORKOUT_OCCURRENCE"), eq(occurrenceId));
        verify(auditService).recordAudit(any());
    }
}
