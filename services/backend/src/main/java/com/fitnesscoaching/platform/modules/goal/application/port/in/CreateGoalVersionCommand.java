package com.fitnesscoaching.platform.modules.goal.application.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateGoalVersionCommand(
        UUID studentId,
        UUID goalId,
        String title,
        LocalDate startDate,
        LocalDate targetDate,
        Integer durationDays,
        String changeReason,
        String changeSummary,
        List<CreateGoalObjectiveCommand> objectives,
        List<CreateGoalTargetCommand> targets
) {
    public CreateGoalVersionCommand {
        objectives = objectives != null ? List.copyOf(objectives) : List.of();
        targets = targets != null ? List.copyOf(targets) : List.of();
    }
}
