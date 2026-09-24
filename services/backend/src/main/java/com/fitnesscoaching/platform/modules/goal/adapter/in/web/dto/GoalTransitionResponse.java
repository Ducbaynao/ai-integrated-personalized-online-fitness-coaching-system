package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.GoalTransition;

import java.time.Instant;
import java.util.UUID;

public record GoalTransitionResponse(
        UUID id,
        UUID previousGoalId,
        UUID newGoalId,
        String transitionReason,
        UUID proposalId,
        UUID initiatedBy,
        Instant transitionedAt,
        String notes,
        FitnessGoalResponse newGoal
) {
    public static GoalTransitionResponse fromDomain(GoalTransition domain) {
        return fromDomain(domain, null);
    }

    public static GoalTransitionResponse fromDomain(GoalTransition domain, FitnessGoal newGoal) {
        if (domain == null) {
            return null;
        }
        return new GoalTransitionResponse(
                domain.id(),
                domain.previousGoalId(),
                domain.newGoalId(),
                domain.transitionReason(),
                domain.proposalId(),
                domain.initiatedBy(),
                domain.transitionedAt(),
                domain.notes(),
                FitnessGoalResponse.fromDomain(newGoal)
        );
    }
}
