package com.fitnesscoaching.platform.modules.workout.application.model;

import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkoutExecutionViews {
    private WorkoutExecutionViews() {}

    public record Page(List<Detail> items, int page, int size, long totalItems, int totalPages) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static Page empty(int page, int size) {
            return new Page(List.of(), page, size, 0, 0);
        }
    }

    public record Detail(
            UUID executionId, UUID studentId, UUID plannedWorkoutId, UUID planId, UUID planVersionId,
            UUID planSessionId, UUID coachingPeriodId, String snapshotMode, Instant performedStartedAt,
            Instant performedEndedAt, WorkoutExecutionStatus status, BigDecimal overallRpe, String sessionNote,
            long version, Instant frozenAt, Long sourceOccurrenceVersion, Instant plannedStartAt,
            Instant originalPlannedStartAt, Instant plannedEndAt, String supervisionRequirement,
            List<Exercise> exercises
    ) {
        public Detail {
            exercises = exercises == null ? List.of() : List.copyOf(exercises);
        }
    }

    public record Exercise(
            UUID exerciseExecutionId, UUID sourcePrescriptionId, UUID prescribedVariationId,
            UUID actualVariationId, ExercisePresentation prescribedVariation,
            ExercisePresentation actualVariation, String substitutionReason, Integer sequenceNumber,
            Integer baselineSetCount, Integer targetRepsMin, Integer targetRepsMax, BigDecimal targetLoad,
            Short loadUnitId, UnitPresentation loadUnit, BigDecimal targetRpe, BigDecimal targetRir,
            Integer restSeconds, String tempo, Integer durationSeconds, BigDecimal distanceValue,
            Short distanceUnitId, UnitPresentation distanceUnit, String instructions,
            String note, List<SetView> sets
    ) {
        public Exercise {
            sets = sets == null ? List.of() : List.copyOf(sets);
        }
    }

    public record ExercisePresentation(
            UUID variationId, UUID exerciseId, String exerciseName, String variationName, String state,
            UUID canonicalExerciseId, String canonicalExerciseName
    ) {}

    public record UnitPresentation(short id, String code, String symbol, String dimension) {}

    public record SetView(
            UUID clientSetId, Integer baselineSetNumber, int setNumber, String setType,
            String completionStatus, Integer repetitions, BigDecimal loadValue, Short loadUnitId,
            UnitPresentation loadUnit, Integer durationSeconds, BigDecimal distanceValue, Short distanceUnitId,
            UnitPresentation distanceUnit,
            BigDecimal rpe, BigDecimal rir, String tempo, Integer restAfterSeconds, String note,
            Instant completedAt
    ) {}
}
