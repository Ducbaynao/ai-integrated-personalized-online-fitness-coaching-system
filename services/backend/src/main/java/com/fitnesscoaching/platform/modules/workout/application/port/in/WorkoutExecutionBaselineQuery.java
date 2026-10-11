package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.util.UUID;

/** Non-mutating preview of the exact B05 frozen-prescription validation. */
public interface WorkoutExecutionBaselineQuery {
    boolean isReady(UUID occurrenceId, UUID planSessionId);
}
