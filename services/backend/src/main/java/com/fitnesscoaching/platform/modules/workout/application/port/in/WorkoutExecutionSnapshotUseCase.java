package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** B04-owned stateful boundary used by B05 in the caller's transaction. */
public interface WorkoutExecutionSnapshotUseCase {
    FrozenExecutionSnapshot freezeForExecution(FreezeExecutionCommand command);

    record FreezeExecutionCommand(UUID occurrenceId, UUID studentActorId, long expectedOccurrenceVersion) {}
    record FrozenExecutionSnapshot(
            UUID occurrenceId, UUID studentId, UUID planId, UUID planVersionId, UUID planSessionId,
            UUID coachingPeriodId, Instant plannedStartAt, Instant plannedEndAt,
            Instant originalPlannedStartAt, String supervisionRequirement, Instant frozenAt,
            long sealedOccurrenceVersion, String sessionNote, List<FrozenPrescription> prescriptions,
            List<UUID> appliedAdjustmentIds
    ) {}
    record FrozenPrescription(
            UUID prescriptionId, UUID prescribedVariationId, int sequenceNumber, int targetSets,
            Integer targetRepsMin, Integer targetRepsMax, BigDecimal targetLoad, Short loadUnitId,
            BigDecimal targetRpe, BigDecimal targetRir, Integer restSeconds, String tempo,
            Integer durationSeconds, BigDecimal distanceValue, Short distanceUnitId,
            String instructions, String note
    ) {}
}
