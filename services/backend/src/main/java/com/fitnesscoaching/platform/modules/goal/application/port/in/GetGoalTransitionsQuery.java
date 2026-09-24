package com.fitnesscoaching.platform.modules.goal.application.port.in;

import java.util.UUID;

public record GetGoalTransitionsQuery(
        UUID studentId,
        UUID goalId
) {
}
