package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ArchiveExerciseRequest(
        @Min(0) long expectedVersion,
        @NotBlank @Size(max = 1000) String reason
) {
}
