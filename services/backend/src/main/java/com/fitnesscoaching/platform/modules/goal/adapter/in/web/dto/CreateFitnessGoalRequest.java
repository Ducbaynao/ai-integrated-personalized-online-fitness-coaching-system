package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateFitnessGoalRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 200, message = "Title must not exceed 200 characters")
        String title,

        @NotNull(message = "Start date is required")
        LocalDate startDate,

        LocalDate targetDate,

        @Positive(message = "Duration days must be positive")
        Integer durationDays,

        Boolean activateImmediately,

        @NotEmpty(message = "At least one objective is required")
        List<@NotNull @Valid CreateGoalObjectiveRequest> objectives,

        List<@NotNull @Valid CreateGoalTargetRequest> targets
) {
    public CreateFitnessGoalCommand toCommand(UUID studentId) {
        return new CreateFitnessGoalCommand(
                studentId,
                title,
                startDate,
                targetDate,
                durationDays,
                Boolean.TRUE.equals(activateImmediately),
                objectives != null ? objectives.stream().map(CreateGoalObjectiveRequest::toCommand).toList() : List.of(),
                targets != null ? targets.stream().map(CreateGoalTargetRequest::toCommand).toList() : List.of()
        );
    }
}
