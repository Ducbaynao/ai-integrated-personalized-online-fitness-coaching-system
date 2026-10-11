package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Workout-owned presentation of the exact version referenced by a schedule proposal. */
public interface WorkoutScheduleProposalSourceQuery {
    Map<UUID, Source> findByVersionIds(Collection<UUID> versionIds);

    record Source(UUID planId, UUID versionId, String planName, Map<UUID, Session> sessions) {}
    record Session(UUID sessionId, String name, String focus) {}
}
