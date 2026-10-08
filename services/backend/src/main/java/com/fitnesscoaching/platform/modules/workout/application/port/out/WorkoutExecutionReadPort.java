package com.fitnesscoaching.platform.modules.workout.application.port.out;

import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkoutExecutionReadPort {
    Optional<ExecutionRecord> findCurrent(UUID studentId);
    Optional<ExecutionRecord> findDetail(UUID executionId);
    PageRecord findHistory(UUID studentId, Instant historyFrom, Instant historyUntil, int page, int size);

    record PageRecord(List<ExecutionRecord> items, long totalItems) {
        public PageRecord {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record ExecutionRecord(
            UUID executionId, UUID studentId, UUID plannedWorkoutId, UUID planId, UUID planVersionId,
            UUID planSessionId, UUID coachingPeriodId, String snapshotMode, Instant performedStartedAt,
            Instant performedEndedAt, WorkoutExecutionStatus status, BigDecimal overallRpe, String sessionNote,
            long version, Instant frozenAt, Long sourceOccurrenceVersion, Instant plannedStartAt,
            Instant originalPlannedStartAt, Instant plannedEndAt, String supervisionRequirement,
            List<ExerciseRecord> exercises
    ) {
        public ExecutionRecord {
            exercises = exercises == null ? List.of() : List.copyOf(exercises);
        }
    }

    record ExerciseRecord(
            UUID exerciseExecutionId, UUID sourcePrescriptionId, UUID prescribedVariationId,
            UUID actualVariationId, String substitutionReason, Integer sequenceNumber,
            Integer baselineSetCount, Integer targetRepsMin, Integer targetRepsMax, BigDecimal targetLoad,
            Short loadUnitId, BigDecimal targetRpe, BigDecimal targetRir, Integer restSeconds, String tempo,
            Integer durationSeconds, BigDecimal distanceValue, Short distanceUnitId, String instructions,
            String note, List<SetRecord> sets
    ) {
        public ExerciseRecord {
            sets = sets == null ? List.of() : List.copyOf(sets);
        }
    }

    record SetRecord(
            UUID clientSetId, Integer baselineSetNumber, int setNumber, String setType,
            String completionStatus, Integer repetitions, BigDecimal loadValue, Short loadUnitId,
            Integer durationSeconds, BigDecimal distanceValue, Short distanceUnitId,
            BigDecimal rpe, BigDecimal rir, String tempo, Integer restAfterSeconds, String note,
            Instant completedAt
    ) {}
}
