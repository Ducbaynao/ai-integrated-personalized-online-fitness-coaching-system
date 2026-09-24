package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;

public interface GetGoalVersionDetailUseCase {

    FitnessGoalVersion getGoalVersionDetail(GetGoalVersionDetailQuery query);
}
