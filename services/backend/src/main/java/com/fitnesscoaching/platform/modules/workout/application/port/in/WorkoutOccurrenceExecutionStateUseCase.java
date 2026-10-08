package com.fitnesscoaching.platform.modules.workout.application.port.in;

import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionStatus;

import java.time.Instant;
import java.util.UUID;

/** B04-owned planned-occurrence synchronization boundary for B05. */
public interface WorkoutOccurrenceExecutionStateUseCase {
    UUID studentForOccurrence(UUID occurrenceId);
    long lockForExecutionMutation(UUID occurrenceId, UUID studentActorId);
    PlannedWorkoutOutcome skip(UUID occurrenceId, UUID studentActorId, long expectedVersion, String reason);
    PlannedWorkoutOutcome synchronizeTerminal(UUID occurrenceId, UUID studentActorId, long expectedVersion,
                                              WorkoutExecutionStatus executionStatus, Instant boundary);
    record PlannedWorkoutOutcome(UUID occurrenceId, String status, long version, Instant effectiveAt) {}
}
