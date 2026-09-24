package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.GoalTransition;

import java.util.List;

public interface GetGoalTransitionsUseCase {

    List<GoalTransition> getGoalTransitions(GetGoalTransitionsQuery query);
}
