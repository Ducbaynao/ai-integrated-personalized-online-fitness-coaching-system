package com.fitnesscoaching.platform.modules.goal.domain;

import java.time.Instant;
import java.util.UUID;

public record FitnessGoal(
        UUID id,
        UUID studentId,
        String title,
        GoalStatus status,
        UUID createdBy,
        Instant activatedAt,
        Instant pausedAt,
        Instant completedAt,
        Instant endedAt,
        String statusReason,
        Instant createdAt,
        Instant updatedAt,
        FitnessGoalVersion currentVersion
) {
}
