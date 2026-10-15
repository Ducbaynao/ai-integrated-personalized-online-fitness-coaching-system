package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Workout-owned factual signal; Nutrition never reads Workout persistence directly. */
public interface WorkoutTrainingDaySignalQuery {
    boolean hasConfirmedOrPerformedWorkout(UUID studentId, LocalDate localDate, ZoneId timezone);
}
