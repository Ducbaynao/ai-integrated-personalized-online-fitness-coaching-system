package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.domain.GoalProposalObjective;
import com.fitnesscoaching.platform.modules.goal.domain.ObjectivePriority;

import java.util.UUID;

public record GoalProposalObjectiveResponse(
        UUID id,
        short goalTypeId,
        String goalTypeCode,
        String goalTypeName,
        ObjectivePriority priority,
        int sortOrder,
        String notes
) {
    public static GoalProposalObjectiveResponse fromDomain(GoalProposalObjective domain) {
        return new GoalProposalObjectiveResponse(
                domain.id(),
                domain.goalTypeId(),
                domain.goalTypeCode(),
                domain.goalTypeName(),
                domain.priority(),
                domain.sortOrder(),
                domain.notes()
        );
    }
}
