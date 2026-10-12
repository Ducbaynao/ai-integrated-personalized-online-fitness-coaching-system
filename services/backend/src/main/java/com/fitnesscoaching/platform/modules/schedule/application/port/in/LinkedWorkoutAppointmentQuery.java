package com.fitnesscoaching.platform.modules.schedule.application.port.in;

import java.util.UUID;

/** Schedule-owned check of every Appointment link, regardless of Appointment status. */
public interface LinkedWorkoutAppointmentQuery {
    /** Called after the Planned Workout row lock; holds existing links until transaction commit. */
    boolean hasAnyLinkedForMutation(UUID occurrenceId);
    boolean hasAnyLinked(UUID occurrenceId);
}
