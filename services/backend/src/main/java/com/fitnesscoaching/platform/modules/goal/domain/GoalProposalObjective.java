package com.fitnesscoaching.platform.modules.goal.domain;

import java.util.UUID;

public record GoalProposalObjective(
        UUID id,
        UUID goalProposalId,
        short goalTypeId,
        String goalTypeCode,
        String goalTypeName,
        ObjectivePriority priority,
        int sortOrder,
        String notes
) {
}
