package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;

import java.time.Instant;
import java.util.UUID;

public record FitnessGoalResponse(
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
        GoalVersionResponse currentVersion
) {
    public static FitnessGoalResponse fromDomain(FitnessGoal goal) {
        if (goal == null) {
            return null;
        }
        return new FitnessGoalResponse(
                goal.id(),
                goal.studentId(),
                goal.title(),
                goal.status(),
                goal.createdBy(),
                goal.activatedAt(),
                goal.pausedAt(),
                goal.completedAt(),
                goal.endedAt(),
                goal.statusReason(),
                goal.createdAt(),
                goal.updatedAt(),
                GoalVersionResponse.fromDomain(goal.currentVersion())
        );
    }
}
