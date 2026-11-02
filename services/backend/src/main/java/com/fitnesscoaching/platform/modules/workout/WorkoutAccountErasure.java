package com.fitnesscoaching.platform.modules.workout;

import java.util.UUID;

/** Workout-owned, author-proven text minimization; Account owns the lease and hold fence. */
public interface WorkoutAccountErasure {
    void minimizeAuthoredVersionSummaries(UUID userId);
}
