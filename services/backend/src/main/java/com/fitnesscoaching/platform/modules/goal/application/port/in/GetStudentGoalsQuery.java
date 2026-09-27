package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;

import java.util.UUID;

public record GetStudentGoalsQuery(
        UUID studentId,
        GoalStatus status,
        int page,
        int size
) {
}
