package com.fitnesscoaching.platform.modules.workout.domain;

public class WorkoutPlanFailure extends RuntimeException {
    private final WorkoutPlanError error;
    public WorkoutPlanFailure(WorkoutPlanError error, String message) { super(message); this.error = error; }
    public WorkoutPlanError error() { return error; }
}
