package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.domain.F05SourceSnapshot;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class F05RuntimeOutputValidator {

    private final ObjectMapper objectMapper;

    public F05RuntimeOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ValidationResult(boolean isValid, String failureReason) {
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    public ValidationResult validate(String rawPayload, F05SourceSnapshot sourceSnapshot) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return ValidationResult.invalid("Payload is empty or null.");
        }
        if (sourceSnapshot == null) {
            return ValidationResult.invalid("Source snapshot is required for F05 output validation.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            return ValidationResult.invalid("Payload is not valid JSON: " + e.getMessage());
        }

        if (!root.isObject()) {
            return ValidationResult.invalid("Root must be a JSON object.");
        }

        // 1. Validate recommendationType
        if (!"WORKOUT_RESCHEDULE_PROPOSAL".equals(root.path("recommendationType").asText())) {
            return ValidationResult.invalid("recommendationType must be WORKOUT_RESCHEDULE_PROPOSAL");
        }

        // 2. Validate summary and confidence
        if (root.path("summary").asText("").isBlank()) {
            return ValidationResult.invalid("summary is required and must not be blank.");
        }
        if (!root.has("confidence") || root.path("confidence").asDouble(-1.0) < 0.0 || root.path("confidence").asDouble(-1.0) > 1.0) {
            return ValidationResult.invalid("confidence must be a number between 0.0 and 1.0.");
        }

        // 3. Validate reschedule container
        JsonNode rescheduleNode = root.path("reschedule");
        if (!rescheduleNode.isObject()) {
            return ValidationResult.invalid("reschedule object is required.");
        }

        String occurrenceIdStr = rescheduleNode.path("occurrenceId").asText();
        if (occurrenceIdStr.isBlank()) {
            return ValidationResult.invalid("occurrenceId is required in reschedule payload.");
        }
        try {
            UUID occurrenceId = UUID.fromString(occurrenceIdStr);
            if (!occurrenceId.equals(sourceSnapshot.occurrenceId())) {
                return ValidationResult.invalid("occurrenceId does not match source snapshot ("
                        + occurrenceId + " vs " + sourceSnapshot.occurrenceId() + ").");
            }
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid("occurrenceId is not a valid UUID: " + occurrenceIdStr);
        }

        // 4. Invariant T10: Supervision requirement cannot be degraded
        String outputSupervision = rescheduleNode.path("supervisionRequirement").asText("");
        if (outputSupervision.isBlank()) {
            return ValidationResult.invalid("supervisionRequirement is required in reschedule payload.");
        }
        if ("COACH_REQUIRED".equals(sourceSnapshot.supervisionRequirement()) && !"COACH_REQUIRED".equals(outputSupervision)) {
            return ValidationResult.invalid("COACH_REQUIRED supervision requirement cannot be degraded to " + outputSupervision);
        }

        // 5. Validate destination object
        JsonNode destNode = rescheduleNode.path("destination");
        if (!destNode.isObject()) {
            return ValidationResult.invalid("destination object is required in reschedule payload.");
        }

        String tzStr = destNode.path("timezone").asText("");
        if (tzStr.isBlank()) {
            return ValidationResult.invalid("destination.timezone is required.");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(tzStr);
        } catch (DateTimeException e) {
            return ValidationResult.invalid("destination.timezone is invalid: " + tzStr);
        }

        String localStartStr = destNode.path("localStart").asText("");
        String localEndStr = destNode.path("localEnd").asText("");
        if (localStartStr.isBlank() || localEndStr.isBlank()) {
            return ValidationResult.invalid("destination localStart and localEnd are required.");
        }

        LocalDateTime localStart;
        LocalDateTime localEnd;
        try {
            localStart = LocalDateTime.parse(localStartStr);
            localEnd = LocalDateTime.parse(localEndStr);
        } catch (DateTimeException e) {
            return ValidationResult.invalid("destination localStart or localEnd malformed: " + e.getMessage());
        }

        if (!localEnd.isAfter(localStart)) {
            return ValidationResult.invalid("destination localEnd must be strictly after localStart.");
        }

        String startOffsetStr = destNode.path("startUtcOffset").asText("");
        String endOffsetStr = destNode.path("endUtcOffset").asText("");
        if (startOffsetStr.isBlank() || endOffsetStr.isBlank()) {
            return ValidationResult.invalid("destination startUtcOffset and endUtcOffset are required.");
        }

        try {
            ZoneOffset startOffset = ZoneOffset.of(startOffsetStr);
            ZoneOffset endOffset = ZoneOffset.of(endOffsetStr);
            var startValid = zone.getRules().getValidOffsets(localStart);
            if (startValid.isEmpty()) {
                return ValidationResult.invalid("destination localStart falls in a DST transition gap (nonexistent local time).");
            }
            if (!startValid.contains(startOffset)) {
                return ValidationResult.invalid("startUtcOffset is not valid for timezone at localStart.");
            }
            var endValid = zone.getRules().getValidOffsets(localEnd);
            if (endValid.isEmpty()) {
                return ValidationResult.invalid("destination localEnd falls in a DST transition gap (nonexistent local time).");
            }
            if (!endValid.contains(endOffset)) {
                return ValidationResult.invalid("endUtcOffset is not valid for timezone at localEnd.");
            }
        } catch (DateTimeException e) {
            return ValidationResult.invalid("Invalid UTC offset format: " + e.getMessage());
        }

        String proposedStartStr = destNode.path("proposedStartAt").asText("");
        String proposedEndStr = destNode.path("proposedEndAt").asText("");
        if (proposedStartStr.isBlank() || proposedEndStr.isBlank()) {
            return ValidationResult.invalid("destination proposedStartAt and proposedEndAt are required.");
        }

        Instant proposedStart;
        Instant proposedEnd;
        try {
            proposedStart = Instant.parse(proposedStartStr);
            proposedEnd = Instant.parse(proposedEndStr);
        } catch (DateTimeException e) {
            return ValidationResult.invalid("proposedStartAt or proposedEndAt malformed ISO-8601: " + e.getMessage());
        }

        if (!proposedEnd.isAfter(proposedStart)) {
            return ValidationResult.invalid("proposedEndAt must be strictly after proposedStartAt.");
        }

        Instant now = Instant.now();
        if (proposedStart.isBefore(now.minusSeconds(120))) { // allow slight clock skew
            return ValidationResult.invalid("Proposed reschedule slot cannot be in the past.");
        }
        if (proposedStart.isAfter(now.plus(Duration.ofDays(90)))) {
            return ValidationResult.invalid("Proposed reschedule slot cannot exceed 90 days in the future.");
        }

        return ValidationResult.valid();
    }
}
