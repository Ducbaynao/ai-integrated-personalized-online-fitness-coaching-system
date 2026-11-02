package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.ChangeType;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F03AiServiceClient;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SimulatedF03AiServiceClient implements F03AiServiceClient {

    private final ObjectMapper objectMapper;
    private final ExerciseReferenceQuery exerciseReferenceQuery;

    public SimulatedF03AiServiceClient(ObjectMapper objectMapper, ExerciseReferenceQuery exerciseReferenceQuery) {
        this.objectMapper = objectMapper;
        this.exerciseReferenceQuery = exerciseReferenceQuery;
    }

    @Override
    public String generateAdjustmentJson(F03ServiceRequest request) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("recommendationType", "WORKOUT_ADJUSTMENT_PROPOSAL");
            root.put("summary", "Personalized workout adjustment based on training status and user request.");
            root.put("reasoning", List.of(
                    "Context evaluated for trainee " + request.studentId(),
                    "Target exercise evaluated against recent execution and load capacity."
            ));
            root.put("usedData", List.of(
                    "scope: " + request.scope(),
                    "requestedChangeType: " + request.requestedChangeType(),
                    "allowLoadIncrease: " + request.allowLoadIncrease(),
                    "continuityState: " + (request.continuityState() != null ? request.continuityState().name() : "CONTINUOUS")
            ));
            root.put("missingData", request.allowLoadIncrease() ? List.of() : List.of("recent_exercise_rpe_logs"));
            root.put("limitations", List.of(
                    "Single adjustment per proposal (Phase 2 release scope baseline).",
                    "Loads must be self-regulated using RPE guidelines."
            ));
            root.put("confidence", 0.92);

            Map<String, Object> change = new LinkedHashMap<>();
            change.put("changeId", UUID.randomUUID().toString());
            change.put("changeType", request.requestedChangeType().name());
            change.put("scope", request.scope().name());

            // TargetRef
            Map<String, Object> targetRef = new LinkedHashMap<>();
            targetRef.put("scope", request.scope().name());
            if (request.scope() == TargetScope.ONE_MATERIALIZED_OCCURRENCE) {
                targetRef.put("plannedWorkoutId", request.sourceSnapshot().plannedWorkoutId().toString());
                targetRef.put("plannedSessionExerciseId", request.sourceSnapshot().plannedSessionExerciseId().toString());
            } else {
                targetRef.put("planId", request.sourceSnapshot().planId().toString());
                targetRef.put("planVersionNumber", request.sourceSnapshot().planVersionNumber());
                targetRef.put("exercisePrescriptionId", request.sourceSnapshot().exercisePrescriptionId().toString());
            }
            change.put("targetRef", targetRef);

            // VersionGuards
            Map<String, Object> versionGuards = new LinkedHashMap<>();
            if (request.scope() == TargetScope.ONE_MATERIALIZED_OCCURRENCE) {
                versionGuards.put("expectedOccurrenceVersion", request.sourceSnapshot().occurrenceVersion());
            } else {
                versionGuards.put("expectedPlanAggregateVersion", request.sourceSnapshot().planAggregateVersion());
            }
            change.put("versionGuards", versionGuards);

            // Before / After according to ChangeType
            BaselinePrescription base = request.baselinePrescription();
            if (request.requestedChangeType() == ChangeType.EXERCISE_SUBSTITUTION) {
                Map<String, Object> before = new LinkedHashMap<>();
                before.put("exerciseVariationId", base.exerciseVariationId().toString());
                before.put("exerciseName", base.exerciseName());
                before.put("movementPattern", base.movementPattern());
                before.put("equipmentType", base.equipmentType());
                change.put("before", before);

                // Find replacement variation that shares base exercise or is active
                UUID replacementVarId = UUID.fromString("b0000000-0000-4000-8000-000000000002");
                if (replacementVarId.equals(base.exerciseVariationId())) {
                    replacementVarId = UUID.fromString("b0000000-0000-4000-8000-000000000001");
                }
                String repName = "Alternative Variation (" + base.exerciseName() + ")";
                if (exerciseReferenceQuery != null) {
                    var histOpt = exerciseReferenceQuery.resolveHistorical(base.exerciseVariationId());
                    if (histOpt.isPresent()) {
                        UUID baseExerciseId = histOpt.get().exerciseId();
                        // Query authoring references to see if another variation exists
                        // If none easily resolvable, use test fallback sibling
                        repName = histOpt.get().exerciseName() + " (Alternative)";
                    }
                }

                Map<String, Object> after = new LinkedHashMap<>();
                after.put("replacementVariationId", replacementVarId.toString());
                after.put("replacementExerciseName", repName);
                after.put("movementPattern", base.movementPattern());
                after.put("equipmentType", base.equipmentType());
                after.put("substitutionCategory", "EQUIPMENT_UNAVAILABLE");
                change.put("after", after);
                change.put("reason", "Alternative equipment substituted due to availability or preferences.");

            } else if (request.requestedChangeType() == ChangeType.LOAD_ADJUSTMENT) {
                BigDecimal beforeLoad = base.targetLoad() != null ? base.targetLoad() : BigDecimal.valueOf(20.0);
                Map<String, Object> before = new LinkedHashMap<>();
                before.put("targetLoad", beforeLoad);
                before.put("loadUnit", "KG");
                before.put("loadUnitId", 1);
                change.put("before", before);

                Map<String, Object> after = new LinkedHashMap<>();
                after.put("loadUnit", "KG");
                after.put("loadUnitId", 1);

                if (request.allowLoadIncrease()) {
                    BigDecimal delta = BigDecimal.valueOf(2.5);
                    BigDecimal targetLoad = beforeLoad.add(delta);
                    BigDecimal deltaPercent = beforeLoad.compareTo(BigDecimal.ZERO) > 0
                            ? delta.divide(beforeLoad, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                            : BigDecimal.valueOf(10.0);
                    after.put("targetLoad", targetLoad);
                    after.put("direction", "INCREASE");
                    after.put("deltaLoad", delta);
                    after.put("deltaPercent", deltaPercent.setScale(1, RoundingMode.HALF_UP));
                    change.put("reason", "Gradual load increase calibrated to progressive overload criteria.");
                } else {
                    BigDecimal delta = BigDecimal.valueOf(-2.5);
                    BigDecimal targetLoad = beforeLoad.subtract(BigDecimal.valueOf(2.5)).max(BigDecimal.ZERO);
                    BigDecimal deltaPercent = beforeLoad.compareTo(BigDecimal.ZERO) > 0
                            ? delta.divide(beforeLoad, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                            : BigDecimal.valueOf(-10.0);
                    after.put("targetLoad", targetLoad);
                    after.put("direction", "DECREASE");
                    after.put("deltaLoad", delta);
                    after.put("deltaPercent", deltaPercent.setScale(1, RoundingMode.HALF_UP));
                    change.put("reason", "Load reduction recommended for fatigue management and form safety.");
                }
                change.put("after", after);

            } else {
                // VOLUME_ADJUSTMENT
                int beforeSets = base.targetSets() != null ? base.targetSets() : 3;
                int beforeRepsMin = base.targetRepsMin() != null ? base.targetRepsMin() : 8;
                int beforeRepsMax = base.targetRepsMax() != null ? base.targetRepsMax() : 12;

                Map<String, Object> before = new LinkedHashMap<>();
                before.put("targetSets", beforeSets);
                before.put("targetRepsMin", beforeRepsMin);
                before.put("targetRepsMax", beforeRepsMax);
                if (base.durationSeconds() != null) {
                    before.put("durationSeconds", base.durationSeconds());
                }
                change.put("before", before);

                Map<String, Object> after = new LinkedHashMap<>();
                if (request.allowLoadIncrease()) {
                    int targetSets = Math.min(20, beforeSets + 1);
                    after.put("targetSets", targetSets);
                    after.put("targetRepsMin", beforeRepsMin);
                    after.put("targetRepsMax", beforeRepsMax);
                    after.put("deltaSets", targetSets - beforeSets);
                    after.put("deltaRepsMin", 0);
                    after.put("deltaRepsMax", 0);
                    change.put("reason", "Added set volume for training stimulus progression.");
                } else {
                    int targetSets = Math.max(1, beforeSets - 1);
                    after.put("targetSets", targetSets);
                    after.put("targetRepsMin", beforeRepsMin);
                    after.put("targetRepsMax", beforeRepsMax);
                    after.put("deltaSets", targetSets - beforeSets);
                    after.put("deltaRepsMin", 0);
                    after.put("deltaRepsMax", 0);
                    change.put("reason", "Volume reduction applied for active recovery and session capacity adaptation.");
                }
                if (base.durationSeconds() != null) {
                    after.put("durationSeconds", base.durationSeconds());
                    after.put("deltaDurationSeconds", 0);
                }
                change.put("after", after);
            }

            // Evidence
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("movementPatternMatch", true);
            evidence.put("equipmentAvailable", true);
            evidence.put("rpeWindowCoverage", "LAST_5_SESSIONS");
            evidence.put("continuityState", request.continuityState() != null ? request.continuityState().name() : "CONTINUOUS");
            evidence.put("knowledgeCitations", List.of("NSCA Resistance Training Guidelines Section 4"));
            change.put("evidence", evidence);

            root.put("changes", List.of(change));

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate simulated F03 proposal: " + e.getMessage(), e);
        }
    }
}
