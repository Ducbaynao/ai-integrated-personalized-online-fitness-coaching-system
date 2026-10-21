package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Workout-owned snapshot used while confirming an explicitly linked Appointment. */
public interface AppointmentWorkoutLinkQuery {
    /** Locks the occurrence until the caller's transaction commits, serializing START/change. */
    Optional<Occurrence> lockForAppointment(UUID occurrenceId);

    record Occurrence(UUID id, UUID studentId, UUID coachingPeriodId, String status,
                      String supervisionRequirement, Instant plannedStartAt, Instant plannedEndAt,
                      boolean executionSealed) {}
}
