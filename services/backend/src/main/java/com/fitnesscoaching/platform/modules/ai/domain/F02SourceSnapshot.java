package com.fitnesscoaching.platform.modules.ai.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of source dependencies captured when F02 workout proposal is generated.
 * Used during Accept-and-Apply to guard against state drift (Goal changes, plan changes, etc.).
 */
public record F02SourceSnapshot(
        UUID goalId,
        Integer goalVersionNumber,
        UUID activePlanId,
        Long activePlanAggregateVersion,
        UUID coachingPeriodId,
        String coachingMode,
        UUID trainerId,
        TrainingContinuityState continuityState,
        Integer daysSinceLastWorkout,
        Instant asOf
) {}
