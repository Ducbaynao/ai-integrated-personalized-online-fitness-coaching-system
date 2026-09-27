package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseEquipment;

public record ExerciseEquipmentResponse(String code, String name, String requirement) {

    public static ExerciseEquipmentResponse fromDomain(ExerciseEquipment equipment) {
        return new ExerciseEquipmentResponse(equipment.code(), equipment.name(), equipment.requirement());
    }
}
