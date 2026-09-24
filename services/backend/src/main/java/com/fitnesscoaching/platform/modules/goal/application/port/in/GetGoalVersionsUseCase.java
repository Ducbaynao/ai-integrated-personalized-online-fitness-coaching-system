package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.application.model.GoalVersionPage;

public interface GetGoalVersionsUseCase {

    GoalVersionPage getGoalVersions(GetGoalVersionsQuery query);
}
