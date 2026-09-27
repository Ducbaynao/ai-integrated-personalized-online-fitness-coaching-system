package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseMuscle;

public record ExerciseMuscleResponse(String code, String name, String involvement) {

    public static ExerciseMuscleResponse fromDomain(ExerciseMuscle muscle) {
        return new ExerciseMuscleResponse(muscle.code(), muscle.name(), muscle.involvement());
    }
}
