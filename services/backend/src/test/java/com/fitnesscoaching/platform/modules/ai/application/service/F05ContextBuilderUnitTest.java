package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.CurrentCoachingContext;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.Mode;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.ContinuitySignal;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.ContinuityState;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.ProgressSnapshot;
import com.fitnesscoaching.platform.modules.progress.domain.ProgressError;
import com.fitnesscoaching.platform.modules.progress.domain.ProgressFailure;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutCompletionEvidenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutMissedEvidenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery.Occurrence;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleCalendarReadPort.PlanSummary;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleCalendarReadPort.SessionSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class F05ContextBuilderUnitTest {

    private CurrentCoachingContextQuery coachingContextQuery;
    private UserAccountStatusQuery accountStatusQuery;
    private WorkoutScheduleCalendarQuery calendarQuery;
    private WorkoutMissedEvidenceQuery missedEvidence;
    private ProgressEngineQuery progressEngineQuery;
    private WorkoutCompletionEvidenceQuery completionEvidenceQuery;
    private CoachingAuthorityQuery coachingAuthorityQuery;
    private GetStudentProfileUseCase studentProfileUseCase;
    private Clock clock;

    private ObjectProvider<WorkoutScheduleCalendarQuery> calendarProvider;
    private ObjectProvider<ProgressEngineQuery> progressEngineProvider;
    private ObjectProvider<WorkoutCompletionEvidenceQuery> completionProvider;
    private ObjectProvider<CoachingAuthorityQuery> authorityProvider;
    private ObjectProvider<GetStudentProfileUseCase> studentProfileProvider;

    private F05ContextBuilder contextBuilder;

    private final UUID studentId = UUID.randomUUID();
    private final UUID trainerId = UUID.randomUUID();
    private final UUID occurrenceId = UUID.randomUUID();
    private final Instant fixedNow = Instant.parse("2026-11-20T10:00:00Z");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        coachingContextQuery = mock(CurrentCoachingContextQuery.class);
        accountStatusQuery = mock(UserAccountStatusQuery.class);
        calendarQuery = mock(WorkoutScheduleCalendarQuery.class);
        missedEvidence = mock(WorkoutMissedEvidenceQuery.class);
        progressEngineQuery = mock(ProgressEngineQuery.class);
        completionEvidenceQuery = mock(WorkoutCompletionEvidenceQuery.class);
        coachingAuthorityQuery = mock(CoachingAuthorityQuery.class);
        studentProfileUseCase = mock(GetStudentProfileUseCase.class);

        calendarProvider = mock(ObjectProvider.class);
        when(calendarProvider.getIfAvailable()).thenReturn(calendarQuery);

        progressEngineProvider = mock(ObjectProvider.class);
        when(progressEngineProvider.getIfAvailable()).thenReturn(progressEngineQuery);

        completionProvider = mock(ObjectProvider.class);
        when(completionProvider.getIfAvailable()).thenReturn(completionEvidenceQuery);

        authorityProvider = mock(ObjectProvider.class);
        when(authorityProvider.getIfAvailable()).thenReturn(coachingAuthorityQuery);

        studentProfileProvider = mock(ObjectProvider.class);
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileUseCase);

        clock = Clock.fixed(fixedNow, ZoneId.of("UTC"));

        contextBuilder = new F05ContextBuilder(
                coachingContextQuery,
                accountStatusQuery,
                calendarProvider,
                clock,
                missedEvidence,
                progressEngineProvider,
                completionProvider,
                authorityProvider,
                studentProfileProvider
        );

        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
    }

    private Occurrence createScheduledOccurrence() {
        return new Occurrence(
                occurrenceId,
                0L,
                studentId,
                "SCHEDULED",
                fixedNow.plusSeconds(3600),
                fixedNow.plusSeconds(3600),
                null,
                null,
                fixedNow.plusSeconds(7200),
                "Asia/Ho_Chi_Minh",
                "2026-11-20T17:00:00",
                "2026-11-20T18:00:00",
                "+07:00",
                "+07:00",
                new PlanSummary(UUID.randomUUID(), UUID.randomUUID(), 1, "Hypertrophy"),
                new SessionSummary(UUID.randomUUID(), 1, 1, 1, "Full Body Strength", "HYPERTROPHY", 60, "SELF_PERFORMABLE"),
                List.of(),
                null,
                null
        );
    }

    private WorkoutMissedEvidenceQuery.Evidence createMissedEvidence() {
        return new WorkoutMissedEvidenceQuery.Evidence(
                occurrenceId,
                studentId,
                0L,
                fixedNow.plusSeconds(3600),
                fixedNow.plusSeconds(7200),
                true
        );
    }

    @Test
    @DisplayName("F05-BL-01: Continuity with recent workout maps to RECENT_ACTIVITY_OBSERVED, never RECENT_CONSISTENT")
    void buildContext_recentWorkout_mapsToRecentActivityObserved() {
        Occurrence occ = createScheduledOccurrence();
        when(calendarQuery.detail(studentId, occurrenceId)).thenReturn(occ);
        when(missedEvidence.find(occurrenceId)).thenReturn(Optional.of(createMissedEvidence()));

        ContinuitySignal signal = new ContinuitySignal(
                ContinuityState.RECENT_ACTIVITY_OBSERVED,
                4,
                LocalDate.of(2026, 11, 16),
                UUID.randomUUID(),
                List.of(),
                "Recent completed workout recorded"
        );
        ProgressSnapshot snapshot = mock(ProgressSnapshot.class);
        when(snapshot.continuity()).thenReturn(signal);
        when(snapshot.compositeWatermark()).thenReturn("watermark-hash-123");
        when(progressEngineQuery.query(eq(studentId), eq(studentId), eq((Set<String>) null), any(), any(), any(), eq(fixedNow)))
                .thenReturn(snapshot);

        WorkoutRescheduleApiRequest request = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, null, "Schedule conflict", "MORNING");
        var result = contextBuilder.buildContextEnvelope(studentId, request);

        assertThat(result.sourceSnapshot().continuityState())
                .isEqualTo(TrainingContinuityState.RECENT_ACTIVITY_OBSERVED);
        assertThat(result.sourceSnapshot().daysSinceLastWorkout()).isEqualTo(4);
        assertThat(result.sourceSnapshot().continuityWatermark()).isEqualTo("watermark-hash-123");
        assertThat(result.serviceRequest().continuityState())
                .isEqualTo("RECENT_ACTIVITY_OBSERVED");
    }

    @Test
    @DisplayName("F05-BL-01: Inactivity >= 100 days maps to PROLONGED_INACTIVITY")
    void buildContext_prolongedInactivity_mapsToProlongedInactivity() {
        Occurrence occ = createScheduledOccurrence();
        when(calendarQuery.detail(studentId, occurrenceId)).thenReturn(occ);
        when(missedEvidence.find(occurrenceId)).thenReturn(Optional.of(createMissedEvidence()));

        ContinuitySignal signal = new ContinuitySignal(
                ContinuityState.PROLONGED_INACTIVITY,
                120,
                LocalDate.of(2026, 7, 23),
                UUID.randomUUID(),
                List.of("RETURN_AFTER_PROLONGED_INACTIVITY"),
                "Prolonged inactivity detected"
        );
        ProgressSnapshot snapshot = mock(ProgressSnapshot.class);
        when(snapshot.continuity()).thenReturn(signal);
        when(snapshot.compositeWatermark()).thenReturn("watermark-hash-456");
        when(progressEngineQuery.query(eq(studentId), eq(studentId), eq((Set<String>) null), any(), any(), any(), eq(fixedNow)))
                .thenReturn(snapshot);

        WorkoutRescheduleApiRequest request = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, null, "Returning after break", "AFTERNOON");
        var result = contextBuilder.buildContextEnvelope(studentId, request);

        assertThat(result.sourceSnapshot().continuityState())
                .isEqualTo(TrainingContinuityState.PROLONGED_INACTIVITY);
        assertThat(result.sourceSnapshot().daysSinceLastWorkout()).isEqualTo(120);
        assertThat(result.sourceSnapshot().continuityWatermark()).isEqualTo("watermark-hash-456");
        assertThat(result.serviceRequest().continuityState())
                .isEqualTo("PROLONGED_INACTIVITY");
    }

    @Test
    @DisplayName("F05-BL-01: Confirmed beginner onboarding maps to CONFIRMED_NEW")
    void buildContext_confirmedBeginner_mapsToConfirmedNew() {
        Occurrence occ = createScheduledOccurrence();
        when(calendarQuery.detail(studentId, occurrenceId)).thenReturn(occ);
        when(missedEvidence.find(occurrenceId)).thenReturn(Optional.of(createMissedEvidence()));

        ContinuitySignal signal = new ContinuitySignal(
                ContinuityState.CONFIRMED_NEW,
                null,
                null,
                null,
                List.of(),
                "Confirmed beginner onboarding with 0 months experience"
        );
        ProgressSnapshot snapshot = mock(ProgressSnapshot.class);
        when(snapshot.continuity()).thenReturn(signal);
        when(progressEngineQuery.query(eq(studentId), eq(studentId), eq((Set<String>) null), any(), any(), any(), eq(fixedNow)))
                .thenReturn(snapshot);

        WorkoutRescheduleApiRequest request = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, null, "New trainee first reschedule", null);
        var result = contextBuilder.buildContextEnvelope(studentId, request);

        assertThat(result.sourceSnapshot().continuityState())
                .isEqualTo(TrainingContinuityState.CONFIRMED_NEW);
        assertThat(result.serviceRequest().continuityState())
                .isEqualTo("CONFIRMED_NEW");
    }

    @Test
    @DisplayName("F05-BL-01: Coach access restricted maps to RESTRICTED_HISTORY")
    void buildContext_coachRestricted_mapsToRestrictedHistory() {
        Occurrence occ = createScheduledOccurrence();
        when(calendarQuery.detail(trainerId, occurrenceId)).thenReturn(occ);
        when(missedEvidence.find(occurrenceId)).thenReturn(Optional.of(createMissedEvidence()));

        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(Optional.of(
                new CurrentCoachingContext(UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, Instant.now(), null)
        ));

        when(progressEngineQuery.query(eq(trainerId), eq(studentId), eq((Set<String>) null), any(), any(), any(), eq(fixedNow)))
                .thenThrow(new ProgressFailure(ProgressError.PROGRESS_ACCESS_DENIED, "Access denied to progress"));

        WorkoutRescheduleApiRequest request = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, null, "Coach adjustment", null);
        var result = contextBuilder.buildContextEnvelope(trainerId, request);

        assertThat(result.sourceSnapshot().continuityState())
                .isEqualTo(TrainingContinuityState.RESTRICTED_HISTORY);
        assertThat(result.serviceRequest().continuityState())
                .isEqualTo("RESTRICTED_HISTORY");
        assertThat(result.decisionAuthority()).isEqualTo(AiDecisionAuthority.TRAINER);
    }

    @Test
    @DisplayName("F05-BL-01: Missing workout history without confirmed beginner evidence maps to UNKNOWN_HISTORY")
    void buildContext_missingHistory_mapsToUnknownHistory() {
        Occurrence occ = createScheduledOccurrence();
        when(calendarQuery.detail(studentId, occurrenceId)).thenReturn(occ);
        when(missedEvidence.find(occurrenceId)).thenReturn(Optional.of(createMissedEvidence()));

        ContinuitySignal signal = new ContinuitySignal(
                ContinuityState.UNKNOWN_HISTORY,
                null,
                null,
                null,
                List.of(),
                "Zero workout logs recorded but unconfirmed beginner"
        );
        ProgressSnapshot snapshot = mock(ProgressSnapshot.class);
        when(snapshot.continuity()).thenReturn(signal);
        when(progressEngineQuery.query(eq(studentId), eq(studentId), eq((Set<String>) null), any(), any(), any(), eq(fixedNow)))
                .thenReturn(snapshot);

        WorkoutRescheduleApiRequest request = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, null, "Reschedule", null);
        var result = contextBuilder.buildContextEnvelope(studentId, request);

        assertThat(result.sourceSnapshot().continuityState())
                .isEqualTo(TrainingContinuityState.UNKNOWN_HISTORY);
        assertThat(result.serviceRequest().continuityState())
                .isEqualTo("UNKNOWN_HISTORY");
    }

    @Test
    @DisplayName("F05-BL-01: Fallback resolution via WorkoutCompletionEvidenceQuery when ProgressEngineQuery unavailable")
    void buildContext_fallbackCompletionEvidence_resolvesCorrectly() {
        when(progressEngineProvider.getIfAvailable()).thenReturn(null);

        Occurrence occ = createScheduledOccurrence();
        when(calendarQuery.detail(studentId, occurrenceId)).thenReturn(occ);
        when(missedEvidence.find(occurrenceId)).thenReturn(Optional.of(createMissedEvidence()));

        WorkoutCompletionEvidenceQuery.Completion recentCompletion = new WorkoutCompletionEvidenceQuery.Completion(
                UUID.randomUUID(),
                studentId,
                1L,
                fixedNow.minusSeconds(86400 * 3), // 3 days ago
                fixedNow.minusSeconds(86400 * 3 - 3600)
        );
        when(completionEvidenceQuery.completedForStudent(studentId)).thenReturn(List.of(recentCompletion));

        WorkoutRescheduleApiRequest request = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, null, "Reschedule", null);
        var result = contextBuilder.buildContextEnvelope(studentId, request);

        assertThat(result.sourceSnapshot().continuityState())
                .isEqualTo(TrainingContinuityState.RECENT_ACTIVITY_OBSERVED);
        assertThat(result.sourceSnapshot().daysSinceLastWorkout()).isEqualTo(3);
        assertThat(result.sourceSnapshot().continuityWatermark()).isNull();
        assertThat(result.serviceRequest().continuityState())
                .isEqualTo("RECENT_ACTIVITY_OBSERVED");
    }

    @Test
    @DisplayName("F05-BL-01: Fallback resolution detects prolonged inactivity >= 100 days")
    void buildContext_fallbackProlongedInactivity_resolvesCorrectly() {
        when(progressEngineProvider.getIfAvailable()).thenReturn(null);

        Occurrence occ = createScheduledOccurrence();
        when(calendarQuery.detail(studentId, occurrenceId)).thenReturn(occ);
        when(missedEvidence.find(occurrenceId)).thenReturn(Optional.of(createMissedEvidence()));

        WorkoutCompletionEvidenceQuery.Completion oldCompletion = new WorkoutCompletionEvidenceQuery.Completion(
                UUID.randomUUID(),
                studentId,
                1L,
                fixedNow.minusSeconds(86400L * 120), // 120 days ago
                fixedNow.minusSeconds(86400L * 120 - 3600)
        );
        when(completionEvidenceQuery.completedForStudent(studentId)).thenReturn(List.of(oldCompletion));

        WorkoutRescheduleApiRequest request = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, null, "Reschedule", null);
        var result = contextBuilder.buildContextEnvelope(studentId, request);

        assertThat(result.sourceSnapshot().continuityState())
                .isEqualTo(TrainingContinuityState.PROLONGED_INACTIVITY);
        assertThat(result.sourceSnapshot().daysSinceLastWorkout()).isEqualTo(120);
        assertThat(result.sourceSnapshot().continuityWatermark()).isNull();
        assertThat(result.serviceRequest().continuityState())
                .isEqualTo("PROLONGED_INACTIVITY");
    }

    @Test
    @DisplayName("F05-BL-01: Fallback resolution with no providers defaults safe fail-closed to UNKNOWN_HISTORY")
    void buildContext_noProviders_defaultsToUnknownHistory() {
        when(progressEngineProvider.getIfAvailable()).thenReturn(null);
        when(completionProvider.getIfAvailable()).thenReturn(null);

        Occurrence occ = createScheduledOccurrence();
        when(calendarQuery.detail(studentId, occurrenceId)).thenReturn(occ);
        when(missedEvidence.find(occurrenceId)).thenReturn(Optional.of(createMissedEvidence()));

        WorkoutRescheduleApiRequest request = new WorkoutRescheduleApiRequest(studentId, occurrenceId, 0L, null, "Reschedule", null);
        var result = contextBuilder.buildContextEnvelope(studentId, request);

        assertThat(result.sourceSnapshot().continuityState())
                .isEqualTo(TrainingContinuityState.UNKNOWN_HISTORY);
        assertThat(result.serviceRequest().continuityState())
                .isEqualTo("UNKNOWN_HISTORY");
    }
}
