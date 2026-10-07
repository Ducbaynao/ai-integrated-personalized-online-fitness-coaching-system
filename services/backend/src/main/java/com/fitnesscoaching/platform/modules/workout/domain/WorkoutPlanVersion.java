package com.fitnesscoaching.platform.modules.workout.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkoutPlanVersion(
        UUID id, UUID planId, int versionNumber, Instant effectiveFrom, Instant effectiveUntil,
        Instant lockedAt, List<WorkoutSessionTemplate> sessions
) {
    public WorkoutPlanVersion {
        if (id == null || planId == null || versionNumber < 1 || effectiveFrom == null)
            throw new IllegalArgumentException("Invalid workout plan version");
        if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom))
            throw new IllegalArgumentException("Invalid effective interval");
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }
    public boolean locked() { return lockedAt != null; }
}
