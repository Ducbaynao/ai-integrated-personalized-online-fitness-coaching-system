package com.fitnesscoaching.platform.modules.workout.application.port.out;

import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanSummary;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.VersionDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.VersionSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkoutPlanQueryPort {
    List<PlanSummary> findByStudent(UUID studentId);
    Optional<PlanSummary> findActiveByStudent(UUID studentId);
    Optional<PlanSummary> findPlan(UUID planId);
    List<VersionSummary> findVersions(UUID planId);
    Optional<VersionDetail> findVersion(UUID planId, UUID versionId);
}
