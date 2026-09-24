package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.ObjectivePriority;

public record CreateGoalObjectiveCommand(
        Short goalTypeId,
        String goalTypeCode,
        ObjectivePriority priority,
        Integer sortOrder,
        String notes
) {
}
