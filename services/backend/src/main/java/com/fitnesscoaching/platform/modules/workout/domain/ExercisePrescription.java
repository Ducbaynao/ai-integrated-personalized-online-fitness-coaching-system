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
            throw invalid();
        if ((targetSets != null && targetSets <= 0)
                || (targetRepsMin != null && targetRepsMin < 0)
                || (targetRepsMax != null && targetRepsMax < 0)
                || (targetLoad != null && targetLoad.signum() < 0)
                || (restSeconds != null && restSeconds < 0)
                || (durationSeconds != null && durationSeconds < 0))
            throw invalid();
        if (targetRepsMin != null && targetRepsMax != null && targetRepsMax < targetRepsMin)
            throw invalid();
    }

    private static WorkoutPlanFailure invalid() {
        return new WorkoutPlanFailure(WorkoutPlanError.VALIDATION_FAILED, "Invalid exercise prescription");
    }
}
