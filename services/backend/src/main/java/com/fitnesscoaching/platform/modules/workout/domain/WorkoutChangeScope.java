package com.fitnesscoaching.platform.modules.workout.domain;

public enum WorkoutChangeScope {
    ONE_MATERIALIZED_OCCURRENCE,
    TEMPLATE_OR_FUTURE_OCCURRENCES,
    MULTIPLE_OCCURRENCES;

    public boolean isMinor() { return this == ONE_MATERIALIZED_OCCURRENCE; }
    public void requireMinor() {
        if (!isMinor()) throw new WorkoutPlanFailure(WorkoutPlanError.WORKOUT_PLAN_IMMUTABLE,
                "Template, future, and multi-occurrence changes require a new workout plan version");
    }
}
