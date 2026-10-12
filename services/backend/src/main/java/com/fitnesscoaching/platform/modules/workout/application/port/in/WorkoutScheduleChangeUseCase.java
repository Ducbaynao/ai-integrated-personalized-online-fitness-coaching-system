package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

public interface WorkoutScheduleChangeUseCase {
    ChangeResult reschedule(Reschedule command);
    ChangeResult cancel(Cancel command);
    void lockStudentAndActor(UUID studentId, UUID actorId);
    ProposalSource inspectProposalCreation(UUID studentId, UUID occurrenceId, long expectedOccurrenceVersion,
                                           String changeType, Destination destination);

    record Destination(LocalDateTime localStart, LocalDateTime localEnd, String timezone,
                       String startUtcOffset, String endUtcOffset) {}
    record Reschedule(UUID occurrenceId, UUID actorId, long expectedOccurrenceVersion,
                      Destination destination, String reason, String commandKey) {}
    record Cancel(UUID occurrenceId, UUID actorId, long expectedOccurrenceVersion,
                  String reason, String commandKey) {}
    record ProposalSource(UUID occurrenceId, UUID studentId, long occurrenceVersion,
                          UUID planVersionId, UUID planSessionId, Instant plannedStartAt,
                          Instant plannedEndAt, Instant originalPlannedStartAt, String timezone,
                          String localStart, String localEnd, String startUtcOffset, String endUtcOffset) {}
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
