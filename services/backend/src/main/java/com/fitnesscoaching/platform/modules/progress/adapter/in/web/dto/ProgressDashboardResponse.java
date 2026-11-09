package com.fitnesscoaching.platform.modules.progress.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProgressDashboardResponse(
        UUID studentId,
        String scope,
        LocalDate fromDate,
        LocalDate toDate,
        String timezone,
        Instant asOf,
        GoalInfo goal,
        ProgressEngineQuery.WorkoutProgressSection workout,
        ProgressEngineQuery.MeasurementProgressSection measurement,
        ProgressEngineQuery.ContinuitySignal continuity,
        ProgressEngineQuery.HistoricalLimitsSummary historicalLimits,
        String compositeWatermark
) {
    public record GoalInfo(
            UUID id,
            String title,
            String status,
            LocalDate startDate,
            LocalDate targetDate,
            Integer durationDays,
            List<GoalTargetInfo> targets
    ) {}

    public record GoalTargetInfo(
            int metricDefinitionId,
            String metricCode,
            String metricDisplayName,
            java.math.BigDecimal targetValue,
            java.math.BigDecimal targetMinValue,
            java.math.BigDecimal targetMaxValue,
            String unitCode,
            String unitSymbol
    ) {}
}
