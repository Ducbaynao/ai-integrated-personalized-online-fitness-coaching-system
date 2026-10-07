package com.fitnesscoaching.platform.modules.workout.domain;

public enum WorkoutPlanStatus {
    DRAFT, ACTIVE, PAUSED, COMPLETED, ARCHIVED;

    public boolean canTransitionTo(WorkoutPlanStatus target) {
        if (target == null || target == this) return false;
        return switch (this) {
            case DRAFT -> target == ACTIVE || target == ARCHIVED;
            case ACTIVE -> target == PAUSED || target == COMPLETED || target == ARCHIVED;
            case PAUSED -> target == ACTIVE || target == COMPLETED || target == ARCHIVED;
            case COMPLETED -> target == ARCHIVED;
            case ARCHIVED -> false;
        };
    }
}
