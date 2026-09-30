package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.application.model.AdminExerciseFormMetadata;

import java.util.List;

public record AdminExerciseFormMetadataResponse(
        List<CatalogOptionResponse> categories,
        List<MuscleGroupOptionResponse> muscleGroups,
        List<CatalogOptionResponse> equipment,
        List<CatalogOptionResponse> tags,
        List<String> difficulties,
        List<CatalogOptionResponse> movementPatterns
) {
    public static AdminExerciseFormMetadataResponse fromDomain(AdminExerciseFormMetadata metadata) {
        return new AdminExerciseFormMetadataResponse(
                metadata.categories().stream().map(CatalogOptionResponse::fromDomain).toList(),
                metadata.muscleGroups().stream().map(MuscleGroupOptionResponse::fromDomain).toList(),
                metadata.equipment().stream().map(CatalogOptionResponse::fromDomain).toList(),
                metadata.tags().stream().map(CatalogOptionResponse::fromDomain).toList(),
                metadata.difficulties(),
                metadata.movementPatterns().stream().map(CatalogOptionResponse::fromDomain).toList()
        );
    }
}
