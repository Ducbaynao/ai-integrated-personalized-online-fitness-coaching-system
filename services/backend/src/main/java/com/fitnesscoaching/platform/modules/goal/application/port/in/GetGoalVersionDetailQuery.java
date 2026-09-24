package com.fitnesscoaching.platform.modules.goal.application.port.in;

import java.util.UUID;

public record GetGoalVersionDetailQuery(
        UUID studentId,
        UUID goalId,
        UUID versionId
) {}
