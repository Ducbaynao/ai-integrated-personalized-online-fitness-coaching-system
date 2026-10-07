package com.fitnesscoaching.platform.modules.workout.domain;

import java.util.UUID;

public record WorkoutPlan(
        UUID id, UUID studentId, WorkoutPlanStatus status, long version,
        DecisionOwnerType decisionOwnerType, UUID decisionOwnerId,
        UUID coachingPeriodId, UUID basedOnPlanId, UUID basedOnPlanVersionId
) {
    public WorkoutPlan {
        if (id == null || studentId == null || status == null || decisionOwnerType == null
                || decisionOwnerId == null || version < 0) throw new IllegalArgumentException("Invalid workout plan");
        if (decisionOwnerType == DecisionOwnerType.STUDENT && !studentId.equals(decisionOwnerId))
            throw new IllegalArgumentException("Student-owned plan must be owned by its student");
        if ((basedOnPlanId == null) != (basedOnPlanVersionId == null) || id.equals(basedOnPlanId))
            throw new IllegalArgumentException("Invalid successor lineage");
    }

    public WorkoutPlan transitionTo(WorkoutPlanStatus target) {
        if (!status.canTransitionTo(target)) throw new WorkoutPlanFailure(
                WorkoutPlanError.WORKOUT_PLAN_LIFECYCLE_CONFLICT, "Invalid workout plan lifecycle transition");
        return new WorkoutPlan(id, studentId, target, version + 1, decisionOwnerType, decisionOwnerId,
                coachingPeriodId, basedOnPlanId, basedOnPlanVersionId);
    }

    public boolean requiresStudentSuccessor(UUID actorId) {
        return decisionOwnerType == DecisionOwnerType.TRAINER && studentId.equals(actorId);
    }
}
