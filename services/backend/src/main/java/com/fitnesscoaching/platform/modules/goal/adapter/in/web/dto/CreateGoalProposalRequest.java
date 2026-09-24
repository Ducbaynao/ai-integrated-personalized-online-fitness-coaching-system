package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalProposalCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateGoalProposalRequest(
        @Size(max = 200, message = "Proposed title must not exceed 200 characters")
        String proposedTitle,

        LocalDate proposedStartDate,

        LocalDate proposedTargetDate,

        @Positive(message = "Proposed duration days must be positive")
        Integer proposedDurationDays,

        @NotBlank(message = "Proposal reason is required")
        String reason,

        Instant expiresAt,

        @NotEmpty(message = "At least one proposed objective is required")
        List<@NotNull @Valid CreateGoalObjectiveRequest> objectives,

        List<@NotNull @Valid CreateGoalTargetRequest> targets
) {
    public CreateGoalProposalRequest(
            String proposedTitle,
            LocalDate proposedStartDate,
            LocalDate proposedTargetDate,
            Integer proposedDurationDays,
            String reason,
            List<CreateGoalObjectiveRequest> objectives,
            List<CreateGoalTargetRequest> targets
    ) {
        this(proposedTitle, proposedStartDate, proposedTargetDate, proposedDurationDays, reason, null, objectives, targets);
    }

    public CreateGoalProposalCommand toCommand(UUID goalId, UUID trainerId) {
        return new CreateGoalProposalCommand(
                goalId,
                trainerId,
                proposedTitle,
                proposedStartDate,
                proposedTargetDate,
                proposedDurationDays,
                reason,
                expiresAt,
                objectives != null ? objectives.stream().map(CreateGoalObjectiveRequest::toCommand).toList() : List.of(),
                targets != null ? targets.stream().map(CreateGoalTargetRequest::toCommand).toList() : List.of()
        );
    }
}
