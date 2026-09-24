package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.application.model.GoalProposalPage;

public interface GetStudentGoalProposalsUseCase {
    GoalProposalPage getStudentProposals(GetStudentGoalProposalsQuery query);
}
