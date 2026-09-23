package com.fitnesscoaching.platform.modules.goal.application.port.out;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.goal.domain.GoalObjective;
import com.fitnesscoaching.platform.modules.goal.domain.GoalTarget;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FitnessGoalPersistencePort {

    FitnessGoal saveGoalAggregate(
            FitnessGoal goal,
            FitnessGoalVersion version,
            List<GoalObjective> objectives,
            List<GoalTarget> targets,
            boolean activateImmediately
    );

    FitnessGoal activateGoal(UUID goalId, UUID studentId, String reason);

    Optional<FitnessGoal> findById(UUID goalId);

    Optional<FitnessGoal> findCurrentActiveByStudentId(UUID studentId);

    boolean hasActiveGoal(UUID studentId);
}
