package com.fitnesscoaching.platform.modules.goal.application.port.in;

import java.util.UUID;

public record GetGoalProposalDetailQuery(
        UUID proposalId,
        UUID requesterUserId
) {
}
