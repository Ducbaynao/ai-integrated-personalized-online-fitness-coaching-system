package com.fitnesscoaching.platform.modules.workout.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlannedWorkoutOccurrence(
        UUID id, UUID studentId, UUID planSessionId, UUID planVersionId,
        Instant plannedStartAt, Instant originalPlannedStartAt, String status,
        long version, List<WorkoutSessionAdjustment> adjustments
) {
    public PlannedWorkoutOccurrence {
        if (id == null || studentId == null || plannedStartAt == null || originalPlannedStartAt == null || version < 0)
            throw new IllegalArgumentException("Invalid planned workout occurrence");
        adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
    }
}
