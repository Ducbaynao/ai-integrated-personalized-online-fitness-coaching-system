package com.fitnesscoaching.platform.modules.goal.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateGoalProposalCommand(
        UUID goalId,
        UUID trainerId,
        String proposedTitle,
        LocalDate proposedStartDate,
        LocalDate proposedTargetDate,
        Integer proposedDurationDays,
        String reason,
        Instant expiresAt,
        List<CreateGoalObjectiveCommand> objectives,
        List<CreateGoalTargetCommand> targets
) {
    public CreateGoalProposalCommand {
        objectives = objectives != null ? List.copyOf(objectives) : List.of();
        targets = targets != null ? List.copyOf(targets) : List.of();
    }
}
