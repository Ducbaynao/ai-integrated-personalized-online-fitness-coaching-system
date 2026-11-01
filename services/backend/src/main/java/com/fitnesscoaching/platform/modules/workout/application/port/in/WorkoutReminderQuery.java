package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Workout-owned read boundary; only self-performable scheduled occurrences qualify. */
public interface WorkoutReminderQuery {
    record Slot(UUID id, long version, UUID student, Instant startAt) {}
    List<Slot> startingBetween(Instant after, Instant through);
    Optional<Slot> scheduled(UUID id);
}
