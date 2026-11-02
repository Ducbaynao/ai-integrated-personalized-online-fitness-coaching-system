package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.ChangeType;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetRef;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.CurrentCoachingContext;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.Mode;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutExecutionViews;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanSummary;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PrescriptionView;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.SessionView;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.VersionDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.VersionSummary;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutCompletionEvidenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleCalendarReadPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class F03ContextBuilderUnitTest {

    @Mock
    private CurrentCoachingContextQuery coachingContextQuery;
    @Mock
    private UserAccountStatusQuery accountStatusQuery;
    @Mock
    private ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider;
    @Mock
    private ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider;
    @Mock
    private ObjectProvider<ExerciseReferenceQuery> exerciseReferenceQueryProvider;
    @Mock
    private ObjectProvider<WorkoutExecutionQueryUseCase> workoutExecutionQueryProvider;
    @Mock
    private ObjectProvider<WorkoutCompletionEvidenceQuery> completionQueryProvider;

    @Mock
    private WorkoutScheduleCalendarQuery scheduleCalendarQuery;
    @Mock
    private WorkoutPlanQueryUseCase workoutPlanQueryUseCase;
    @Mock
    private ExerciseReferenceQuery exerciseReferenceQuery;
    @Mock
    private WorkoutExecutionQueryUseCase workoutExecutionQueryUseCase;
    @Mock
    private WorkoutCompletionEvidenceQuery completionEvidenceQuery;

    private F03ContextBuilder contextBuilder;
    private final Clock clock = Clock.systemUTC();

    private static final UUID STUDENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRAINER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PLAN_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OCCURRENCE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PRESCRIPTION_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID VARIATION_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");

    @BeforeEach
    void setUp() {
        contextBuilder = new F03ContextBuilder(
                coachingContextQuery,
                accountStatusQuery,
                scheduleCalendarProvider,
                workoutPlanQueryProvider,
                exerciseReferenceQueryProvider,
                workoutExecutionQueryProvider,
                completionQueryProvider,
                clock
        );
    }

    @Test
    @DisplayName("Inactive actor is denied access")
    void buildContext_inactiveActor_throwsAccessDenied() {
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

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

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(STUDENT_ID, STUDENT_ID, req))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Actor account is not active");
    }

    @Test
    @DisplayName("In HUMAN_COACH mode, student actor is denied access")
    void buildContext_humanCoach_studentActor_throwsAccessDenied() {
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
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

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(STUDENT_ID, STUDENT_ID, req))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("only the assigned coach may request a workout adjustment");
    }

    @Test
    @DisplayName("In SELF_DIRECTED mode, non-student actor is denied access")
    void buildContext_selfDirected_nonStudentActor_throwsAccessDenied() {
        when(accountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(coachingContextQuery.findEffectiveContext(STUDENT_ID, false)).thenReturn(Optional.empty());

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

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(STUDENT_ID, TRAINER_ID, req))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("only the student may request a workout adjustment");
    }

    @Test
    @DisplayName("Occurrence scope: builds valid context envelope and snapshot")
    void buildContext_occurrenceScope_extractsPrescriptionAndSnapshot() {
        when(accountStatusQuery.getAccountStatus(STUDENT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(coachingContextQuery.findEffectiveContext(STUDENT_ID, false)).thenReturn(Optional.empty());

        when(scheduleCalendarProvider.getIfAvailable()).thenReturn(scheduleCalendarQuery);
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQueryUseCase);

        UUID planVersionId = UUID.randomUUID();
        var planSummary = new WorkoutScheduleCalendarReadPort.PlanSummary(PLAN_ID, planVersionId, 1, "Plan 1");
        var sessionSummary = new WorkoutScheduleCalendarReadPort.SessionSummary(UUID.randomUUID(), 1, 1, 1, "Legs", "Hypertrophy", 45, null);

        var occ = new WorkoutScheduleCalendarQuery.Occurrence(
                OCCURRENCE_ID, 2L, STUDENT_ID, "SCHEDULED",
                Instant.now(), Instant.now(), null, null, Instant.now().plusSeconds(3600),
                "UTC", "10:00", "11:00", "+00:00", "+00:00", planSummary, sessionSummary,
                List.of(), null, null
        );
        when(scheduleCalendarQuery.detail(STUDENT_ID, OCCURRENCE_ID)).thenReturn(occ);

        PlanSummary fullPlanSummary = mock(PlanSummary.class);
        when(fullPlanSummary.aggregateVersion()).thenReturn(10L);
        PlanDetail planDetail = new PlanDetail(fullPlanSummary, null);
        when(workoutPlanQueryUseCase.detail(STUDENT_ID, PLAN_ID)).thenReturn(planDetail);

        PrescriptionView prescription = new PrescriptionView(
                PRESCRIPTION_ID, VARIATION_ID, 1, 3, 8, 12, BigDecimal.valueOf(50.0), 90, null, null, null
        );
        SessionView sessionView = new SessionView(
                UUID.randomUUID(), 1, 1, 1, "Legs", "Focus", 45, "Notes", null, List.of(prescription)
        );
        VersionDetail versionDetail = new VersionDetail(null, List.of(sessionView));
        when(workoutPlanQueryUseCase.version(STUDENT_ID, PLAN_ID, planVersionId)).thenReturn(versionDetail);

        var req = new WorkoutAdjustmentApiRequest(
                STUDENT_ID,
                "cmd-1",
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                new TargetRef(TargetScope.ONE_MATERIALIZED_OCCURRENCE, OCCURRENCE_ID, PRESCRIPTION_ID, null, null, null, null),
                ChangeType.LOAD_ADJUSTMENT,
                "I feel strong",
                List.of(),
                List.of(),
                true
        );

        var result = contextBuilder.buildContextEnvelope(STUDENT_ID, STUDENT_ID, req);

        assertThat(result).isNotNull();
        assertThat(result.sourceSnapshot().planAggregateVersion()).isEqualTo(10L);
        assertThat(result.sourceSnapshot().occurrenceVersion()).isEqualTo(2L);
        assertThat(result.sourceSnapshot().beforeExerciseVariationId()).isEqualTo(VARIATION_ID);
        assertThat(result.sourceSnapshot().beforeTargetLoad()).isEqualTo(BigDecimal.valueOf(50.0));
        // Since no execution history was mocked, allowLoadIncrease is safely false (no RPE evidence)
        assertThat(result.sourceSnapshot().allowLoadIncrease()).isFalse();
    }
}
