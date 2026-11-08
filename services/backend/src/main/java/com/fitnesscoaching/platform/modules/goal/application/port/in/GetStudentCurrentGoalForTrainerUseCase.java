package com.fitnesscoaching.platform.modules.goal.application.port.in;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;

import java.util.UUID;

public interface GetStudentCurrentGoalForTrainerUseCase {

    FitnessGoal getStudentCurrentGoalForTrainer(UUID trainerId, UUID studentId);
}
