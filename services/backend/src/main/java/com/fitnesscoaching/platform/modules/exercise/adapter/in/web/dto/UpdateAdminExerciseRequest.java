package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateAdminExerciseRequest(
        @Min(0) long expectedVersion,
        @NotNull @Valid AdminExerciseDraftRequest exercise
) {
}
