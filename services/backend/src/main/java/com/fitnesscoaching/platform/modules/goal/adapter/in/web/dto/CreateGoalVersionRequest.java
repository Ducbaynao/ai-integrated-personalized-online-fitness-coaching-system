package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalVersionCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateGoalVersionRequest(
        @Size(max = 200, message = "Title must not exceed 200 characters")
        String title,

        LocalDate startDate,

        LocalDate targetDate,

        @Positive(message = "Duration days must be positive")
        Integer durationDays,

        @NotBlank(message = "Change reason is required")
        @Size(max = 100, message = "Change reason must not exceed 100 characters")
        String changeReason,

        @Size(max = 2000, message = "Change summary must not exceed 2000 characters")
        String changeSummary,

        @NotEmpty(message = "At least one objective is required")
        List<@NotNull @Valid CreateGoalObjectiveRequest> objectives,

        List<@NotNull @Valid CreateGoalTargetRequest> targets
) {
    public CreateGoalVersionCommand toCommand(UUID studentId, UUID goalId) {
        return new CreateGoalVersionCommand(
                studentId,
                goalId,
                title,
                startDate,
                targetDate,
                durationDays,
                changeReason,
                changeSummary,
                objectives != null ? objectives.stream().map(CreateGoalObjectiveRequest::toCommand).toList() : List.of(),
                targets != null ? targets.stream().map(CreateGoalTargetRequest::toCommand).toList() : List.of()
        );
    }
}
