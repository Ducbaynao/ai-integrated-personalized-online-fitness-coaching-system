package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalCommand;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ActivateFitnessGoalRequest(
        @Size(max = 1000, message = "Reason must not exceed 1000 characters")
        String reason
) {
    public ActivateFitnessGoalCommand toCommand(UUID studentId, UUID goalId) {
        return new ActivateFitnessGoalCommand(studentId, goalId, reason);
    }
}
