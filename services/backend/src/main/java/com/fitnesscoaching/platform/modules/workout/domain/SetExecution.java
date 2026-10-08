package com.fitnesscoaching.platform.modules.workout.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record SetExecution(
        UUID id, UUID clientSetId, Integer baselineSetNumber, int setNumber, String setType,
        String completionStatus, Integer repetitions, BigDecimal loadValue, Short loadUnitId,
        Integer durationSeconds, BigDecimal distanceValue, Short distanceUnitId,
        BigDecimal rpe, BigDecimal rir, String tempo, Integer restAfterSeconds, String note
) {
    private static final java.util.Set<String> SET_TYPES = java.util.Set.of(
            "WARMUP", "WORKING", "DROP", "FAILURE", "AMRAP", "COOLDOWN");
    private static final java.util.Set<String> COMPLETION_STATES = java.util.Set.of(
            "PLANNED", "COMPLETED", "SKIPPED", "FAILED");

    public SetExecution {
        if (clientSetId == null || setNumber < 1 || baselineSetNumber != null && baselineSetNumber < 1)
            throw invalid();
        if (!SET_TYPES.contains(setType) || !COMPLETION_STATES.contains(completionStatus)) throw invalid();
        if (repetitions != null && repetitions < 0 || loadValue != null && loadValue.signum() < 0
                || durationSeconds != null && durationSeconds < 0 || distanceValue != null && distanceValue.signum() < 0
                || restAfterSeconds != null && restAfterSeconds < 0) throw invalid();
        if (rpe != null && (rpe.compareTo(BigDecimal.ONE) < 0 || rpe.compareTo(BigDecimal.TEN) > 0)) throw invalid();
        if (rir != null && (rir.signum() < 0 || rir.compareTo(BigDecimal.TEN) > 0)) throw invalid();
    }
    public boolean baseline() { return baselineSetNumber != null; }
    public boolean completed() { return "COMPLETED".equals(completionStatus); }
    private static WorkoutExecutionFailure invalid() {
        return new WorkoutExecutionFailure(WorkoutExecutionError.VALIDATION_FAILED, "Invalid set execution");
    }
}
