package com.fitnesscoaching.platform.modules.goal.application.model;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.GoalTransition;

public record GoalTransitionResult(
        GoalTransition transition,
        FitnessGoal newGoal
) {
}
