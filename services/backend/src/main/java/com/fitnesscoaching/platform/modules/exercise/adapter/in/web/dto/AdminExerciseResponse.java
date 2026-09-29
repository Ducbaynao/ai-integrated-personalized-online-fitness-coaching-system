package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.domain.AdminExercise;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseEquipment;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseMuscle;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseVariation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminExerciseResponse(
        UUID id,
        String code,
        String name,
        String categoryCode,
        String description,
        String instructions,
        String difficulty,
        String movementPattern,
        boolean unilateral,
        String status,
        long version,
        UUID canonicalReplacementId,
        List<String> tagCodes,
        List<VariationResponse> variations,
        Instant createdAt,
        Instant updatedAt
) {
    public static AdminExerciseResponse fromDomain(AdminExercise exercise) {
        return new AdminExerciseResponse(
                exercise.id(), exercise.code(), exercise.name(), exercise.categoryCode(), exercise.description(),
                exercise.instructions(), exercise.difficulty(), exercise.movementPattern(), exercise.unilateral(),
                exercise.status().name(), exercise.version(), exercise.canonicalReplacementId(), exercise.tagCodes(),
                exercise.variations().stream().map(VariationResponse::fromDomain).toList(),
                exercise.createdAt(), exercise.updatedAt()
        );
    }

    public record VariationResponse(
            UUID id, String code, String name, String description, String instructions, String difficulty,
            boolean defaultVariation, boolean active, List<MuscleResponse> muscles,
            List<EquipmentResponse> equipment
    ) {
        static VariationResponse fromDomain(AdminExerciseVariation variation) {
            return new VariationResponse(
                    variation.id(), variation.code(), variation.name(), variation.description(),
                    variation.instructions(), variation.difficulty(), variation.defaultVariation(), variation.active(),
                    variation.muscles().stream().map(MuscleResponse::fromDomain).toList(),
                    variation.equipment().stream().map(EquipmentResponse::fromDomain).toList()
            );
        }
    }

    public record MuscleResponse(String muscleGroupCode, String involvement) {
        static MuscleResponse fromDomain(AdminExerciseMuscle muscle) {
            return new MuscleResponse(muscle.muscleGroupCode(), muscle.involvement());
        }
    }

    public record EquipmentResponse(String equipmentCode, String requirement) {
        static EquipmentResponse fromDomain(AdminExerciseEquipment equipment) {
            return new EquipmentResponse(equipment.equipmentCode(), equipment.requirement());
        }
    }
}
