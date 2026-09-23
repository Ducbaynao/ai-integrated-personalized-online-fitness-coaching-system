package com.fitnesscoaching.platform.modules.goal.application.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateFitnessGoalCommand(
        UUID studentId,
        String title,
        LocalDate startDate,
        LocalDate targetDate,
        Integer durationDays,
        boolean activateImmediately,
        List<CreateGoalObjectiveCommand> objectives,
        List<CreateGoalTargetCommand> targets
) {
    public CreateFitnessGoalCommand {
        objectives = objectives != null ? List.copyOf(objectives) : List.of();
        targets = targets != null ? List.copyOf(targets) : List.of();
    }
}
