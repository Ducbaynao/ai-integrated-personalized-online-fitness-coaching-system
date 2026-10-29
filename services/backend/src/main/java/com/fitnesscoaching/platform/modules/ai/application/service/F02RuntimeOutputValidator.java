package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class F02RuntimeOutputValidator {

    private final ObjectMapper objectMapper;
    private final ExerciseReferenceQuery exerciseReferenceQuery;

    public F02RuntimeOutputValidator(ObjectMapper objectMapper, ExerciseReferenceQuery exerciseReferenceQuery) {
        this.objectMapper = objectMapper;
        this.exerciseReferenceQuery = exerciseReferenceQuery;
    }

    public record ValidationResult(boolean isValid, String failureReason, List<UUID> variationIds) {
        public static ValidationResult valid(List<UUID> variationIds) {
            return new ValidationResult(true, null, variationIds);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason, List.of());
        }
    }

    public ValidationResult validate(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return ValidationResult.invalid("Payload is empty or null.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            return ValidationResult.invalid("Malformed JSON payload: " + e.getMessage());
        }

        if (!"WORKOUT_PLAN_PROPOSAL".equals(root.path("recommendationType").asText())) {
            return ValidationResult.invalid("recommendationType must be WORKOUT_PLAN_PROPOSAL");
        }

        JsonNode plan = root.path("plan");
        if (plan.isMissingNode() || !plan.isObject()) {
            return ValidationResult.invalid("Missing 'plan' object in proposal.");
        }

        String planName = plan.path("name").asText();
        if (planName == null || planName.isBlank() || planName.length() > 200) {
            return ValidationResult.invalid("Plan name is invalid or exceeds 200 characters.");
        }

        int durationWeeks = plan.path("durationWeeks").asInt(0);
        if (durationWeeks != 1) {
            return ValidationResult.invalid("durationWeeks must be exactly 1 for Phase 2 F02 release scope (D02).");
        }

        JsonNode sessionsNode = plan.path("sessions");
        if (!sessionsNode.isArray() || sessionsNode.isEmpty() || sessionsNode.size() > 7) {
            return ValidationResult.invalid("Plan must contain between 1 and 7 sessions.");
        }

        Set<String> sessionKeys = new HashSet<>();
        List<UUID> variationIds = new ArrayList<>();

        for (int i = 0; i < sessionsNode.size(); i++) {
            JsonNode session = sessionsNode.get(i);
            int weekNumber = session.path("weekNumber").asInt(0);
            if (weekNumber != 1) {
                return ValidationResult.invalid("Session weekNumber must be 1.");
            }

            int dayNumber = session.path("dayNumber").asInt(0);
            if (dayNumber < 1 || dayNumber > 7) {
                return ValidationResult.invalid("Session dayNumber must be between 1 and 7.");
            }

            int seqNumber = session.path("sequenceNumber").asInt(0);
            if (seqNumber < 1) {
                return ValidationResult.invalid("Session sequenceNumber must be >= 1.");
            }

            String sessionKey = weekNumber + ":" + dayNumber + ":" + seqNumber;
            if (!sessionKeys.add(sessionKey)) {
                return ValidationResult.invalid("Duplicate session slot: " + sessionKey);
            }

            String sessionName = session.path("name").asText();
            if (sessionName == null || sessionName.isBlank() || sessionName.length() > 180) {
                return ValidationResult.invalid("Session name must be between 1 and 180 characters.");
            }

            JsonNode prescriptionsNode = session.path("prescriptions");
            if (!prescriptionsNode.isArray() || prescriptionsNode.isEmpty()) {
                return ValidationResult.invalid("Session '" + sessionName + "' must contain at least one exercise prescription.");
            }

            Set<Integer> exerciseSeqs = new HashSet<>();
            for (int j = 0; j < prescriptionsNode.size(); j++) {
                JsonNode prescription = prescriptionsNode.get(j);
                String variationIdStr = prescription.path("exerciseVariationId").asText();
                UUID variationId;
                try {
                    variationId = UUID.fromString(variationIdStr);
                } catch (Exception e) {
                    return ValidationResult.invalid("Invalid exerciseVariationId UUID format: " + variationIdStr);
                }
                variationIds.add(variationId);

                int exSeq = prescription.path("sequenceNumber").asInt(0);
                if (exSeq < 1 || !exerciseSeqs.add(exSeq)) {
                    return ValidationResult.invalid("Invalid or duplicate exercise sequenceNumber in session: " + exSeq);
                }

                int targetSets = prescription.path("targetSets").asInt(0);
                if (targetSets < 1 || targetSets > 20) {
                    return ValidationResult.invalid("targetSets must be between 1 and 20.");
                }

                if (prescription.has("targetRepsMin") && prescription.get("targetRepsMin").asInt() < 0) {
                    return ValidationResult.invalid("targetRepsMin cannot be negative.");
                }
                if (prescription.has("targetRepsMax") && prescription.get("targetRepsMax").asInt() < 0) {
                    return ValidationResult.invalid("targetRepsMax cannot be negative.");
                }
                if (prescription.has("targetRepsMin") && prescription.has("targetRepsMax")
                        && prescription.get("targetRepsMax").asInt() < prescription.get("targetRepsMin").asInt()) {
                    return ValidationResult.invalid("targetRepsMax cannot be less than targetRepsMin.");
                }
            }
        }

        // Verify active exercise catalog references
        long uniqueRequested = variationIds.stream().distinct().count();
        var lockedRefs = exerciseReferenceQuery.lockAuthoringReferences(variationIds);
        if (lockedRefs.size() != uniqueRequested) {
            return ValidationResult.invalid("WORKOUT_PLAN_EXERCISE_UNAVAILABLE: One or more exercise variations do not exist or are inactive.");
        }

        return ValidationResult.valid(variationIds);
    }
}
