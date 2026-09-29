package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import jakarta.validation.constraints.Min;

public record ExerciseVersionRequest(@Min(0) long expectedVersion) {
}
