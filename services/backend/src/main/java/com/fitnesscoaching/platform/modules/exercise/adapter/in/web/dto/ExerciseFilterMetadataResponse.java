package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.application.model.ExerciseFilterMetadata;

import java.util.List;

public record ExerciseFilterMetadataResponse(
        List<CatalogOptionResponse> categories,
        List<MuscleGroupOptionResponse> muscleGroups,
        List<CatalogOptionResponse> equipment,
        List<CatalogOptionResponse> tags,
        List<String> difficulties,
        List<String> movementPatterns
) {
    public static ExerciseFilterMetadataResponse fromDomain(ExerciseFilterMetadata metadata) {
        return new ExerciseFilterMetadataResponse(
                metadata.categories().stream().map(CatalogOptionResponse::fromDomain).toList(),
                metadata.muscleGroups().stream().map(MuscleGroupOptionResponse::fromDomain).toList(),
                metadata.equipment().stream().map(CatalogOptionResponse::fromDomain).toList(),
                metadata.tags().stream().map(CatalogOptionResponse::fromDomain).toList(),
                metadata.difficulties(),
                metadata.movementPatterns()
        );
    }
}
