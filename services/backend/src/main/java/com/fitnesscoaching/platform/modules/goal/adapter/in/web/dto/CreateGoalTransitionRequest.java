package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTransitionCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateGoalTransitionRequest(
        @NotBlank(message = "Transition reason is required")
        @Size(max = 100, message = "Transition reason must not exceed 100 characters")
        String transitionReason,

        @Size(max = 2000, message = "Notes must not exceed 2000 characters")
        String notes,

        @NotBlank(message = "Title is required")
        @Size(max = 200, message = "Title must not exceed 200 characters")
        String title,

        @NotNull(message = "Start date is required")
        LocalDate startDate,

        LocalDate targetDate,

        @Positive(message = "Duration days must be positive")
        Integer durationDays,

        @NotNull(message = "Objectives must not be null")
        @NotEmpty(message = "At least one objective is required")
        List<@Valid CreateGoalObjectiveRequest> objectives,

        List<@Valid CreateGoalTargetRequest> targets
) {
    public CreateGoalTransitionCommand toCommand(UUID studentId, UUID sourceGoalId) {
        return new CreateGoalTransitionCommand(
                studentId,
                sourceGoalId,
                transitionReason != null ? transitionReason.trim() : null,
                notes != null ? notes.trim() : null,
                title != null ? title.trim() : null,
                startDate,
                targetDate,
                durationDays,
                objectives != null ? objectives.stream().map(CreateGoalObjectiveRequest::toCommand).toList() : List.of(),
                targets != null ? targets.stream().map(CreateGoalTargetRequest::toCommand).toList() : List.of()
        );
    }
}
