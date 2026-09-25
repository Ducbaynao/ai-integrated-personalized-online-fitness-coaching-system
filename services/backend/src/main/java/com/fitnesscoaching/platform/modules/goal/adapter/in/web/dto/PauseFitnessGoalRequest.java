package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.application.port.in.PauseFitnessGoalCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record PauseFitnessGoalRequest(
        @NotBlank(message = "Reason is required")
        @Size(max = 1000, message = "Reason must not exceed 1000 characters")
        String reason
) {
    public PauseFitnessGoalCommand toCommand(UUID studentId, UUID goalId) {
        return new PauseFitnessGoalCommand(studentId, goalId, reason != null ? reason.trim() : null);
    }
}
