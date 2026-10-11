package com.fitnesscoaching.platform.modules.schedule.application.port.in;

import java.util.UUID;

/** Schedule-owned interpretation of the V16 qualifying coached Appointment predicate. */
public interface QualifyingWorkoutAppointmentQuery {
    boolean existsForOccurrence(UUID occurrenceId);
    /** Holds a qualifying Appointment against concurrent status/delete changes through START commit. */
    boolean lockForExecution(UUID occurrenceId);
}
