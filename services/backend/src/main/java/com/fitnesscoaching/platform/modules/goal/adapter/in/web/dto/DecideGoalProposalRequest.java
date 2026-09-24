package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.application.port.in.DecideGoalProposalCommand;
import com.fitnesscoaching.platform.modules.goal.domain.ProposalDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record DecideGoalProposalRequest(
        @NotNull(message = "Decision is required")
        ProposalDecision decision,

        @Size(max = 2000, message = "Decision note must not exceed 2000 characters")
        String decisionNote
) {
    public DecideGoalProposalCommand toCommand(UUID proposalId, UUID studentId) {
        return new DecideGoalProposalCommand(
                proposalId,
                studentId,
                decision,
                decisionNote
        );
    }
}
