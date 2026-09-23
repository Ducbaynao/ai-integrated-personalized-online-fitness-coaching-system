package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.domain.GoalObjective;
import com.fitnesscoaching.platform.modules.goal.domain.ObjectivePriority;

import java.util.UUID;

public record GoalObjectiveResponse(
        UUID id,
        short goalTypeId,
        String goalTypeCode,
        String goalTypeName,
        ObjectivePriority priority,
        int sortOrder,
        String notes
) {
    public static GoalObjectiveResponse fromDomain(GoalObjective objective) {
        if (objective == null) {
            return null;
        }
        return new GoalObjectiveResponse(
                objective.id(),
                objective.goalTypeId(),
                objective.goalTypeCode(),
                objective.goalTypeName(),
                objective.priority(),
                objective.sortOrder(),
                objective.notes()
        );
    }
}
