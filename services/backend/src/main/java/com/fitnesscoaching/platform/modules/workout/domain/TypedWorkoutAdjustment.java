package com.fitnesscoaching.platform.modules.workout.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record TypedWorkoutAdjustment(
        WorkoutSessionAdjustment.Type type, UUID prescriptionId, UUID replacementVariationId,
        BigDecimal targetLoad, Short loadUnitId, Integer repsMin, Integer repsMax,
        Integer targetSets, Integer durationSeconds, Integer sequence, String note
) {}
