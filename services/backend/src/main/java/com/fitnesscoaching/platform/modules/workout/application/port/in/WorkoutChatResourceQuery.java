package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.util.UUID;

/** Workout-owned Chat read boundary; a Chat link never grants Workout access. */
public interface WorkoutChatResourceQuery {
    boolean canReadPublishedVersion(UUID actor, UUID student, UUID period, UUID planId, UUID versionId);
    boolean canReadOccurrence(UUID actor, UUID student, UUID period, UUID occurrenceId);
}
