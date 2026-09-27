package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseVariation;

import java.util.List;
import java.util.UUID;

public record ExerciseVariationResponse(
        UUID id,
        String code,
        String name,
        String description,
        String instructions,
        String difficulty,
        boolean defaultVariation,
        List<ExerciseMuscleResponse> muscles,
        List<ExerciseEquipmentResponse> equipment,
        List<ExerciseMediaReferenceResponse> media
) {
    public static ExerciseVariationResponse fromDomain(ExerciseVariation variation) {
        return new ExerciseVariationResponse(
                variation.id(),
                variation.code(),
                variation.name(),
                variation.description(),
                variation.instructions(),
                variation.difficulty(),
                variation.defaultVariation(),
                variation.muscles().stream().map(ExerciseMuscleResponse::fromDomain).toList(),
                variation.equipment().stream().map(ExerciseEquipmentResponse::fromDomain).toList(),
                variation.media().stream().map(ExerciseMediaReferenceResponse::fromDomain).toList()
        );
    }
}
