package com.fitnesscoaching.platform.modules.ai.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of source dependencies captured when an F05 workout reschedule proposal is generated.
 * Used during Accept-and-Apply to guard against state drift (occurrence version, occurrence status,
 * coaching relationship, and supervision requirement).
 */
public record F05SourceSnapshot(
        UUID studentId,
        UUID coachingPeriodId,
        String coachingMode,
        UUID trainerId,
        UUID occurrenceId,
        long occurrenceVersion,
        String occurrenceStatus,
        UUID planId,
        UUID planVersionId,
        UUID planSessionId,
        String sessionName,
        Instant plannedStartAt,
        Instant plannedEndAt,
        Instant originalPlannedStartAt,
        String timezone,
        String localStart,
        String localEnd,
        String startUtcOffset,
        String endUtcOffset,
        String supervisionRequirement,
        UUID linkedAppointmentId,
        Long linkedAppointmentVersion,
        TrainingContinuityState continuityState,
        Instant asOf
) {}
