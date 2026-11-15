package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public response DTO for F04 progress summarization and readback.
 * Strictly presents INFORMATION projection without mutations or plan proposals.
 * Preserves the original requested scope (fromDate, toDate, timezone, requestedMetricCodes, metricScope)
 * to ensure that refreshes on saved summaries never mutate scope unintentionally.
 */
public record F04ProgressSummaryResponse(
        UUID runId,
        UUID correlationId,
        UUID studentId,
        UUID actorId,
        LocalDate fromDate,
        LocalDate toDate,
        String timezone,
        Set<String> requestedMetricCodes,
        String metricScope,
        String runStatus,
        String resultType,
        String reasonCode,
        String summary,
        WorkoutProgressSummaryDto workoutProgressSummary,
        MeasurementProgressSummaryDto measurementProgressSummary,
        ContinuityEvaluationDto continuityEvaluation,
        List<String> limitations,
        List<String> missingData,
        List<String> factsReferenced,
        Instant asOf,
        String sourceWatermark,
        UsageDto usage,
        Boolean isStale,
        Boolean needsRefresh,
        String currentWatermark
) {
    /**
     * Backward-compatibility constructor for legacy callers and tests without explicit scope parameters.
     */
    public F04ProgressSummaryResponse(
            UUID runId,
            UUID correlationId,
            UUID studentId,
            UUID actorId,
            String runStatus,
            String resultType,
            String reasonCode,
            String summary,
            WorkoutProgressSummaryDto workoutProgressSummary,
            MeasurementProgressSummaryDto measurementProgressSummary,
            ContinuityEvaluationDto continuityEvaluation,
            List<String> limitations,
            List<String> missingData,
            List<String> factsReferenced,
            Instant asOf,
            String sourceWatermark,
            UsageDto usage,
            Boolean isStale,
            Boolean needsRefresh,
            String currentWatermark
    ) {
        this(
                runId,
                correlationId,
                studentId,
                actorId,
                null,
                null,
                null,
                null,
                null,
                runStatus,
                resultType,
                reasonCode,
                summary,
                workoutProgressSummary,
                measurementProgressSummary,
                continuityEvaluation,
                limitations,
                missingData,
                factsReferenced,
                asOf,
                sourceWatermark,
                usage,
                isStale,
                needsRefresh,
                currentWatermark
        );
    }

    public record WorkoutProgressSummaryDto(
            String completionEvaluation,
            String adherenceEvaluation,
            String volumeEvaluation
    ) {}

    public record MeasurementProgressSummaryDto(
            List<MetricEvaluationDto> metricsEvaluations
    ) {}

    public record MetricEvaluationDto(
            String metricCode,
            String trendDirection,
            String message
    ) {}

    public record ContinuityEvaluationDto(
            String state,
            String message,
            boolean hasSafetyAlert
    ) {}

    public record UsageDto(
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens
    ) {}

    public F04ProgressSummaryResponse withScope(
            LocalDate fromDate,
            LocalDate toDate,
            String timezone,
            Set<String> requestedMetricCodes,
            String metricScope
    ) {
        return new F04ProgressSummaryResponse(
                runId(),
                correlationId(),
                studentId(),
                actorId(),
                fromDate,
                toDate,
                timezone,
                requestedMetricCodes,
                metricScope,
                runStatus(),
                resultType(),
                reasonCode(),
                summary(),
                workoutProgressSummary(),
                measurementProgressSummary(),
                continuityEvaluation(),
                limitations(),
                missingData(),
                factsReferenced(),
                asOf(),
                sourceWatermark(),
                usage(),
                isStale(),
                needsRefresh(),
                currentWatermark()
        );
    }

    public F04ProgressSummaryResponse withScope(
            LocalDate fromDate,
            LocalDate toDate,
            String timezone,
            Set<String> requestedMetricCodes
    ) {
        String derivedScope = (requestedMetricCodes != null && !requestedMetricCodes.isEmpty()) ? "SELECTED" : "ALL";
        return withScope(fromDate, toDate, timezone, requestedMetricCodes, derivedScope);
    }

    public static F04ProgressSummaryResponse withFreshness(
            F04ProgressSummaryResponse base,
            Boolean isStale,
            Boolean needsRefresh,
            String currentWatermark
    ) {
        return new F04ProgressSummaryResponse(
                base.runId(),
                base.correlationId(),
                base.studentId(),
                base.actorId(),
                base.fromDate(),
                base.toDate(),
                base.timezone(),
                base.requestedMetricCodes(),
                base.metricScope(),
                base.runStatus(),
                base.resultType(),
                base.reasonCode(),
                base.summary(),
                base.workoutProgressSummary(),
                base.measurementProgressSummary(),
                base.continuityEvaluation(),
                base.limitations(),
                base.missingData(),
                base.factsReferenced(),
                base.asOf(),
                base.sourceWatermark(),
                base.usage(),
                isStale,
                needsRefresh,
                currentWatermark
        );
    }

    public F04ProgressSummaryResponse withMaskedWorkout() {
        List<String> updatedFacts = this.factsReferenced() != null
                ? this.factsReferenced().stream().filter(f -> f != null && !f.toLowerCase().startsWith("workout.")).toList()
                : List.of();
        List<String> updatedLimits = new java.util.ArrayList<>(this.limitations() != null ? this.limitations() : List.of());
        updatedLimits.add("Dữ liệu buổi tập hiện bị hạn chế quyền truy cập theo chính sách chia sẻ.");

        return new F04ProgressSummaryResponse(
                runId(),
                correlationId(),
                studentId(),
                actorId(),
                fromDate(),
                toDate(),
                timezone(),
                requestedMetricCodes(),
                metricScope(),
                runStatus(),
                resultType(),
                reasonCode(),
                summary(),
                null,
                measurementProgressSummary(),
                continuityEvaluation(),
                List.copyOf(updatedLimits),
                missingData(),
                updatedFacts,
                asOf(),
                sourceWatermark(),
                usage(),
                isStale(),
                needsRefresh(),
                currentWatermark()
        );
    }

    public F04ProgressSummaryResponse withMaskedMeasurement() {
        List<String> updatedFacts = this.factsReferenced() != null
                ? this.factsReferenced().stream().filter(f -> f != null && !f.toLowerCase().startsWith("measurement.")).toList()
                : List.of();
        List<String> updatedLimits = new java.util.ArrayList<>(this.limitations() != null ? this.limitations() : List.of());
        updatedLimits.add("Dữ liệu số đo thể hình hiện bị hạn chế quyền truy cập theo chính sách chia sẻ.");

        return new F04ProgressSummaryResponse(
                runId(),
                correlationId(),
                studentId(),
                actorId(),
                fromDate(),
                toDate(),
                timezone(),
                requestedMetricCodes(),
                metricScope(),
                runStatus(),
                resultType(),
                reasonCode(),
                summary(),
                workoutProgressSummary(),
                null,
                continuityEvaluation(),
                List.copyOf(updatedLimits),
                missingData(),
                updatedFacts,
                asOf(),
                sourceWatermark(),
                usage(),
                isStale(),
                needsRefresh(),
                currentWatermark()
        );
    }
}
