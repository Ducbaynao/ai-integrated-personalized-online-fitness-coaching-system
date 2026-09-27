package com.fitnesscoaching.platform.modules.goal.application.model;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;

import java.util.List;

public record FitnessGoalPage(
        List<FitnessGoal> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public FitnessGoalPage {
        if (items == null) {
            items = List.of();
        }
    }
}
