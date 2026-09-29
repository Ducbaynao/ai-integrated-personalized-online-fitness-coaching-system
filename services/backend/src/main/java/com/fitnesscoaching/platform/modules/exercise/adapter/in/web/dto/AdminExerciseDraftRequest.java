package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseDraftData;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseEquipment;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseMuscle;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseVariation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AdminExerciseDraftRequest(
        @NotBlank @Size(max = 100) String code,
        @NotBlank @Size(max = 180) String name,
        @Size(max = 60) String categoryCode,
        String description,
        String instructions,
        String difficulty,
        @Size(max = 60) String movementPattern,
        boolean unilateral,
        @Size(max = 100) List<@NotBlank @Size(max = 80) String> tagCodes,
        @Size(max = 100) List<@Valid VariationRequest> variations
) {
    public ExerciseDraftData toDomain() {
        return new ExerciseDraftData(
                code, name, categoryCode, description, instructions, difficulty, movementPattern, unilateral,
                tagCodes == null ? List.of() : tagCodes,
                variations == null ? List.of() : variations.stream().map(VariationRequest::toDomain).toList()
        );
    }

    public record VariationRequest(
            @NotBlank @Size(max = 120) String code,
            @NotBlank @Size(max = 180) String name,
            String description,
            String instructions,
            String difficulty,
            boolean defaultVariation,
            boolean active,
            @Size(max = 100) List<@Valid MuscleRequest> muscles,
            @Size(max = 100) List<@Valid EquipmentRequest> equipment
    ) {
        AdminExerciseVariation toDomain() {
            return new AdminExerciseVariation(
                    null, code, name, description, instructions, difficulty, defaultVariation, active,
                    muscles == null ? List.of() : muscles.stream().map(MuscleRequest::toDomain).toList(),
                    equipment == null ? List.of() : equipment.stream().map(EquipmentRequest::toDomain).toList()
            );
        }
    }

    public record MuscleRequest(
            @NotBlank @Size(max = 60) String muscleGroupCode,
            @NotBlank String involvement
    ) {
        AdminExerciseMuscle toDomain() {
            return new AdminExerciseMuscle(muscleGroupCode, involvement);
        }
    }

    public record EquipmentRequest(
            @NotBlank @Size(max = 60) String equipmentCode,
            @NotBlank String requirement
    ) {
        AdminExerciseEquipment toDomain() {
            return new AdminExerciseEquipment(equipmentCode, requirement);
        }
    }
}
