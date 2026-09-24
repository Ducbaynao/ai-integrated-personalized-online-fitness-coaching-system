package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.GoalProposal;

public interface CreateGoalProposalUseCase {
    GoalProposal createProposal(CreateGoalProposalCommand command);
}
