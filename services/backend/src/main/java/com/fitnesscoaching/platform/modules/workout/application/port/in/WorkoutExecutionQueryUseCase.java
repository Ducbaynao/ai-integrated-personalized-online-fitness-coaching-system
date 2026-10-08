package com.fitnesscoaching.platform.modules.workout.application.port.in;

import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkoutExecutionQueryUseCase {
    Optional<WorkoutExecution> current(UUID actorId);
    WorkoutExecution detail(UUID actorId, UUID executionId);
    List<WorkoutExecution> history(UUID actorId, UUID studentId, int limit, int offset);
}
