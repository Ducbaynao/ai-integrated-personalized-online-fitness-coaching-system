package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.application.model.FitnessGoalPage;

public interface GetStudentGoalsUseCase {

    FitnessGoalPage getStudentGoals(GetStudentGoalsQuery query);
}
