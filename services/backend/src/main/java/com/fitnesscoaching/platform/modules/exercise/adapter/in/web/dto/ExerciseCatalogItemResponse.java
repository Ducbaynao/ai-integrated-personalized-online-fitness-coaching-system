package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseCatalogItem;

import java.util.List;
import java.util.UUID;

public record ExerciseCatalogItemResponse(
        UUID id,
        String code,
        String name,
        CatalogOptionResponse category,
        String description,
        String difficulty,
        String movementPattern,
        boolean unilateral,
        List<CatalogOptionResponse> primaryMuscles,
        List<CatalogOptionResponse> equipment,
        List<CatalogOptionResponse> tags,
        int variationCount,
        boolean mediaAvailable
) {
    public static ExerciseCatalogItemResponse fromDomain(ExerciseCatalogItem item) {
        return new ExerciseCatalogItemResponse(
                item.id(),
                item.code(),
                item.name(),
                CatalogOptionResponse.fromDomain(item.category()),
                item.description(),
                item.difficulty(),
                item.movementPattern(),
                item.unilateral(),
                item.primaryMuscles().stream().map(CatalogOptionResponse::fromDomain).toList(),
                item.equipment().stream().map(CatalogOptionResponse::fromDomain).toList(),
                item.tags().stream().map(CatalogOptionResponse::fromDomain).toList(),
                item.variationCount(),
                item.mediaAvailable()
        );
    }
}
