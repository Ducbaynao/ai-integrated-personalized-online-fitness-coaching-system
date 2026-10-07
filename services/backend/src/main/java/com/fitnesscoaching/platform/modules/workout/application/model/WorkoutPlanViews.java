package com.fitnesscoaching.platform.modules.workout.application.model;

import com.fitnesscoaching.platform.modules.workout.domain.DecisionOwnerType;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkoutPlanViews {
    private WorkoutPlanViews() {}

    public enum ReadContext { CURRENT, HISTORICAL }

    public record Page<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}

    public record PlanSummary(
            UUID id, UUID studentId, UUID fitnessGoalId, UUID coachingPeriodId,
            String name, String description, String source, WorkoutPlanStatus status,
            long aggregateVersion, DecisionOwnerType decisionOwnerType, UUID decisionOwnerId,
            UUID basedOnPlanId, UUID basedOnPlanVersionId,
            Instant createdAt, Instant updatedAt, Instant archivedAt,
            UUID currentVersionId, Integer currentVersionNumber, Instant currentVersionEffectiveFrom,
            Instant currentVersionLockedAt, ReadContext readContext
    ) {}

    public record PlanDetail(PlanSummary plan, VersionSummary currentVersion) {}

    public record VersionSummary(
            UUID id, UUID planId, int versionNumber, Instant effectiveFrom, Instant effectiveUntil,
            String changeLevel, String changeReason, String changeSummary,
            UUID createdBy, Instant createdAt, Instant lockedAt, boolean current,
            ReadContext readContext
    ) {}

    public record VersionDetail(VersionSummary version, List<SessionView> sessions) {}

    public record SessionView(
            UUID id, int weekNumber, int dayNumber, int sequenceNumber, String name,
            String focus, Integer estimatedDurationMinutes, String notes,
            List<PrescriptionView> prescriptions
    ) {}

    public record PrescriptionView(
            UUID id, UUID exerciseVariationId, int sequenceNumber,
            Integer targetSets, Integer targetRepsMin, Integer targetRepsMax,
            BigDecimal targetLoad, Integer restSeconds, Integer durationSeconds, String instructions,
            ExercisePresentation exercise
    ) {}

    public record ExercisePresentation(
            UUID variationId, UUID exerciseId, String exerciseName, String variationName,
            String state, UUID canonicalExerciseId, String canonicalExerciseName
    ) {}
}
