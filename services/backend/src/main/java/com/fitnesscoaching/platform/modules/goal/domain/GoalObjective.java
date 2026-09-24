package com.fitnesscoaching.platform.modules.goal.domain;

import java.util.UUID;

public record GoalObjective(
        UUID id,
        UUID goalVersionId,
        short goalTypeId,
        String goalTypeCode,
        String goalTypeName,
        ObjectivePriority priority,
        int sortOrder,
        String notes
) {
}
