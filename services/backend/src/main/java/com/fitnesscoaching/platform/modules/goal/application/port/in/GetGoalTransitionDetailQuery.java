package com.fitnesscoaching.platform.modules.goal.application.port.in;

import java.util.UUID;

public record GetGoalTransitionDetailQuery(
        UUID studentId,
        UUID goalId,
        UUID transitionId
) {
}
