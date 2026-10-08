package com.fitnesscoaching.platform.modules.workout.domain;

public enum WorkoutExecutionStatus {
    IN_PROGRESS, COMPLETED, PARTIALLY_COMPLETED, ABORTED;

    public boolean terminal() { return this != IN_PROGRESS; }
}
