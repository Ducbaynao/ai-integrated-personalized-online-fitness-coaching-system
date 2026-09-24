package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.ProposalStatus;

import java.util.UUID;

public record GetStudentGoalProposalsQuery(
        UUID studentId,
        ProposalStatus statusFilter,
        int page,
        int size
) {
}
