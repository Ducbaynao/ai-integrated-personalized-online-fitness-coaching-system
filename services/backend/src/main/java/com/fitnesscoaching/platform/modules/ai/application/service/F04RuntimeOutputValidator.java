package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates F04 AI progress summarization output against contract invariants, authority boundaries,
 * and data minimization rules before surfacing or persisting as readable result.
 */
@Component
public class F04RuntimeOutputValidator {

    private static final Logger log = LoggerFactory.getLogger(F04RuntimeOutputValidator.class);
    private static final Set<String> ALLOWED_RESULT_TYPES = Set.of("INFORMATION", "INSUFFICIENT_DATA", "BLOCKED");

    public record ValidationResult(boolean isValid, String failureReason) {
        public static ValidationResult valid() {
            return new ValidationResult(true, "PASSED");
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    public ValidationResult validate(F04ProgressSummaryResponse response, F04InternalContextEnvelope envelope) {
        if (response == null) {
            return ValidationResult.invalid("Response is null.");
        }
        if (envelope == null) {
            return ValidationResult.invalid("Context envelope is null.");
        }

        // 1. Run ID & Correlation ID Match
        if (!Objects.equals(envelope.requestId(), response.runId())) {
            return ValidationResult.invalid("Mismatched runId: expected " + envelope.requestId() + " but was " + response.runId());
        }

        // 2. ResultType Boundary: Strictly INFORMATION, INSUFFICIENT_DATA, or BLOCKED
        if (response.resultType() == null || !ALLOWED_RESULT_TYPES.contains(response.resultType())) {
            return ValidationResult.invalid("Invalid resultType: '" + response.resultType()
                    + "'. F04 is an INFORMATION capability and strictly forbids PROPOSAL or ESTIMATE result types.");
        }

        // 3. Side-channel protection for Restricted Workout source
        var workoutCtx = envelope.context().workout();
        if ("RESTRICTED".equalsIgnoreCase(workoutCtx.availability())) {
            if (response.workoutProgressSummary() != null && (
                    response.workoutProgressSummary().completionEvaluation() != null
                    || response.workoutProgressSummary().adherenceEvaluation() != null
                    || response.workoutProgressSummary().volumeEvaluation() != null)) {
                return ValidationResult.invalid("Side channel leak: workoutProgressSummary contains evaluations despite workout source being RESTRICTED.");
            }
            if (response.factsReferenced() != null) {
                boolean leaked = response.factsReferenced().stream().anyMatch(f -> f != null && f.toLowerCase().startsWith("workout."));
                if (leaked) {
                    return ValidationResult.invalid("Side channel leak: factsReferenced references workout facts despite workout source being RESTRICTED.");
                }
            }
        }

        // 4. Side-channel protection for Restricted Measurement source
        var measCtx = envelope.context().measurement();
        if ("RESTRICTED".equalsIgnoreCase(measCtx.availability())) {
            if (response.measurementProgressSummary() != null
                    && response.measurementProgressSummary().metricsEvaluations() != null
                    && !response.measurementProgressSummary().metricsEvaluations().isEmpty()) {
                return ValidationResult.invalid("Side channel leak: measurementProgressSummary contains evaluations despite measurement source being RESTRICTED.");
            }
            if (response.factsReferenced() != null) {
                boolean leaked = response.factsReferenced().stream().anyMatch(f -> f != null && f.toLowerCase().startsWith("measurement."));
                if (leaked) {
                    return ValidationResult.invalid("Side channel leak: factsReferenced references measurement facts despite measurement source being RESTRICTED.");
                }
            }
        }

        // 5. Prolonged Inactivity Safety Boundary (>= 100 days)
        var contCtx = envelope.context().continuity();
        boolean isProlonged = "PROLONGED_INACTIVITY".equalsIgnoreCase(contCtx.state())
                || (contCtx.safetyFlags() != null && contCtx.safetyFlags().contains("RETURN_AFTER_100_DAYS_V1"));

        if (isProlonged) {
            if (response.continuityEvaluation() == null || !response.continuityEvaluation().hasSafetyAlert()) {
                return ValidationResult.invalid("Safety boundary violation: prolonged inactivity (>= 100 days) was present in context but output omitted safety alert flag.");
            }
        }

        // 6. Source Watermark Integrity
        if (response.sourceWatermark() != null && !Objects.equals(envelope.context().compositeWatermark(), response.sourceWatermark())) {
            return ValidationResult.invalid("Source watermark mismatch between context and response.");
        }

        return ValidationResult.valid();
    }
}
