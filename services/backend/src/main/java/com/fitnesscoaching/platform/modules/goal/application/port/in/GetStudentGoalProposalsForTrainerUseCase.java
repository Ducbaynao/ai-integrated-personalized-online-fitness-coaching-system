package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.application.model.GoalProposalPage;
import com.fitnesscoaching.platform.modules.goal.domain.ProposalStatus;

import java.util.UUID;

public interface GetStudentGoalProposalsForTrainerUseCase {

    GoalProposalPage getStudentProposalsForTrainer(
            UUID trainerId,
            UUID studentId,
            UUID fitnessGoalId,
            ProposalStatus status,
            int page,
            int size
    );
}
