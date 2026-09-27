package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.application.model.ExerciseCatalogPage;

import java.util.List;

public record ExerciseCatalogPageResponse(
        List<ExerciseCatalogItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public static ExerciseCatalogPageResponse fromDomain(ExerciseCatalogPage page) {
        return new ExerciseCatalogPageResponse(
                page.items().stream().map(ExerciseCatalogItemResponse::fromDomain).toList(),
                page.page(),
                page.size(),
                page.totalItems(),
                page.totalPages()
        );
    }
}
