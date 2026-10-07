package com.fitnesscoaching.platform.modules.workout.application.port.in;

import com.fitnesscoaching.platform.modules.workout.domain.PlannedWorkoutOccurrence;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanVersion;

import java.util.Optional;
import java.util.UUID;

/** Workout-owned immutable input boundary for B05. */
public interface WorkoutExecutionSnapshotQuery {
    Optional<WorkoutPlanVersion> findImmutableVersion(UUID versionId, UUID actorId);
    Optional<PlannedWorkoutOccurrence> findOccurrenceWithOrderedAdjustments(UUID occurrenceId, UUID actorId);
}
