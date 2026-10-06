package com.fitnesscoaching.platform.modules.coaching.adapter.out;

import com.fitnesscoaching.platform.common.exception.*;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalTrainerAuthorityPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CoachingProposalAuthorityAdapter implements GoalTrainerAuthorityPort {

    private final CoachingAuthorityQuery coachingAuthorityQuery;

    public CoachingProposalAuthorityAdapter(CoachingAuthorityQuery coachingAuthorityQuery) {
        this.coachingAuthorityQuery = coachingAuthorityQuery;
    }

    @Override
    public void verifyTrainerCanProposeGoal(UUID trainerId, UUID studentId) {
        verifyTrainerAuthority(trainerId, studentId, DataAccessLevel.CONTRIBUTE);
    }

    @Override
    public void verifyTrainerCanViewProposal(UUID trainerId, UUID studentId) {
        verifyTrainerAuthority(trainerId, studentId, DataAccessLevel.VIEW);
    }

    private void verifyTrainerAuthority(UUID trainerId, UUID studentId, DataAccessLevel requiredLevel) {
        AuthorityDecision decision = coachingAuthorityQuery.evaluateCurrent(
                new AuthorityRequest(trainerId, studentId, DataScope.FITNESS_GOAL, requiredLevel));
        if (decision.allowed()) {
            return;
        }
        switch (decision.reason()) {
            case USER_NOT_FOUND -> throw new UserNotFoundException("Trainer account was not found.");
            case ACCOUNT_UNAVAILABLE -> throw new AccountUnavailableException("Trainer account is unavailable.");
            case TRAINER_CAPABILITY_UNAVAILABLE, INVALID_REQUEST ->
                    throw new TrainerCapabilityUnavailableException("Trainer capability is unavailable.");
            case TRAINER_NOT_ELIGIBLE -> throw new TrainerNotEligibleException("Trainer is not eligible to coach.");
            case RELATIONSHIP_NOT_ACTIVE, PERIOD_NOT_EFFECTIVE ->
                    throw new CoachingRelationshipRequiredException(
                            "An active coaching relationship and HUMAN_COACH period are required.");
            case ACCESS_LEVEL_INSUFFICIENT -> throw new DataSharingAccessLevelInsufficientException(
                    "The FITNESS_GOAL sharing level is insufficient for this operation.");
            case PERMISSION_NOT_GRANTED, HISTORY_WINDOW_NOT_GRANTED, RESOURCE_OUTSIDE_HISTORY_WINDOW ->
                    throw new DataSharingPermissionRequiredException(
                            "Active FITNESS_GOAL data sharing permission is required.");
            case ALLOWED -> throw new IllegalStateException("Allowed authority decision cannot be rejected.");
        }
    }
}
