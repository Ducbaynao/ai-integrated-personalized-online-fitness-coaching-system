package com.fitnesscoaching.platform.modules.goal.application.port.in;

import java.util.UUID;

public record GetGoalVersionsQuery(
        UUID studentId,
        UUID goalId,
        int page,
        int size
) {}
