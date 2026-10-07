package com.fitnesscoaching.platform.modules.workout.domain;

import java.util.List;
import java.util.UUID;

public record WorkoutSessionTemplate(
        UUID id, int weekNumber, int dayNumber, int sequenceNumber, String name,
        String focus, Integer estimatedDurationMinutes, String notes,
        List<ExercisePrescription> prescriptions
) {
    public WorkoutSessionTemplate {
        if (weekNumber < 1 || dayNumber < 1 || sequenceNumber < 1 || name == null || name.isBlank())
            throw new IllegalArgumentException("Invalid workout session template");
        prescriptions = prescriptions == null ? List.of() : List.copyOf(prescriptions);
    }
}
