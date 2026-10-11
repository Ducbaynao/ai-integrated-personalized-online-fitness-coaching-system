package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.QualifyingWorkoutAppointmentQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/** Shared read/command precondition; the B05 mutation still rechecks under its locks. */
@Component
public class WorkoutOccurrenceActionPolicy {
    private final QualifyingWorkoutAppointmentQuery appointments;

    public WorkoutOccurrenceActionPolicy(QualifyingWorkoutAppointmentQuery appointments) {
        this.appointments = appointments;
    }

    public boolean canSkip(String status, Instant executionStartedAt) {
        return "SCHEDULED".equals(status) && executionStartedAt == null;
    }

    public boolean canStart(UUID occurrenceId, String status, Instant executionStartedAt,
                            String supervisionRequirement, boolean hasValidOverride, boolean hasInProgress) {
        return canStartWith(occurrenceId, status, executionStartedAt, supervisionRequirement,
                hasValidOverride, hasInProgress, appointments::existsForOccurrence);
    }

    public boolean canStartForMutation(UUID occurrenceId, String status, Instant executionStartedAt,
                                       String supervisionRequirement, boolean hasValidOverride) {
        return canStartWith(occurrenceId, status, executionStartedAt, supervisionRequirement,
                hasValidOverride, false, appointments::lockForExecution);
    }

    private boolean canStartWith(UUID occurrenceId, String status, Instant executionStartedAt,
                                 String supervisionRequirement, boolean hasValidOverride, boolean hasInProgress,
                                 Function<UUID, Boolean> appointmentCheck) {
        return canSkip(status, executionStartedAt) && !hasInProgress
                && (!"COACH_REQUIRED".equals(supervisionRequirement) || hasValidOverride
                    || appointmentCheck.apply(occurrenceId));
    }
}
