package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public response DTO for F04 progress summarization and readback.
 * Strictly presents INFORMATION projection without mutations or plan proposals.
 */
public record F04ProgressSummaryResponse(
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
