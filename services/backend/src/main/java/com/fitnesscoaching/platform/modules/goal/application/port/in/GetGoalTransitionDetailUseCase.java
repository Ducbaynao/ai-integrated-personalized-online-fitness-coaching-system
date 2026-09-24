package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.GoalTransition;

public interface GetGoalTransitionDetailUseCase {

    GoalTransition getGoalTransitionDetail(GetGoalTransitionDetailQuery query);
}
