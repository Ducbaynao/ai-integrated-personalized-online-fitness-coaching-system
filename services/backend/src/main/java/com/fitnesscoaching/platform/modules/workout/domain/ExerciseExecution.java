package com.fitnesscoaching.platform.modules.workout.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ExerciseExecution(
        UUID id, UUID sourcePrescriptionId, UUID prescribedVariationId, UUID actualVariationId,
        String substitutionReason, int sequenceNumber, int baselineSetCount,
        Integer targetRepsMin, Integer targetRepsMax, BigDecimal targetLoad, Short loadUnitId,
        BigDecimal targetRpe, BigDecimal targetRir, Integer restSeconds, String tempo,
        Integer durationSeconds, BigDecimal distanceValue, Short distanceUnitId,
        String instructions, String note, List<SetExecution> sets
) {
    public ExerciseExecution {
        if (id == null || sourcePrescriptionId == null || prescribedVariationId == null
                || actualVariationId == null || sequenceNumber < 1 || baselineSetCount < 1)
            throw new WorkoutExecutionFailure(WorkoutExecutionError.WORKOUT_EXECUTION_BASELINE_INVALID,
                    "Invalid exercise execution baseline");
        if (targetRepsMin != null && targetRepsMin < 0 || targetRepsMax != null && targetRepsMax < 0
                || targetRepsMin != null && targetRepsMax != null && targetRepsMax < targetRepsMin)
            throw new WorkoutExecutionFailure(WorkoutExecutionError.VALIDATION_FAILED, "Invalid repetition range");
        sets = sets == null ? List.of() : List.copyOf(sets);
    }
}
