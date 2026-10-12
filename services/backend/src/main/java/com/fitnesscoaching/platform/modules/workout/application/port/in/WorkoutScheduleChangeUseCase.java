package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

public interface WorkoutScheduleChangeUseCase {
    ChangeResult reschedule(Reschedule command);
    ChangeResult cancel(Cancel command);

    record Destination(LocalDateTime localStart, LocalDateTime localEnd, String timezone,
                       String startUtcOffset, String endUtcOffset) {}
    record Reschedule(UUID occurrenceId, UUID actorId, long expectedOccurrenceVersion,
                      Destination destination, String reason, String commandKey) {}
    record Cancel(UUID occurrenceId, UUID actorId, long expectedOccurrenceVersion,
                  String reason, String commandKey) {}
    record ChangeResult(String changeType, UUID previousOccurrenceId, long previousOccurrenceVersion,
                        String previousStatus, Instant originalPlannedStartAt, Instant oldPlannedStartAt,
                        Instant oldPlannedEndAt, UUID replacementOccurrenceId, Long replacementOccurrenceVersion,
                        Instant newPlannedStartAt, Instant newPlannedEndAt,
                        UUID rescheduledFromOccurrenceId, Instant effectiveAt, boolean replayed) {
        public ChangeResult asReplay() {
            return new ChangeResult(changeType, previousOccurrenceId, previousOccurrenceVersion, previousStatus,
                    originalPlannedStartAt, oldPlannedStartAt, oldPlannedEndAt, replacementOccurrenceId,
                    replacementOccurrenceVersion, newPlannedStartAt, newPlannedEndAt,
                    rescheduledFromOccurrenceId, effectiveAt, true);
        }
    }
}
