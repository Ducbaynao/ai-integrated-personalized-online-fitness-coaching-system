package com.fitnesscoaching.platform.modules.workout.domain;

public class WorkoutExecutionFailure extends RuntimeException {
    private final WorkoutExecutionError error;
    public WorkoutExecutionFailure(WorkoutExecutionError error, String message) {
        super(message); this.error = error;
    }
    public WorkoutExecutionError error() { return error; }
}
