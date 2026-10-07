package com.fitnesscoaching.platform.modules.workout.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record ExercisePrescription(
        UUID id, UUID exerciseVariationId, int sequenceNumber,
        Integer targetSets, Integer targetRepsMin, Integer targetRepsMax,
        BigDecimal targetLoad, Integer restSeconds, Integer durationSeconds, String instructions
) {
    public ExercisePrescription {
        if (exerciseVariationId == null || sequenceNumber < 1)
            throw new IllegalArgumentException("Invalid exercise prescription");
        if (targetRepsMin != null && targetRepsMax != null && targetRepsMax < targetRepsMin)
            throw new IllegalArgumentException("Invalid repetition range");
    }
}
