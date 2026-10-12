package com.fitnesscoaching.platform.modules.workout.application.port.out;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.ChangeResult;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.Destination;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WorkoutScheduleChangePersistencePort {
    Optional<Receipt> lockReceipt(UUID actorId, String commandKey);
    Occurrence lockOccurrence(UUID occurrenceId);
    Instant databaseNow();
    boolean hasSuccessor(UUID occurrenceId);
    boolean hasOtherScheduledUnknownEnd(UUID studentId, UUID excludingOccurrenceId);
    boolean hasSourceInstant(UUID studentId, UUID sessionId, Instant start);
    boolean hasOtherScheduledOverlap(UUID studentId, UUID excludingOccurrenceId, Instant start, Instant end);
    void reschedule(Occurrence old, UUID replacementId, UUID changeId, UUID actorId,
                    Destination destination, Instant start, Instant end, Instant at, String reason);
    void cancel(Occurrence old, UUID changeId, UUID actorId, Instant at, String reason);
    void saveReceipt(UUID actorId, String key, String name, String hash, UUID changeId,
                     ChangeResult result, Instant at);

    record Receipt(String commandName, String payloadHash, String responseJson) {}
    record Occurrence(UUID id, UUID studentId, UUID sessionId, UUID planVersionId, UUID coachingPeriodId,
                      long version, String status, Instant executionStartedAt,
                      Instant plannedStartAt, Instant plannedEndAt, Instant originalPlannedStartAt,
                      String localStart, String localEnd, String timezone, String startUtcOffset,
                      String endUtcOffset, String supervisionRequirement, UUID scheduleBatchId) {}
}
