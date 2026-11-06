package com.fitnesscoaching.platform.modules.ai.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Internal context envelope carrying minimal authorized facts from Spring Boot to FastAPI.
 */
public record F04InternalContextEnvelope(
        UUID requestId,
        UUID correlationId,
        UUID studentId,
        UUID actorId,
        String actorRole,
        F04ProgressContextDto context
) {
    public record F04ProgressContextDto(
            DateWindowDto dateWindow,
            Instant effectiveAsOf,
            WorkoutSectionDto workout,
            MeasurementSectionDto measurement,
            ContinuitySignalDto continuity,
            HistoricalLimitsSummaryDto historicalLimits,
            String compositeWatermark
    ) {}

    public record DateWindowDto(
            LocalDate fromDate,
            LocalDate toDate,
            String timezone
    ) {}

    public record WorkoutSectionDto(
            String availability,
            String restrictionReason,
            WorkoutAdherenceSummaryDto adherenceSummary,
            WorkoutVolumeSummaryDto volumeSummary,
            String sourceWatermark
    ) {}

    public record WorkoutAdherenceSummaryDto(
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
            int unverifiableDueCount,
            BigDecimal rawCompletionRate,
            BigDecimal onTimeAdherenceRate,
            String availabilityReason
    ) {}

    public record WorkoutVolumeSummaryDto(
            BigDecimal knownVolumeKg,
            int totalSetsCompleted,
            int workingSetsCompleted,
            int warmupSetsCompleted,
            int bodyweightSetsCompleted,
            int setsWithMissingLoadCount,
            int setsWithZeroLoadCount,
            int setsWithMissingRepsCount,
            int setsWithUnknownUnitCount,
            int setsWithCalculableVolumeCount,
            BigDecimal loadCoverageRate,
            boolean hasIncompleteVolumeCoverage,
            int totalRepsCompleted,
            Integer totalSetDurationSeconds,
            Integer totalSessionElapsedSeconds,
            BigDecimal averageSessionRpe,
            BigDecimal averageSetRpe
    ) {}

    public record MeasurementSectionDto(
            String availability,
            String restrictionReason,
            MeasurementSummaryDto summary,
            List<MetricTrendDeltaDto> metricDeltas,
            String sourceWatermark
    ) {}

    public record MeasurementSummaryDto(
            int totalObservationsCount,
            int distinctDaysCount,
            int trackedMetricsCount
    ) {}

    public record MetricTrendDeltaDto(
            String metricCode,
            String metricDisplayName,
            String unitCode,
            BigDecimal baselineValue,
            Instant baselineMeasuredAt,
            BigDecimal latestValue,
            Instant latestMeasuredAt,
            BigDecimal deltaValue,
            BigDecimal percentageChange,
            String trendDirection,
            int numericObservationsCount
    ) {}

    public record ContinuitySignalDto(
            String state,
            Integer daysSinceLastWorkout,
            LocalDate lastWorkoutDate,
            List<String> safetyFlags,
            String rationale
    ) {}

    public record HistoricalLimitsSummaryDto(
            boolean hasHistoricalLimits,
            boolean workoutLimits,
            boolean measurementLimits,
            String explanation
    ) {}
}
