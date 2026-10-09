package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class WorkoutExecutionDtos {
    private WorkoutExecutionDtos() {}

    enum SetType { WARMUP, WORKING, DROP, FAILURE, AMRAP, COOLDOWN }
    enum SetCompletionStatus { PLANNED, COMPLETED, SKIPPED, FAILED }

    record StartWorkoutExecutionRequest(
            @NotNull @PositiveOrZero Long expectedOccurrenceVersion,
            @NotBlank @Size(max = 120) String commandKey
    ) {}

    record SkipPlannedWorkoutRequest(
            @NotNull @PositiveOrZero Long expectedOccurrenceVersion,
            @NotBlank @Size(max = 120) String commandKey,
            String reason
    ) {}

    record UpsertWorkoutSetRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull UUID exerciseExecutionId,
            @Min(1) Integer baselineSetNumber,
            @Min(1) int setNumber,
            @NotNull SetType setType,
            @NotNull SetCompletionStatus completionStatus,
            @PositiveOrZero Integer repetitions,
            @DecimalMin("0") BigDecimal loadValue,
            Short loadUnitId,
            @PositiveOrZero Integer durationSeconds,
            @DecimalMin("0") BigDecimal distanceValue,
            Short distanceUnitId,
            @DecimalMin("1") @DecimalMax("10") BigDecimal rpe,
            @DecimalMin("0") @DecimalMax("10") BigDecimal rir,
            String tempo,
            @PositiveOrZero Integer restAfterSeconds,
            String note
    ) {}

    record SubstituteWorkoutExerciseRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull UUID actualExerciseVariationId,
            String substitutionReason
    ) {}

    record CompleteWorkoutExecutionRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 120) String commandKey,
            @DecimalMin("1") @DecimalMax("10") BigDecimal overallRpe,
            String sessionNote
    ) {}

    record AbortWorkoutExecutionRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 120) String commandKey,
            @DecimalMin("1") @DecimalMax("10") BigDecimal overallRpe,
            String sessionNote
    ) {}

    record WorkoutExecutionPage(List<WorkoutExecutionSummary> items, int page, int size,
                                long totalItems, int totalPages) {}

    record WorkoutExecutionSummary(
            UUID executionId, UUID studentId, UUID plannedWorkoutId, UUID planId, UUID planVersionId,
            UUID planSessionId, UUID coachingPeriodId, String snapshotMode, Instant plannedStartAt,
            Instant originalPlannedStartAt, Instant plannedEndAt, String supervisionRequirement,
            Instant snapshotFrozenAt, Long sourceOccurrenceVersion, Instant performedStartedAt,
            Instant performedEndedAt, WorkoutExecutionStatus status, BigDecimal overallRpe,
            String sessionNote, long version
    ) {}

    record WorkoutExecutionDetail(WorkoutExecutionSummary execution,
                                  List<WorkoutExerciseExecution> exercises) {}

    record WorkoutExerciseExecution(
            UUID exerciseExecutionId, UUID sourcePrescriptionId, UUID prescribedVariationId,
            ExercisePresentation prescribedVariationPresentation, UUID actualVariationId,
            ExercisePresentation actualVariationPresentation, String substitutionReason,
            Integer sequence, Integer baselineSetCount, Integer targetRepsMin, Integer targetRepsMax,
            BigDecimal targetLoad, Short loadUnitId, MeasurementUnitPresentation loadUnit,
            BigDecimal targetRpe, BigDecimal targetRir,
            Integer restSeconds, String tempo, Integer durationSeconds, BigDecimal distanceValue,
            Short distanceUnitId, MeasurementUnitPresentation distanceUnit, String instructions, String note,
            List<WorkoutSetExecution> sets
    ) {}

    record ExercisePresentation(
            UUID variationId, UUID exerciseId, String exerciseName, String variationName, String state,
            UUID canonicalExerciseId, String canonicalExerciseName
    ) {}

    record MeasurementUnitPresentation(short id, String code, String symbol, String dimension) {}

    record WorkoutSetExecution(
            UUID clientSetId, Integer baselineSetNumber, int setNumber, String setType,
            String completionStatus, Integer repetitions, BigDecimal loadValue, Short loadUnitId,
            MeasurementUnitPresentation loadUnit, Integer durationSeconds, BigDecimal distanceValue,
            Short distanceUnitId, MeasurementUnitPresentation distanceUnit,
            BigDecimal rpe, BigDecimal rir, String tempo, Integer restAfterSeconds, String note,
            Instant completedAt
    ) {}

    record SkipPlannedWorkoutResponse(UUID occurrenceId, String status, long occurrenceVersion,
                                      boolean replayed) {}
}
