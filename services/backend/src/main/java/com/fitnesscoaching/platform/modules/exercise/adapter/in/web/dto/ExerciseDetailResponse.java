package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseCatalogDetail;

import java.util.List;
import java.util.UUID;

public record ExerciseDetailResponse(
        UUID id,
        String code,
        String name,
        CatalogOptionResponse category,
        String description,
        String instructions,
        String difficulty,
        String movementPattern,
        boolean unilateral,
        List<ExerciseVariationResponse> variations,
        List<CatalogOptionResponse> tags,
        List<ExerciseGuidanceResponse> guidance,
        boolean mediaAvailable
) {
    public static ExerciseDetailResponse fromDomain(ExerciseCatalogDetail detail) {
        return new ExerciseDetailResponse(
                detail.id(),
                detail.code(),
                detail.name(),
                CatalogOptionResponse.fromDomain(detail.category()),
                detail.description(),
                detail.instructions(),
                detail.difficulty(),
                detail.movementPattern(),
                detail.unilateral(),
                detail.variations().stream().map(ExerciseVariationResponse::fromDomain).toList(),
                detail.tags().stream().map(CatalogOptionResponse::fromDomain).toList(),
                detail.guidance().stream().map(ExerciseGuidanceResponse::fromDomain).toList(),
                detail.mediaAvailable()
        );
    }
}
