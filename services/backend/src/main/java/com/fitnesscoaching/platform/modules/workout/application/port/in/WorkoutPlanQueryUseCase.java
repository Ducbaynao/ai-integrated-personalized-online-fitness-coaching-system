package com.fitnesscoaching.platform.modules.workout.application.port.in;

import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.Page;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanSummary;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.VersionDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.VersionSummary;

import java.util.UUID;

public interface WorkoutPlanQueryUseCase {
    Page<PlanSummary> list(UUID actorId, UUID studentId, int page, int size);
    PlanDetail current(UUID actorId, UUID studentId);
    PlanDetail detail(UUID actorId, UUID planId);
    Page<VersionSummary> versions(UUID actorId, UUID planId, int page, int size);
    VersionDetail version(UUID actorId, UUID planId, UUID versionId);
}
