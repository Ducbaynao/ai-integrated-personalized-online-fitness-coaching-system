package com.fitnesscoaching.platform.modules.workout.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkoutExecution(
        UUID id, UUID studentId, UUID plannedWorkoutId, UUID planVersionId, UUID planSessionId,
        UUID coachingPeriodId, Long frozenOccurrenceVersion, String snapshotMode, Instant performedStartedAt,
        Instant performedEndedAt, WorkoutExecutionStatus status, BigDecimal overallRpe,
        String sessionNote, long version, List<ExerciseExecution> exercises
) {
    public WorkoutExecution {
        if (id == null || studentId == null || performedStartedAt == null || status == null
                || snapshotMode == null || version < 0) throw invalid();
        if ("FROZEN".equals(snapshotMode) && (plannedWorkoutId == null || planVersionId == null
                || planSessionId == null || frozenOccurrenceVersion == null || frozenOccurrenceVersion < 0))
            throw invalid();
        if (!"FROZEN".equals(snapshotMode) && !"LEGACY_REFERENCE_ONLY".equals(snapshotMode)) throw invalid();
        if ((status == WorkoutExecutionStatus.IN_PROGRESS) != (performedEndedAt == null)) throw invalid();
        if (overallRpe != null && (overallRpe.compareTo(BigDecimal.ONE) < 0
                || overallRpe.compareTo(BigDecimal.TEN) > 0)) throw invalid();
        exercises = exercises == null ? List.of() : List.copyOf(exercises);
    }

    public WorkoutExecutionStatus deriveCompletion() {
        requireInProgress();
        int required = exercises.stream().mapToInt(ExerciseExecution::baselineSetCount).sum();
        if (required < 1) throw new WorkoutExecutionFailure(
                WorkoutExecutionError.WORKOUT_EXECUTION_BASELINE_INVALID, "Execution has no baseline sets");
        long completed = exercises.stream().flatMap(e -> e.sets().stream())
                .filter(SetExecution::baseline).filter(SetExecution::completed).count();
        if (completed == required) return WorkoutExecutionStatus.COMPLETED;
        if (completed > 0) return WorkoutExecutionStatus.PARTIALLY_COMPLETED;
        throw new WorkoutExecutionFailure(WorkoutExecutionError.WORKOUT_EXECUTION_LIFECYCLE_CONFLICT,
                "Complete requires at least one completed baseline set");
    }

    public void requireAbortable() {
        requireInProgress();
        boolean any = exercises.stream().flatMap(e -> e.sets().stream())
                .anyMatch(s -> s.baseline() && s.completed());
        if (any) throw new WorkoutExecutionFailure(WorkoutExecutionError.WORKOUT_EXECUTION_LIFECYCLE_CONFLICT,
                "Abort requires zero completed baseline sets");
    }

    public void requireInProgress() {
        if (status != WorkoutExecutionStatus.IN_PROGRESS)
            throw new WorkoutExecutionFailure(WorkoutExecutionError.WORKOUT_EXECUTION_TERMINAL,
                    "Terminal workout execution is immutable");
    }

    private static WorkoutExecutionFailure invalid() {
        return new WorkoutExecutionFailure(WorkoutExecutionError.VALIDATION_FAILED, "Invalid workout execution");
    }
}
