package com.fitnesscoaching.platform.modules.goal.application.port.in;

import java.util.UUID;

public record ActivateFitnessGoalCommand(
        UUID studentId,
        UUID goalId,
        String reason
) {
}
