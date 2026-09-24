package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;

public interface CreateFitnessGoalUseCase {

    FitnessGoal createFitnessGoal(CreateFitnessGoalCommand command);
}
