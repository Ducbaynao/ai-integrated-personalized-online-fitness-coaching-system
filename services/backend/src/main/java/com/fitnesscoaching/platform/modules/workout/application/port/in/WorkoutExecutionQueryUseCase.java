package com.fitnesscoaching.platform.modules.workout.application.port.in;

import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutExecutionViews.Detail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutExecutionViews.Page;

import java.util.UUID;

public interface WorkoutExecutionQueryUseCase {
    Detail current(UUID actorId);
    Detail detail(UUID actorId, UUID executionId);
    Page history(UUID actorId, UUID studentId, int page, int size);
}
