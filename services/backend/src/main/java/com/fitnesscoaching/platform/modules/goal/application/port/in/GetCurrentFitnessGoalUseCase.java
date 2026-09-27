package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;

import java.util.Optional;
import java.util.UUID;

public interface GetCurrentFitnessGoalUseCase {

    default Optional<FitnessGoal> getCurrentFitnessGoal(UUID studentId) {
        return getCurrentFitnessGoal(studentId, false);
    }

    Optional<FitnessGoal> getCurrentFitnessGoal(UUID studentId, boolean includePaused);
}
