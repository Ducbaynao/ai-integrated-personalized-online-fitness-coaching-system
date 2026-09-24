package com.fitnesscoaching.platform.modules.goal.domain;

import java.time.Instant;
import java.util.UUID;

public record GoalTransition(
        UUID id,
        UUID previousGoalId,
        UUID newGoalId,
        String transitionReason,
        UUID proposalId,
        UUID initiatedBy,
        Instant transitionedAt,
        String notes
) {
}
