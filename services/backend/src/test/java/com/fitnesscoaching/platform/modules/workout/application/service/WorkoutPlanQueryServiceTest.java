package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.common.exception.CoachingRelationshipRequiredException;
import com.fitnesscoaching.platform.common.exception.DataSharingPermissionRequiredException;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.*;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanQueryPort;
import com.fitnesscoaching.platform.modules.workout.domain.DecisionOwnerType;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkoutPlanQueryServiceTest {
    @Mock WorkoutPlanQueryPort store;
    @Mock CurrentCoachingContextQuery contexts;
    @Mock WorkoutPlanAccessPolicy access;
    @Mock ExerciseReferenceQuery exercises;
    WorkoutPlanQueryService service;
    UUID student; UUID trainer; UUID period; UUID planId; UUID versionId;

    @BeforeEach void setUp() {
        service = new WorkoutPlanQueryService(store, contexts, access, exercises);
        student = UUID.randomUUID(); trainer = UUID.randomUUID(); period = UUID.randomUUID();
        planId = UUID.randomUUID(); versionId = UUID.randomUUID();
    }

    @Test void studentReadsOwnCurrentPlanWithoutCoachingGrant() {
        when(store.findActiveByStudent(student)).thenReturn(Optional.of(plan(WorkoutPlanStatus.ACTIVE, period)));
        when(store.findVersions(planId)).thenReturn(List.of(version(true)));

        PlanDetail result = service.current(student, null);

        assertThat(result.plan().readContext()).isEqualTo(ReadContext.CURRENT);
        assertThat(result.currentVersion().readContext()).isEqualTo(ReadContext.CURRENT);
        verifyNoInteractions(access, contexts);
    }

    @Test void trainerCurrentReadRequiresViewAndMatchingCurrentPeriod() {
        when(contexts.findEffectiveContext(student, false)).thenReturn(Optional.of(context(period)));
        when(store.findPlan(planId)).thenReturn(Optional.of(plan(WorkoutPlanStatus.PAUSED, period)));
        when(store.findVersions(planId)).thenReturn(List.of(version(true)));

        PlanDetail result = service.detail(trainer, planId);

        assertThat(result.plan().readContext()).isEqualTo(ReadContext.CURRENT);
        verify(access).verifyCurrentTrainer(trainer, student, DataAccessLevel.VIEW);
        verify(access, never()).verifyHistoricalTrainer(any(), any(), any());
    }

    @Test void trainerHistoricalVersionRequiresHistoryViewAfterCurrentAuthority() {
        Instant occurredAt = Instant.parse("2026-09-01T00:00:00Z");
        VersionSummary historical = new VersionSummary(versionId, planId, 1, occurredAt,
                Instant.parse("2026-09-15T00:00:00Z"), "MAJOR", "UPDATE", null, trainer,
                occurredAt, occurredAt, false, null);
        when(store.findPlan(planId)).thenReturn(Optional.of(plan(WorkoutPlanStatus.ACTIVE, period)));
        when(contexts.findEffectiveContext(student, false)).thenReturn(Optional.of(context(period)));
        when(store.findVersions(planId)).thenReturn(List.of(historical));

        Page<VersionSummary> result = service.versions(trainer, planId, 0, 20);

        assertThat(result.items()).singleElement().extracting(VersionSummary::readContext)
                .isEqualTo(ReadContext.HISTORICAL);
        verify(access).verifyHistoricalTrainer(trainer, student, occurredAt);
    }

    @Test void missingHistoricalGrantConcealsHistoricalVersion() {
        Instant occurredAt = Instant.parse("2026-09-01T00:00:00Z");
        VersionSummary historical = new VersionSummary(versionId, planId, 1, occurredAt,
                Instant.parse("2026-09-15T00:00:00Z"), "MAJOR", "UPDATE", null, trainer,
                occurredAt, occurredAt, false, null);
        when(store.findPlan(planId)).thenReturn(Optional.of(plan(WorkoutPlanStatus.ACTIVE, period)));
        when(contexts.findEffectiveContext(student, false)).thenReturn(Optional.of(context(period)));
        when(store.findVersions(planId)).thenReturn(List.of(historical));
        doThrow(new DataSharingPermissionRequiredException("denied"))
                .when(access).verifyHistoricalTrainer(trainer, student, occurredAt);

        assertThat(service.versions(trainer, planId, 0, 20).items()).isEmpty();
    }

    @Test void formerTrainerCannotRecoverHistoryWhenCurrentAuthorityFails() {
        when(store.findPlan(planId)).thenReturn(Optional.of(plan(WorkoutPlanStatus.COMPLETED, period)));
        doThrow(new CoachingRelationshipRequiredException("ended"))
                .when(access).verifyCurrentTrainer(trainer, student, DataAccessLevel.VIEW);

        assertThatThrownBy(() -> service.detail(trainer, planId))
                .isInstanceOf(WorkoutPlanFailure.class)
                .extracting(ex -> ((WorkoutPlanFailure) ex).error())
                .isEqualTo(WorkoutPlanError.WORKOUT_PLAN_NOT_FOUND);
        verify(access, never()).verifyHistoricalTrainer(any(), any(), any());
    }

    private PlanSummary plan(WorkoutPlanStatus status, UUID coachingPeriod) {
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        return new PlanSummary(planId, student, null, coachingPeriod, "Plan", null, "TRAINER", status,
                2, DecisionOwnerType.TRAINER, trainer, null, null, at, at, null, versionId, 2, at, at, null);
    }

    private VersionSummary version(boolean current) {
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        return new VersionSummary(versionId, planId, 2, at, current ? null : at.plusSeconds(60),
                "MAJOR", "UPDATE", null, trainer, at, at, current, null);
    }

    private CurrentCoachingContextQuery.CurrentCoachingContext context(UUID coachingPeriod) {
        return new CurrentCoachingContextQuery.CurrentCoachingContext(coachingPeriod,
                CurrentCoachingContextQuery.Mode.HUMAN_COACH, UUID.randomUUID(), trainer,
                Instant.parse("2026-01-01T00:00:00Z"), null);
    }
}
