package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.ProposalDecision;

import java.util.UUID;

public record DecideGoalProposalCommand(
        UUID proposalId,
        UUID studentId,
        ProposalDecision decision,
        String decisionNote
) {
}
