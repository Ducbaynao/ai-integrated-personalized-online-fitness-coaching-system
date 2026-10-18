package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Workout-owned, execution-aware evidence for the deterministic missed-planned rule. */
public interface WorkoutMissedEvidenceQuery {
    List<Evidence> candidates(Instant cutoff);
    Optional<Evidence> find(UUID occurrenceId);
    record Evidence(UUID occurrenceId, UUID studentId, long occurrenceVersion,
                    Instant plannedStartAt, Instant plannedEndAt, boolean eligible) {}
}
