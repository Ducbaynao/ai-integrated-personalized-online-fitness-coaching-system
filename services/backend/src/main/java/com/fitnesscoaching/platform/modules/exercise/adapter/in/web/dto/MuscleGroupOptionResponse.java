package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.domain.MuscleGroupOption;

public record MuscleGroupOptionResponse(String code, String name, String parentCode) {

    public static MuscleGroupOptionResponse fromDomain(MuscleGroupOption option) {
        return new MuscleGroupOptionResponse(option.code(), option.name(), option.parentCode());
    }
}
