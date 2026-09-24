package com.fitnesscoaching.platform.modules.goal.application.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateGoalTransitionCommand(
        UUID studentId,
        UUID sourceGoalId,
        String transitionReason,
        String notes,
        String title,
        LocalDate startDate,
        LocalDate targetDate,
        Integer durationDays,
        List<CreateGoalObjectiveCommand> objectives,
        List<CreateGoalTargetCommand> targets
) {
}
