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
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityReason;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    @Mock private UserAccountStatusQuery accountStatusQuery;
    @Mock private CoachingAuthorityQuery coachingAuthorityQuery;

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
        lenient().when(accountStatusQuery.getAccountStatus(any())).thenReturn(Optional.of(AccountStatus.ACTIVE));
        AuthorityDecision defaultAllow = new AuthorityDecision(true, null, UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(), 1L, DataAccessLevel.MANAGE, null, null);
        lenient().when(coachingAuthorityQuery.evaluateCurrent(any())).thenReturn(defaultAllow);

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
                clock,
                null,
                accountStatusQuery,
                coachingAuthorityQuery
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
    @DisplayName("getReschedule: when expired, marks recommendation EXPIRED conditionally if PENDING")
    void getReschedule_whenExpired_marksExpiredIfPending() {
        UUID recId = UUID.randomUUID();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .targetEntityType("WORKOUT_OCCURRENCE")
                .targetEntityId(occurrenceId)
                .expiresAt(fixedNow.minus(1, ChronoUnit.MINUTES))
                .revision(1)
                .build();

        AiRecommendation expiredRec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.EXPIRED)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .targetEntityType("WORKOUT_OCCURRENCE")
                .targetEntityId(occurrenceId)
                .expiresAt(fixedNow.minus(1, ChronoUnit.MINUTES))
                .revision(1)
                .build();

        when(recommendationRepository.findById(recId)).thenReturn(Optional.of(rec), Optional.of(expiredRec));
        when(recommendationRepository.updateDecisionIfStatus(
                eq(recId), eq(AiRecommendationStatus.PENDING), eq(AiRecommendationStatus.EXPIRED),
                isNull(), eq(fixedNow), any(), eq("WORKOUT_OCCURRENCE"), eq(occurrenceId)
        )).thenReturn(true);

        AiRecommendation result = service.getReschedule(studentId, recId);
        assertThat(result.status()).isEqualTo(AiRecommendationStatus.EXPIRED);
        verify(recommendationRepository).updateDecisionIfStatus(
                eq(recId), eq(AiRecommendationStatus.PENDING), eq(AiRecommendationStatus.EXPIRED),
                isNull(), eq(fixedNow), any(), eq("WORKOUT_OCCURRENCE"), eq(occurrenceId)
        );
    }

    @Test
    @DisplayName("acceptReschedule: when linked appointment changed after proposal generation, throws conflict (409)")
    void acceptReschedule_whenLinkedAppointmentChanged_throwsConflict() throws Exception {
        UUID recId = UUID.randomUUID();
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

        // Live occurrence now has a linked appointment that wasn't in sourceSnapshot!
        Occurrence liveOccurrence = new Occurrence(
                occurrenceId, 0L, studentId, "SCHEDULED",
                fixedNow, null, null, null, null,
                "Asia/Ho_Chi_Minh", "2026-11-16T09:00:00", "2026-11-16T10:00:00", "+07:00", "+07:00",
                null, null, List.of("RESCHEDULE"), UUID.randomUUID(), 1L
        );
        when(calendarQuery.detail(studentId, occurrenceId)).thenReturn(liveOccurrence);

        AcceptRescheduleApiRequest acceptReq = new AcceptRescheduleApiRequest("cmd-acc-app", "Accepting", 1, 0L, null, null);

        assertThatThrownBy(() -> service.acceptReschedule(studentId, recId, acceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Linked appointment has changed");
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

    @Test
    @DisplayName("T04: In-flight revocation of Trainer MANAGE permission aborts delivery, fails AI run without storing payload")
    void generateReschedule_whenTrainerManageRevokedInFlight_abortsDeliveryAndFailsRun_T04() throws Exception {
        var req = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, "cmd-t04-manage", "Conflict", "MORNING");
        var serviceReq = new F05ServiceRequest(
                studentId, periodId, "HUMAN_COACH", occurrenceId, 0L,
                UUID.randomUUID(), UUID.randomUUID(), "Push", "COACH_OPTIONAL",
                "Asia/Ho_Chi_Minh", null, fixedNow.toString(), null, "Conflict", "MORNING",
                "RECENT_ACTIVITY_OBSERVED", false, null, defaultSnapshot
        );
        var envelope = new F05ContextBuilder.ContextEnvelopeResult(
                serviceReq, defaultSnapshot, AiDecisionAuthority.TRAINER, studentId, periodId
        );

        when(contextBuilder.buildContextEnvelope(trainerId, req)).thenReturn(envelope);
        when(outputValidator.validate(any(), any())).thenReturn(F05RuntimeOutputValidator.ValidationResult.valid());

        // Initial state before provider call: active coach in HUMAN_COACH mode with MANAGE & VIEW permissions
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, fixedNow, null))
        );

        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch revocationApplied = new CountDownLatch(1);

        when(aiServiceClient.generateRescheduleJson(serviceReq)).thenAnswer(inv -> {
            providerStarted.countDown();
            boolean waited = revocationApplied.await(5, TimeUnit.SECONDS);
            assertThat(waited).isTrue();
            return validPayloadJson();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Future<?> future = executor.submit(() -> {
            try {
                service.generateReschedule(trainerId, req);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });

        // Wait until provider starts executing
        assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Simulate concurrent revocation of MANAGE authority during provider execution
        when(coachingAuthorityQuery.evaluateCurrent(argThat(r -> r != null && r.dataScope() == DataScope.WORKOUT_PLAN && r.requiredLevel() == DataAccessLevel.MANAGE)))
                .thenReturn(AuthorityDecision.denied(AuthorityReason.ACCESS_LEVEL_INSUFFICIENT));

        revocationApplied.countDown();
        future.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(thrown.get())
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Trainer MANAGE permission for WORKOUT_PLAN was revoked during generation");

        // Verify AI Run was marked FAILED with AUTHORITY_REVOKED and outputPayload was NOT persisted (null)
        verify(aiRunRepository).completeRun(any(), eq(AiRunStatus.FAILED), isNull(), eq("AUTHORITY_REVOKED"), any(), isNull(), isNull(), isNull(), eq(fixedNow));
        // Verify no recommendation was persisted
        verify(recommendationRepository, never()).save(any());
        // Verify audit proposed was not recorded
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("T04: In-flight revocation of Trainer VIEW permission aborts delivery without leaking sensitive payload")
    void generateReschedule_whenTrainerViewRevokedInFlight_abortsDeliveryAndFailsRun_T04() throws Exception {
        var req = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, "cmd-t04-view", "Conflict", "MORNING");
        var serviceReq = new F05ServiceRequest(
                studentId, periodId, "HUMAN_COACH", occurrenceId, 0L,
                UUID.randomUUID(), UUID.randomUUID(), "Push", "COACH_OPTIONAL",
                "Asia/Ho_Chi_Minh", null, fixedNow.toString(), null, "Conflict", "MORNING",
                "RECENT_ACTIVITY_OBSERVED", false, null, defaultSnapshot
        );
        var envelope = new F05ContextBuilder.ContextEnvelopeResult(
                serviceReq, defaultSnapshot, AiDecisionAuthority.TRAINER, studentId, periodId
        );

        when(contextBuilder.buildContextEnvelope(trainerId, req)).thenReturn(envelope);
        when(outputValidator.validate(any(), any())).thenReturn(F05RuntimeOutputValidator.ValidationResult.valid());

        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, fixedNow, null))
        );

        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch revocationApplied = new CountDownLatch(1);

        when(aiServiceClient.generateRescheduleJson(serviceReq)).thenAnswer(inv -> {
            providerStarted.countDown();
            boolean waited = revocationApplied.await(5, TimeUnit.SECONDS);
            assertThat(waited).isTrue();
            return validPayloadJson();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Future<?> future = executor.submit(() -> {
            try {
                service.generateReschedule(trainerId, req);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });

        assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Revoke VIEW permission while MANAGE remains allowed
        when(coachingAuthorityQuery.evaluateCurrent(argThat(r -> r != null && r.dataScope() == DataScope.WORKOUT_PLAN && r.requiredLevel() == DataAccessLevel.VIEW)))
                .thenReturn(AuthorityDecision.denied(AuthorityReason.PERMISSION_NOT_GRANTED));

        revocationApplied.countDown();
        future.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(thrown.get())
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Trainer VIEW permission for WORKOUT_PLAN was revoked during generation");

        verify(aiRunRepository).completeRun(any(), eq(AiRunStatus.FAILED), isNull(), eq("AUTHORITY_REVOKED"), any(), isNull(), isNull(), isNull(), eq(fixedNow));
        verify(recommendationRepository, never()).save(any());
    }

    @Test
    @DisplayName("T04: Coaching period changed during provider execution aborts proposal delivery")
    void generateReschedule_whenCoachingPeriodChangedInFlight_abortsDelivery_T04() throws Exception {
        var req = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, "cmd-t04-period", "Conflict", "MORNING");
        var serviceReq = new F05ServiceRequest(
                studentId, periodId, "HUMAN_COACH", occurrenceId, 0L,
                UUID.randomUUID(), UUID.randomUUID(), "Push", "COACH_OPTIONAL",
                "Asia/Ho_Chi_Minh", null, fixedNow.toString(), null, "Conflict", "MORNING",
                "RECENT_ACTIVITY_OBSERVED", false, null, defaultSnapshot
        );
        var envelope = new F05ContextBuilder.ContextEnvelopeResult(
                serviceReq, defaultSnapshot, AiDecisionAuthority.TRAINER, studentId, periodId
        );

        when(contextBuilder.buildContextEnvelope(trainerId, req)).thenReturn(envelope);
        when(outputValidator.validate(any(), any())).thenReturn(F05RuntimeOutputValidator.ValidationResult.valid());

        // Context at start: periodId
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, fixedNow, null))
        );

        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch changeApplied = new CountDownLatch(1);

        when(aiServiceClient.generateRescheduleJson(serviceReq)).thenAnswer(inv -> {
            providerStarted.countDown();
            changeApplied.await(5, TimeUnit.SECONDS);
            return validPayloadJson();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Future<?> future = executor.submit(() -> {
            try {
                service.generateReschedule(trainerId, req);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });

        assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Period changes to new period
        UUID newPeriodId = UUID.randomUUID();
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(newPeriodId, Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, fixedNow, null))
        );

        changeApplied.countDown();
        future.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(thrown.get())
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Trainer coaching relationship or period was revoked or changed during generation");

        verify(aiRunRepository).completeRun(any(), eq(AiRunStatus.FAILED), isNull(), eq("AUTHORITY_REVOKED"), any(), isNull(), isNull(), isNull(), eq(fixedNow));
        verify(recommendationRepository, never()).save(any());
    }

    @Test
    @DisplayName("T04 Readback: When Trainer VIEW permission is revoked, getReschedule denies access without leaking payload")
    void getReschedule_whenTrainerViewRevoked_blocksReadback_T04() {
        UUID recId = UUID.randomUUID();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .structuredPayload(validPayloadJson())
                .build();

        when(recommendationRepository.findById(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, fixedNow, null))
        );

        // VIEW permission is denied
        when(coachingAuthorityQuery.evaluateCurrent(argThat(r -> r != null && r.dataScope() == DataScope.WORKOUT_PLAN && r.requiredLevel() == DataAccessLevel.VIEW)))
                .thenReturn(AuthorityDecision.denied(AuthorityReason.PERMISSION_NOT_GRANTED));

        assertThatThrownBy(() -> service.getReschedule(trainerId, recId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Trainer VIEW permission for WORKOUT_PLAN is revoked");
    }

    @Test
    @DisplayName("T04 Authority Matrix: Revoking MANAGE preserves VIEW for readback, but blocks acceptReschedule")
    void getReschedule_whenTrainerManageRevoked_stillAllowsView_T04() {
        UUID recId = UUID.randomUUID();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRecommendationStatus.PENDING)
                .structuredPayload(validPayloadJson())
                .build();

        when(recommendationRepository.findById(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(
                Optional.of(new CurrentCoachingContext(periodId, Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, fixedNow, null))
        );

        // VIEW is allowed, but MANAGE is revoked
        AuthorityDecision allowView = new AuthorityDecision(true, null, UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(), 1L, DataAccessLevel.VIEW, null, null);
        when(coachingAuthorityQuery.evaluateCurrent(argThat(r -> r != null && r.dataScope() == DataScope.WORKOUT_PLAN && r.requiredLevel() == DataAccessLevel.VIEW)))
                .thenReturn(allowView);
        when(coachingAuthorityQuery.evaluateCurrent(argThat(r -> r != null && r.dataScope() == DataScope.WORKOUT_PLAN && r.requiredLevel() == DataAccessLevel.MANAGE)))
                .thenReturn(AuthorityDecision.denied(AuthorityReason.ACCESS_LEVEL_INSUFFICIENT));

        // Readback succeeds because actor still retains VIEW authority
        AiRecommendation found = service.getReschedule(trainerId, recId);
        assertThat(found.id()).isEqualTo(recId);

        // But Accept is strictly blocked because actor lacks MANAGE authority
        AcceptRescheduleApiRequest acceptReq = new AcceptRescheduleApiRequest("cmd-acc-1", "Apply reschedule", 1, 0L, null, null);
        assertThatThrownBy(() -> service.acceptReschedule(trainerId, recId, acceptReq))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Trainer coaching or data sharing authority is no longer valid");
    }
}
