package com.fitnesscoaching.platform.modules.workout.application.port.out;

import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionAdjustment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkoutExecutionSnapshotPersistencePort {
    SnapshotOccurrence lockOccurrence(UUID occurrenceId);
    List<SnapshotPrescription> prescriptions(UUID planSessionId);
    List<StoredAdjustment> adjustments(UUID occurrenceId);
    Instant databaseNow();
    void seal(UUID occurrenceId, long expectedVersion, Instant at);
    void skip(UUID occurrenceId, long expectedVersion, String reason, Instant at);
    void synchronizeTerminal(UUID occurrenceId, long expectedVersion, String plannedStatus, Instant at);

    record SnapshotOccurrence(UUID id, UUID studentId, UUID planId, UUID planVersionId, UUID planSessionId,
                              UUID coachingPeriodId, Instant plannedStartAt, Instant plannedEndAt,
                              Instant originalPlannedStartAt, String status, String supervisionRequirement,
                              Instant executionStartedAt, long version) {}
    record SnapshotPrescription(UUID id, UUID variationId, int sequence, Integer targetSets,
                                Integer repsMin, Integer repsMax, BigDecimal load, Short loadUnitId,
                                BigDecimal targetRpe, BigDecimal targetRir, Integer restSeconds, String tempo,
                                Integer durationSeconds, BigDecimal distanceValue, Short distanceUnitId,
                                String instructions) {}
    record StoredAdjustment(UUID id, WorkoutSessionAdjustment.Type type, UUID prescriptionId,
                            UUID replacementVariationId, String resolutionState, BigDecimal targetLoad,
                            Short loadUnitId, Integer repsMin, Integer repsMax, Integer targetSets,
                            Integer durationSeconds, Integer sequence, String note) {}
}
