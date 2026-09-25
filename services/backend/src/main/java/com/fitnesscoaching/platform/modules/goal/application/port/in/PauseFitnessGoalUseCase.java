package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;

public interface PauseFitnessGoalUseCase {

    FitnessGoal pauseFitnessGoal(PauseFitnessGoalCommand command);
}
