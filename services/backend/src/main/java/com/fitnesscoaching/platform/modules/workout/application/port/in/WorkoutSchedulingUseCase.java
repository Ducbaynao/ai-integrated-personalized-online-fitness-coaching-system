package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.time.Instant;
import java.util.UUID;

/** Explicit B06 boundary. Appointment changes never mutate an occurrence through this port. */
public interface WorkoutSchedulingUseCase {
    SchedulingProjection materialize(MaterializeOccurrenceCommand command);
    SchedulingProjection reschedule(RescheduleOccurrenceCommand command);

    record MaterializeOccurrenceCommand(UUID planId, UUID studentId, UUID actorId,
                                        int weekNumber, int dayNumber, int sequenceNumber,
                                        Instant plannedStartAt, Instant plannedEndAt, String commandKey) {}
    record RescheduleOccurrenceCommand(UUID occurrenceId, UUID actorId, long expectedVersion,
                                       Instant plannedStartAt, Instant plannedEndAt, String reason,
                                       String commandKey) {}
    record SchedulingProjection(UUID occurrenceId, UUID planId, UUID planVersionId, UUID planSessionId,
                                Instant plannedStartAt, Instant plannedEndAt, long version) {}
}
