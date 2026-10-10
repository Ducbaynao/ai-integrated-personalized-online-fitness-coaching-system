package com.fitnesscoaching.platform.modules.workout.application.port.out;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.ConfirmedBatch;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.ConfirmedItem;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WorkoutScheduleBatchPersistencePort {
    Optional<Receipt> lockReceipt(UUID actorId, String commandKey);
    Session findSession(UUID versionId, UUID sessionId);
    boolean isPublishedEffective(UUID versionId, UUID planId, Instant at);
    boolean hasScheduledUnknownEnd(UUID studentId);
    boolean hasSourceInstant(UUID studentId, UUID sessionId, Instant start);
    boolean hasScheduledOverlap(UUID studentId, Instant start, Instant end);
    void saveBatch(ConfirmedBatch batch);
    void saveOccurrence(ConfirmedBatch batch, ConfirmedItem item, UUID coachingPeriodId);
    void saveReceipt(UUID actorId, String commandKey, String hash, UUID batchId, String responseJson, Instant at);

    record Receipt(String commandName, String payloadHash, String responseJson) {}
    record Session(UUID id, int weekNumber, int dayNumber, int sequenceNumber, String supervision) {}
}
