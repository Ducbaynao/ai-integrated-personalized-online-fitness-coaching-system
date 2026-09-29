package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CanonicalReplacementRequest(
        @Min(0) long expectedVersion,
        UUID targetExerciseId,
        @NotBlank @Size(max = 1000) String reason
) {
}
