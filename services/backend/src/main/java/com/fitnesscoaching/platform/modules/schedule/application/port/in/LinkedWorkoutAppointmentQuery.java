package com.fitnesscoaching.platform.modules.schedule.application.port.in;

import java.util.UUID;
import java.time.Instant;
import java.util.List;

/** Schedule-owned check of every Appointment link, regardless of Appointment status. */
public interface LinkedWorkoutAppointmentQuery {
    /** Called after the Planned Workout row lock; holds existing links until transaction commit. */
    boolean hasAnyLinkedForMutation(UUID occurrenceId);
    boolean hasAnyLinked(UUID occurrenceId);
    List<Linked> findActiveLinks(UUID occurrenceId);

    /** Called after the occurrence row lock, matching Appointment writer lock order. */
    List<Linked> lockLinkedForChange(UUID occurrenceId);
    boolean qualifiesForReplacement(Linked linked, UUID studentId, UUID periodId,
                                    Instant start, Instant end);
    boolean transfer(Linked linked, UUID predecessorId, UUID successorId, UUID actorId);
    boolean release(Linked linked, UUID occurrenceId, UUID actorId);

    record Linked(UUID appointmentId, UUID studentId, UUID trainerId, UUID periodId,
                  long version, String status, Instant startAt, Instant endAt) {}
}
