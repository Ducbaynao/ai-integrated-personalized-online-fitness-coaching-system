package com.fitnesscoaching.platform.modules.goal.application.model;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;

import java.util.List;

public record GoalVersionPage(
        List<FitnessGoalVersion> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public GoalVersionPage {
        if (items == null) {
            items = List.of();
        }
    }
}
