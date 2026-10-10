package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Workout-owned confirmation boundary, reusable by a later Trainer-proposal acceptance command. */
public interface WorkoutScheduleBatchUseCase {
    ConfirmedBatch confirmDirect(ConfirmBatch command);

    record ConfirmBatch(UUID actorId, UUID planId, UUID sourcePlanVersionId,
                        long expectedPlanAggregateVersion, LocalDate weekAnchorDate,
                        String timezone, List<Item> items, String commandKey) {}
    record Item(UUID clientItemId, UUID planSessionId, int weekNumber, int dayNumber,
                int sequenceNumber, LocalDateTime localStart, LocalDateTime localEnd,
                String startUtcOffset, String endUtcOffset) {}
    record ConfirmedItem(UUID clientItemId, UUID occurrenceId, long occurrenceVersion,
                         UUID planSessionId, int weekNumber, int dayNumber, int sequenceNumber,
                         LocalDateTime localStart, LocalDateTime localEnd,
                         String startUtcOffset, String endUtcOffset,
                         Instant plannedStartAt, Instant plannedEndAt, String supervisionRequirement) {}
    record ConfirmedBatch(UUID batchId, UUID studentId, UUID planId, UUID planVersionId,
                          int planVersionNumber, LocalDate weekAnchorDate, String timezone,
                          String confirmationSource, UUID sourceProposalId, UUID confirmedBy,
                          Instant confirmedAt, List<ConfirmedItem> items, boolean replayed) {
        public ConfirmedBatch asReplay() {
            return new ConfirmedBatch(batchId, studentId, planId, planVersionId, planVersionNumber,
                    weekAnchorDate, timezone, confirmationSource, sourceProposalId, confirmedBy,
                    confirmedAt, items, true);
        }
    }
}
