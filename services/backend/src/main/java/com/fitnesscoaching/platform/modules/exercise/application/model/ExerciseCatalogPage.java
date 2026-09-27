package com.fitnesscoaching.platform.modules.exercise.application.model;

import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseCatalogItem;

import java.util.List;

public record ExerciseCatalogPage(
        List<ExerciseCatalogItem> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public ExerciseCatalogPage {
        items = items != null ? List.copyOf(items) : List.of();
    }
}
