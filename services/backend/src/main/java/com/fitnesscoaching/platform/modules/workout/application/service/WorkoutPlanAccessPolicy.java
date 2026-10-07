package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.common.exception.*;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.HistoricalAuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class WorkoutPlanAccessPolicy {
    private final CoachingAuthorityQuery authority;
    public WorkoutPlanAccessPolicy(CoachingAuthorityQuery authority) { this.authority = authority; }

    public void verifyCurrentTrainer(UUID actorId, UUID studentId, DataAccessLevel level) {
        requireAllowed(authority.evaluateCurrent(new AuthorityRequest(
                actorId, studentId, DataScope.WORKOUT_PLAN, level)));
    }

    public void verifyHistoricalTrainer(UUID actorId, UUID studentId, Instant resourceOccurredAt) {
        requireAllowed(authority.evaluateHistorical(new HistoricalAuthorityRequest(
                actorId, studentId, DataScope.WORKOUT_PLAN_HISTORY, DataAccessLevel.VIEW, resourceOccurredAt)));
    }

    private static void requireAllowed(AuthorityDecision decision) {
        if (decision.allowed()) return;
        switch (decision.reason()) {
            case USER_NOT_FOUND -> throw new UserNotFoundException("Trainer account was not found.");
            case ACCOUNT_UNAVAILABLE -> throw new AccountUnavailableException("Trainer account is unavailable.");
            case TRAINER_CAPABILITY_UNAVAILABLE, INVALID_REQUEST ->
                    throw new TrainerCapabilityUnavailableException("Trainer capability is unavailable.");
            case TRAINER_NOT_ELIGIBLE -> throw new TrainerNotEligibleException("Trainer is not eligible to coach.");
            case RELATIONSHIP_NOT_ACTIVE, PERIOD_NOT_EFFECTIVE ->
                    throw new CoachingRelationshipRequiredException(
                            "An active coaching relationship and effective HUMAN_COACH period are required.");
            case ACCESS_LEVEL_INSUFFICIENT -> throw new DataSharingAccessLevelInsufficientException(
                    "The Workout Plan sharing level is insufficient for this operation.");
            case PERMISSION_NOT_GRANTED, HISTORY_WINDOW_NOT_GRANTED, RESOURCE_OUTSIDE_HISTORY_WINDOW ->
                    throw new DataSharingPermissionRequiredException(
                            "Active Workout Plan data sharing permission and history window are required.");
            case ALLOWED -> throw new IllegalStateException("Allowed authority decision cannot be rejected.");
        }
    }
}
