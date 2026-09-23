package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalObjectiveCommand;
import com.fitnesscoaching.platform.modules.goal.domain.ObjectivePriority;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateGoalObjectiveRequest(
        Short goalTypeId,
        String goalTypeCode,
        @NotNull(message = "Priority is required")
        ObjectivePriority priority,
        Integer sortOrder,
        @Size(max = 2000, message = "Notes must not exceed 2000 characters")
        String notes
) {
    public CreateGoalObjectiveCommand toCommand() {
        return new CreateGoalObjectiveCommand(
                goalTypeId,
                goalTypeCode,
                priority,
                sortOrder,
                notes
        );
    }
}
