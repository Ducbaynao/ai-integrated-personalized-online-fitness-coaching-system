package com.fitnesscoaching.platform.modules.ai.domain;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of source dependencies captured when an F03 workout adjustment proposal is generated.
 * Used during Accept-and-Apply or post-provider checks to guard against state drift (plan version,
 * occurrence lifecycle, coaching relationship, or baseline prescriptions).
 */
public record F03SourceSnapshot(
        UUID studentId,
        UUID coachingPeriodId,
        String coachingMode,
        UUID trainerId,
        TargetScope scope,
        UUID planId,
        Long planAggregateVersion,
        Integer planVersionNumber,
        UUID plannedWorkoutId,
        Long occurrenceVersion,
        UUID plannedSessionExerciseId,
        UUID exercisePrescriptionId,
        UUID beforeExerciseVariationId,
        BigDecimal beforeTargetLoad,
        Integer beforeLoadUnitId,
        Integer beforeTargetSets,
        Integer beforeTargetRepsMin,
        Integer beforeTargetRepsMax,
        Integer beforeDurationSeconds,
        boolean allowLoadIncrease,
        TrainingContinuityState continuityState,
        Instant asOf
) {}
