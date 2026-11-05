package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public interface WorkoutProgressAggregationQuery {

    WorkoutProgressSnapshot aggregate(
            UUID actorId,
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    );

    record WorkoutProgressSnapshot(
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf,
            AdherenceSummary adherence,
            VolumeAndIntensitySummary volumeAndIntensity,
            List<PlannedOccurrenceFact> plannedOccurrences,
            List<ActualSessionFact> actualSessions,
            String sourceWatermark
    ) {}

    record AdherenceSummary(
            int scheduledDueCount,
            int completedDueCount,
            int completedOnTimeCount,
            int completedOffScheduleCount,
            int missedCount,
            int todayPendingCount,
            int inProgressCount,
            int partiallyCompletedCount,
            int abortedCount,
            int rescheduledCount,
            int cancelledCount,
            int upcomingCount,
            int unplannedCompletedCount,
            // Count of due workouts whose actual execution status cannot be verified due to restricted actor permissions
            int unverifiableDueCount,
            // Completion Rate: completedDueCount / scheduledDueCount (null if scheduledDueCount == 0 or unverifiableDueCount > 0)
            BigDecimal rawCompletionRate,
            // On-Time Adherence Rate: completedOnTimeCount / scheduledDueCount (null if scheduledDueCount == 0 or unverifiableDueCount > 0)
            BigDecimal onTimeAdherenceRate,
            String availabilityReason
    ) {}

    record VolumeAndIntensitySummary(
            // Volume in KG computed strictly from sets with convertible load units and non-null repetitions
            BigDecimal knownVolumeKg,
            int totalSetsCompleted,
            int workingSetsCompleted,
            int warmupSetsCompleted,
            // Completed working sets verified as bodyweight (via exercise catalog equipment) with no external load
            int bodyweightSetsCompleted,
            // Completed working sets with missing/unknown load (load is null but not verified bodyweight)
            int setsWithMissingLoadCount,
            // Completed working sets with valid zero load (e.g. 0 kg external load)
            int setsWithZeroLoadCount,
            // Completed working sets with missing repetition count
            int setsWithMissingRepsCount,
            // Completed working sets with unknown/non-convertible load units
            int setsWithUnknownUnitCount,
            // Completed working sets whose volume can be calculated (load != null && reps != null && convertible unit)
            int setsWithCalculableVolumeCount,
            // Proportion of completed working sets with calculable volume (setsWithCalculableVolumeCount / workingSetsCompleted)
            BigDecimal loadCoverageRate,
            // True if any completed working sets lack calculable volume
            boolean hasIncompleteVolumeCoverage,
            int totalRepsCompleted,
            // Sum of duration_seconds from set_logs (null if no sets recorded set-level duration)
            Integer totalSetDurationSeconds,
            // Sum of elapsed session durations (performed_end_at - performed_start_at) (null if no sessions recorded end time)
            Integer totalSessionElapsedSeconds,
            // Average overall_rpe across sessions (null if no sessions recorded overall_rpe)
            BigDecimal averageSessionRpe,
            // Average rpe across completed set logs (null if no sets recorded rpe)
            BigDecimal averageSetRpe,
            // True if any contributing session or set was modified after asOf or was ongoing at asOf
            boolean hasHistoricalReconstructionLimits
    ) {}

    record PlannedOccurrenceFact(
            UUID occurrenceId,
            UUID planSessionId,
            UUID coachingPeriodId,
            Instant plannedStartAt,
            LocalDate plannedLocalDate,
            Instant originalPlannedStartAt,
            LocalDate originalPlannedLocalDate,
            String status,
            UUID rescheduledFromId,
            UUID scheduleChangeId,
            long version,
            UUID linkedExecutionId,
            String linkedExecutionStatus,
            Instant linkedPerformedStartAt,
            LocalDate linkedPerformedLocalDate,
            Integer scheduleDeviationDays,
            // VERIFIED, RESTRICTED_OR_UNKNOWN, UPCOMING
            String evidenceStatus
    ) {}

    record ActualSessionFact(
            UUID executionId,
            UUID plannedWorkoutId,
            UUID coachingPeriodId,
            Instant performedStartAt,
            LocalDate performedLocalDate,
            Instant performedEndAt,
            String status,
            BigDecimal overallRpe,
            BigDecimal knownVolumeKg,
            int setsCompleted,
            int workingSetsCompleted,
            int bodyweightSetsCompleted,
            int repsCompleted,
            Integer setDurationSeconds,
            Integer sessionElapsedSeconds,
            boolean isUnplanned,
            // HISTORICAL_SNAPSHOT_EXACT, HISTORICAL_RECONSTRUCTION_LIMITED
            String historicalReconstructionStatus
    ) {}
}
