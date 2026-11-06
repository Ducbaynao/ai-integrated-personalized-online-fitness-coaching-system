package com.fitnesscoaching.platform.modules.ai;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.ContinuityEvaluationDto;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.MeasurementProgressSummaryDto;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.MetricEvaluationDto;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.WorkoutProgressSummaryDto;
import com.fitnesscoaching.platform.modules.ai.application.service.F04RuntimeOutputValidator;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.ContinuitySignalDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.DateWindowDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.F04ProgressContextDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.HistoricalLimitsSummaryDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.MeasurementSectionDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.WorkoutSectionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class F04RuntimeOutputValidatorUnitTest {

    private final F04RuntimeOutputValidator validator = new F04RuntimeOutputValidator();

    private final UUID runId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID actorId = studentId;
    private final Instant now = Instant.parse("2026-10-28T12:00:00Z");

    private F04InternalContextEnvelope createEnvelope(
            String workoutAvailability,
            String measurementAvailability,
            String continuityState,
            List<String> safetyFlags
    ) {
        return new F04InternalContextEnvelope(
                runId,
                correlationId,
                studentId,
                actorId,
                "STUDENT",
                new F04ProgressContextDto(
                        new DateWindowDto(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh"),
                        now,
                        new WorkoutSectionDto(workoutAvailability, null, null, null, "wm-workout"),
                        new MeasurementSectionDto(measurementAvailability, null, null, List.of(), "wm-meas"),
                        new ContinuitySignalDto(continuityState, 2, LocalDate.of(2026, 10, 26), safetyFlags, null),
                        new HistoricalLimitsSummaryDto(false, false, false, null),
                        "ENGINE:composite-wm"
                )
        );
    }

    private F04ProgressSummaryResponse createResponse(
            UUID responseRunId,
            String resultType,
            WorkoutProgressSummaryDto workoutSummary,
            MeasurementProgressSummaryDto measSummary,
            ContinuityEvaluationDto continuityEvaluation,
            List<String> factsReferenced,
            String sourceWatermark
    ) {
        return new F04ProgressSummaryResponse(
                responseRunId,
                correlationId,
                studentId,
                actorId,
                "SUCCEEDED",
                resultType,
                "NONE",
                "Summary text",
                workoutSummary,
                measSummary,
                continuityEvaluation,
                List.of(),
                List.of(),
                factsReferenced,
                now,
                sourceWatermark,
                null,
                false,
                false,
                null
        );
    }

    @Test
    @DisplayName("Valid INFORMATION result: passes validation")
    void validInformationResult_passes() {
        F04InternalContextEnvelope envelope = createEnvelope("AVAILABLE", "AVAILABLE", "RECENT_ACTIVITY_OBSERVED", List.of());
        F04ProgressSummaryResponse response = createResponse(
                runId,
                "INFORMATION",
                new WorkoutProgressSummaryDto("Completed 10/12", "Adherence 83.3%", "Volume 14500kg"),
                new MeasurementProgressSummaryDto(List.of(new MetricEvaluationDto("F_WEIGHT", "DECREASED", "Weight decreased 0.7kg"))),
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Consistent activity", false),
                List.of("workout.adherence.rawCompletionRate:0.9167", "measurement.F_WEIGHT.trend:DECREASED"),
                "ENGINE:composite-wm"
        );

        var result = validator.validate(response, envelope);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Mismatched runId: rejected")
    void mismatchedRunId_rejected() {
        F04InternalContextEnvelope envelope = createEnvelope("AVAILABLE", "AVAILABLE", "RECENT_ACTIVITY_OBSERVED", List.of());
        F04ProgressSummaryResponse response = createResponse(
                UUID.randomUUID(), // different runId
                "INFORMATION",
                null, null,
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Consistent", false),
                List.of(),
                "ENGINE:composite-wm"
        );

        var result = validator.validate(response, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Mismatched runId");
    }

    @Test
    @DisplayName("Illegal resultType PROPOSAL: rejected (F04 is strictly INFORMATION)")
    void proposalResultType_rejected() {
        F04InternalContextEnvelope envelope = createEnvelope("AVAILABLE", "AVAILABLE", "RECENT_ACTIVITY_OBSERVED", List.of());
        F04ProgressSummaryResponse response = createResponse(
                runId,
                "PROPOSAL", // strictly forbidden in F04
                null, null,
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Consistent", false),
                List.of(),
                "ENGINE:composite-wm"
        );

        var result = validator.validate(response, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Invalid resultType");
    }

    @Test
    @DisplayName("Side channel leakage: workout is RESTRICTED but output contains workout facts: rejected")
    void restrictedWorkout_leakingWorkoutFacts_rejected() {
        F04InternalContextEnvelope envelope = createEnvelope("RESTRICTED", "AVAILABLE", "RECENT_ACTIVITY_OBSERVED", List.of());
        F04ProgressSummaryResponse response = createResponse(
                runId,
                "INFORMATION",
                null, null,
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Consistent", false),
                List.of("workout.adherence.scheduledDueCount:10"), // leaked fact!
                "ENGINE:composite-wm"
        );

        var result = validator.validate(response, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Side channel leak");
    }

    @Test
    @DisplayName("Side channel leakage: measurement is RESTRICTED but output contains measurement evaluations: rejected")
    void restrictedMeasurement_leakingMeasurementEvaluation_rejected() {
        F04InternalContextEnvelope envelope = createEnvelope("AVAILABLE", "RESTRICTED", "RECENT_ACTIVITY_OBSERVED", List.of());
        F04ProgressSummaryResponse response = createResponse(
                runId,
                "INFORMATION",
                new WorkoutProgressSummaryDto("Completed 8/8", "100%", "9000kg"),
                new MeasurementProgressSummaryDto(List.of(new MetricEvaluationDto("F_WEIGHT", "DECREASED", "Secret weight info"))),
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Consistent", false),
                List.of("workout.volume.knownVolumeKg:9000"),
                "ENGINE:composite-wm"
        );

        var result = validator.validate(response, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Side channel leak");
    }

    @Test
    @DisplayName("Safety boundary: prolonged inactivity (>= 100 days) requires safety alert in continuity evaluation")
    void prolongedInactivity_missingSafetyAlert_rejected() {
        F04InternalContextEnvelope envelope = createEnvelope(
                "NO_DATA", "NO_DATA", "PROLONGED_INACTIVITY", List.of("RETURN_AFTER_100_DAYS_V1")
        );
        F04ProgressSummaryResponse response = createResponse(
                runId,
                "INFORMATION",
                null, null,
                new ContinuityEvaluationDto("PROLONGED_INACTIVITY", "Inactivity 115 days", false), // missing safety alert!
                List.of(),
                "ENGINE:composite-wm"
        );

        var result = validator.validate(response, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Safety boundary violation");
    }

    @Test
    @DisplayName("Source watermark mismatch: rejected")
    void sourceWatermarkMismatch_rejected() {
        F04InternalContextEnvelope envelope = createEnvelope("AVAILABLE", "AVAILABLE", "RECENT_ACTIVITY_OBSERVED", List.of());
        F04ProgressSummaryResponse response = createResponse(
                runId,
                "INFORMATION",
                null, null,
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Consistent", false),
                List.of(),
                "ENGINE:different-watermark"
        );

        var result = validator.validate(response, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Source watermark mismatch");
    }
}
