package com.fitnesscoaching.platform.modules.exercise.application.model;

import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseSummary;

import java.util.List;

public record AdminExercisePage(
        List<AdminExerciseSummary> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public AdminExercisePage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
