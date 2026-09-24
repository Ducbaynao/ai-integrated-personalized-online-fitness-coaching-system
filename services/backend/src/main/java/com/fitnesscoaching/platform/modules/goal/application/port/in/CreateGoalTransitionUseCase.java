package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.application.model.GoalTransitionResult;

public interface CreateGoalTransitionUseCase {

    GoalTransitionResult createGoalTransition(CreateGoalTransitionCommand command);
}
