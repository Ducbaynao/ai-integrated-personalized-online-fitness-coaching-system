package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.common.exception.DataSharingPermissionRequiredException;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.*;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkoutPlanAccessPolicyTest {
    @Test void historicalReadDelegatesResourceTimestampAndHalfOpenWindowDecisionToCoaching() {
        CoachingAuthorityQuery authority = mock(CoachingAuthorityQuery.class);
        WorkoutPlanAccessPolicy policy = new WorkoutPlanAccessPolicy(authority);
        UUID trainer = UUID.randomUUID(), student = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-07T10:00:00Z");
        when(authority.evaluateHistorical(new HistoricalAuthorityRequest(trainer, student,
                DataScope.WORKOUT_PLAN_HISTORY, DataAccessLevel.VIEW, occurredAt)))
                .thenReturn(AuthorityDecision.denied(AuthorityReason.RESOURCE_OUTSIDE_HISTORY_WINDOW));

        assertThatThrownBy(() -> policy.verifyHistoricalTrainer(trainer, student, occurredAt))
                .isInstanceOf(DataSharingPermissionRequiredException.class);
        verify(authority).evaluateHistorical(new HistoricalAuthorityRequest(trainer, student,
                DataScope.WORKOUT_PLAN_HISTORY, DataAccessLevel.VIEW, occurredAt));
    }

    @Test void currentMutationUsesWorkoutPlanManage() {
        CoachingAuthorityQuery authority = mock(CoachingAuthorityQuery.class);
        WorkoutPlanAccessPolicy policy = new WorkoutPlanAccessPolicy(authority);
        UUID trainer = UUID.randomUUID(), student = UUID.randomUUID();
        when(authority.evaluateCurrent(new AuthorityRequest(trainer, student,
                DataScope.WORKOUT_PLAN, DataAccessLevel.MANAGE)))
                .thenReturn(new AuthorityDecision(true, AuthorityReason.ALLOWED, null, 0,
                        null, null, 0, DataAccessLevel.MANAGE, null, null));

        policy.verifyCurrentTrainer(trainer, student, DataAccessLevel.MANAGE);
        verify(authority).evaluateCurrent(new AuthorityRequest(trainer, student,
                DataScope.WORKOUT_PLAN, DataAccessLevel.MANAGE));
    }
}
