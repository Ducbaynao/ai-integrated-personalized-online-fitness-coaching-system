package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;
import com.fitnesscoaching.platform.modules.ai.domain.F03SourceSnapshot;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementUnitReferenceQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class F03RuntimeOutputValidator {

    private final ObjectMapper objectMapper;
    private final ExerciseReferenceQuery exerciseReferenceQuery;
    private final MeasurementUnitReferenceQuery unitReferenceQuery;

    @Autowired
    public F03RuntimeOutputValidator(
            ObjectMapper objectMapper,
            ExerciseReferenceQuery exerciseReferenceQuery,
            ObjectProvider<MeasurementUnitReferenceQuery> unitReferenceQueryProvider
    ) {
        this.objectMapper = objectMapper;
        this.exerciseReferenceQuery = exerciseReferenceQuery;
        this.unitReferenceQuery = unitReferenceQueryProvider != null ? unitReferenceQueryProvider.getIfAvailable() : null;
    }

    public F03RuntimeOutputValidator(
            ObjectMapper objectMapper,
            ExerciseReferenceQuery exerciseReferenceQuery,
            MeasurementUnitReferenceQuery unitReferenceQuery
    ) {
        this.objectMapper = objectMapper;
        this.exerciseReferenceQuery = exerciseReferenceQuery;
        this.unitReferenceQuery = unitReferenceQuery;
    }

    public F03RuntimeOutputValidator(
            ObjectMapper objectMapper,
            ExerciseReferenceQuery exerciseReferenceQuery
    ) {
        this(objectMapper, exerciseReferenceQuery, (MeasurementUnitReferenceQuery) null);
    }

    public record ValidationResult(boolean isValid, String failureReason) {
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    public ValidationResult validate(String rawPayload, F03SourceSnapshot sourceSnapshot) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return ValidationResult.invalid("Payload is empty or null.");
        }
        if (sourceSnapshot == null) {
            return ValidationResult.invalid("Source snapshot is required for F03 output validation.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            return ValidationResult.invalid("Malformed JSON payload: " + e.getMessage());
        }

        if (!"WORKOUT_ADJUSTMENT_PROPOSAL".equals(root.path("recommendationType").asText())) {
            return ValidationResult.invalid("recommendationType must be WORKOUT_ADJUSTMENT_PROPOSAL");
        }

        JsonNode changesNode = root.path("changes");
        if (!changesNode.isArray() || changesNode.isEmpty()) {
            return ValidationResult.invalid("Proposal must contain at least one change.");
        }
        if (changesNode.size() > 1) {
            return ValidationResult.invalid("Phase 2 release scope (F03) permits exactly one targeted adjustment per proposal.");
        }

        JsonNode change = changesNode.get(0);
        String changeTypeStr = change.path("changeType").asText();
        if (changeTypeStr == null || changeTypeStr.isBlank()) {
            return ValidationResult.invalid("changeType is required.");
        }

        String scopeStr = change.path("scope").asText();
        if (scopeStr == null || !scopeStr.equals(sourceSnapshot.scope().name())) {
            return ValidationResult.invalid("Change scope '" + scopeStr + "' does not match context scope '" + sourceSnapshot.scope().name() + "'.");
        }

        JsonNode targetRef = change.path("targetRef");
        if (targetRef.isMissingNode() || !targetRef.isObject()) {
            return ValidationResult.invalid("Missing 'targetRef' in adjustment change.");
        }

        // Validate TargetRef and VersionGuards matching sourceSnapshot
        JsonNode versionGuards = change.path("versionGuards");
        if (sourceSnapshot.scope() == TargetScope.ONE_MATERIALIZED_OCCURRENCE) {
            String plannedWorkoutIdStr = targetRef.path("plannedWorkoutId").asText();
            String plannedSessionExerciseIdStr = targetRef.path("plannedSessionExerciseId").asText();
            if (!Objects.equals(sourceSnapshot.plannedWorkoutId().toString(), plannedWorkoutIdStr)) {
                return ValidationResult.invalid("Target plannedWorkoutId does not match context.");
            }
            if (!Objects.equals(sourceSnapshot.plannedSessionExerciseId().toString(), plannedSessionExerciseIdStr)) {
                return ValidationResult.invalid("Target plannedSessionExerciseId does not match context.");
            }

            if (versionGuards.isMissingNode() || !versionGuards.has("expectedOccurrenceVersion")) {
                return ValidationResult.invalid("Missing expectedOccurrenceVersion in versionGuards for occurrence scope.");
            }
            long expectedOccVersion = versionGuards.path("expectedOccurrenceVersion").asLong();
            if (expectedOccVersion != sourceSnapshot.occurrenceVersion()) {
                return ValidationResult.invalid("expectedOccurrenceVersion (" + expectedOccVersion
                        + ") does not match context occurrenceVersion (" + sourceSnapshot.occurrenceVersion() + ").");
            }
        } else {
            // TEMPLATE_OR_FUTURE_OCCURRENCES
            String planIdStr = targetRef.path("planId").asText();
            int planVersionNumber = targetRef.path("planVersionNumber").asInt();
            String exercisePrescriptionIdStr = targetRef.path("exercisePrescriptionId").asText();
            if (!Objects.equals(sourceSnapshot.planId().toString(), planIdStr)) {
                return ValidationResult.invalid("Target planId does not match context active plan.");
            }
            if (sourceSnapshot.planVersionNumber() != planVersionNumber) {
                return ValidationResult.invalid("Target planVersionNumber does not match context planVersionNumber.");
            }
            if (!Objects.equals(sourceSnapshot.exercisePrescriptionId().toString(), exercisePrescriptionIdStr)) {
                return ValidationResult.invalid("Target exercisePrescriptionId does not match context.");
            }

            if (versionGuards.isMissingNode() || !versionGuards.has("expectedPlanAggregateVersion")) {
                return ValidationResult.invalid("Missing expectedPlanAggregateVersion in versionGuards for template scope.");
            }
            long expectedAggVersion = versionGuards.path("expectedPlanAggregateVersion").asLong();
            if (expectedAggVersion != sourceSnapshot.planAggregateVersion()) {
                return ValidationResult.invalid("expectedPlanAggregateVersion (" + expectedAggVersion
                        + ") does not match context planAggregateVersion (" + sourceSnapshot.planAggregateVersion() + ").");
            }
        }

        JsonNode beforeNode = change.path("before");
        JsonNode afterNode = change.path("after");
        if (beforeNode.isMissingNode() || afterNode.isMissingNode()) {
            return ValidationResult.invalid("Both 'before' and 'after' payloads are required.");
        }

        // Validate Specific Change Types
        switch (changeTypeStr) {
            case "EXERCISE_SUBSTITUTION" -> {
                String beforeVarStr = beforeNode.path("exerciseVariationId").asText();
                if (!Objects.equals(sourceSnapshot.beforeExerciseVariationId().toString(), beforeVarStr)) {
                    return ValidationResult.invalid("before.exerciseVariationId does not match context baseline.");
                }

                String replacementVarStr = afterNode.path("replacementVariationId").asText();
                if (replacementVarStr == null || replacementVarStr.isBlank()) {
                    return ValidationResult.invalid("replacementVariationId is required.");
                }
                UUID replacementVarId;
                try {
                    replacementVarId = UUID.fromString(replacementVarStr);
                } catch (Exception e) {
                    return ValidationResult.invalid("Invalid UUID for replacementVariationId: " + replacementVarStr);
                }

                if (replacementVarId.equals(sourceSnapshot.beforeExerciseVariationId())) {
                    return ValidationResult.invalid("replacementVariationId must not equal the original exerciseVariationId.");
                }

                // Verify active catalog status
                var activeRefs = exerciseReferenceQuery.lockAuthoringReferences(List.of(replacementVarId));
                if (activeRefs.isEmpty()) {
                    return ValidationResult.invalid("WORKOUT_PLAN_EXERCISE_UNAVAILABLE: Replacement exercise variation does not exist or is inactive.");
                }

                // OD-3 Equivalence Verification
                var beforeHistOpt = exerciseReferenceQuery.resolveHistorical(sourceSnapshot.beforeExerciseVariationId());
                var afterHistOpt = exerciseReferenceQuery.resolveHistorical(replacementVarId);
                if (beforeHistOpt.isPresent() && afterHistOpt.isPresent()) {
                    var bHist = beforeHistOpt.get();
                    var aHist = afterHistOpt.get();
                    boolean sameBaseExercise = Objects.equals(bHist.exerciseId(), aHist.exerciseId());
                    boolean canonicalMatch = (bHist.canonicalExerciseId() != null && Objects.equals(bHist.canonicalExerciseId(), aHist.canonicalExerciseId()))
                            || (bHist.canonicalExerciseId() != null && Objects.equals(bHist.canonicalExerciseId(), aHist.exerciseId()))
                            || (aHist.canonicalExerciseId() != null && Objects.equals(aHist.canonicalExerciseId(), bHist.exerciseId()));

                    if (!sameBaseExercise && !canonicalMatch) {
                        return ValidationResult.invalid("UNVERIFIED_SUBSTITUTION: Replacement variation is not verified as equivalent to target exercise; specialist review required under OD-3.");
                    }
                }
            }

            case "LOAD_ADJUSTMENT" -> {
                if (sourceSnapshot.beforeTargetLoad() != null && beforeNode.has("targetLoad")) {
                    if (!beforeNode.path("targetLoad").isNull()) {
                        BigDecimal beforeLoad = new BigDecimal(beforeNode.path("targetLoad").asText());
                        if (beforeLoad.compareTo(sourceSnapshot.beforeTargetLoad()) != 0) {
                            return ValidationResult.invalid("before.targetLoad does not match context baseline.");
                        }
                    } else {
                        return ValidationResult.invalid("before.targetLoad cannot be null when context baseline has a target load.");
                    }
                }

                String direction = afterNode.path("direction").asText();
                if (!"INCREASE".equals(direction) && !"DECREASE".equals(direction) && !"MAINTAIN".equals(direction)) {
                    return ValidationResult.invalid("direction must be INCREASE, DECREASE, or MAINTAIN.");
                }

                boolean isBaselineUnknown = !beforeNode.has("targetLoad") || beforeNode.path("targetLoad").isNull()
                        || sourceSnapshot.beforeTargetLoad() == null;

                if (isBaselineUnknown) {
                    if (!"MAINTAIN".equals(direction)) {
                        return ValidationResult.invalid("Cannot calculate load delta or adjust load when baseline load is unknown or missing.");
                    }
                    if (afterNode.hasNonNull("deltaLoad") && afterNode.path("deltaLoad").asDouble() != 0) {
                        return ValidationResult.invalid("deltaLoad must be null or 0 when maintaining bodyweight or unknown load.");
                    }
                    break;
                }

                BigDecimal beforeLoad = new BigDecimal(beforeNode.path("targetLoad").asText());
                BigDecimal targetLoad = afterNode.has("targetLoad") && !afterNode.path("targetLoad").isNull()
                        ? new BigDecimal(afterNode.path("targetLoad").asText())
                        : null;
                BigDecimal deltaLoad = afterNode.has("deltaLoad") && !afterNode.path("deltaLoad").isNull()
                        ? new BigDecimal(afterNode.path("deltaLoad").asText())
                        : null;

                if (targetLoad == null) {
                    if (!"MAINTAIN".equals(direction)) {
                        return ValidationResult.invalid("targetLoad is required when direction is " + direction + ".");
                    }
                    break;
                }

                // Authoritative unit verification and consistency
                String rawBeforeUnit = beforeNode.hasNonNull("loadUnit") ? beforeNode.path("loadUnit").asText(null) : null;
                String beforeUnit = canonicalUnitCode(rawBeforeUnit);
                Integer beforeUnitId = beforeNode.hasNonNull("loadUnitId") ? beforeNode.path("loadUnitId").intValue() : null;

                ValidationResult beforeUnitVal = validateUnitConsistency(
                        "before", beforeUnit, beforeUnitId, sourceSnapshot.beforeLoadUnitId()
                );
                if (!beforeUnitVal.isValid()) {
                    return beforeUnitVal;
                }
                if (beforeUnit == null && beforeUnitId != null && unitReferenceQuery != null) {
                    var ref = unitReferenceQuery.resolveAll(List.of(beforeUnitId.shortValue())).get(beforeUnitId.shortValue());
                    if (ref != null) {
                        beforeUnit = canonicalUnitCode(ref.code());
                    }
                }

                String rawAfterUnit = afterNode.hasNonNull("loadUnit") ? afterNode.path("loadUnit").asText(null) : null;
                String afterUnit = canonicalUnitCode(rawAfterUnit);
                Integer afterUnitId = afterNode.hasNonNull("loadUnitId") ? afterNode.path("loadUnitId").intValue() : null;

                ValidationResult afterUnitVal = validateUnitConsistency(
                        "after", afterUnit, afterUnitId, null
                );
                if (!afterUnitVal.isValid()) {
                    return afterUnitVal;
                }
                if (afterUnit == null && afterUnitId != null && unitReferenceQuery != null) {
                    var ref = unitReferenceQuery.resolveAll(List.of(afterUnitId.shortValue())).get(afterUnitId.shortValue());
                    if (ref != null) {
                        afterUnit = canonicalUnitCode(ref.code());
                    }
                }
                if (afterUnit == null && afterUnitId == null) {
                    afterUnit = beforeUnit;
                }

                BigDecimal beforeInAfterUnit = beforeLoad;
                if (beforeUnit != null && afterUnit != null && !beforeUnit.equalsIgnoreCase(afterUnit)) {
                    if (unitReferenceQuery != null) {
                        var detailsMap = unitReferenceQuery.resolveByCodes(List.of(beforeUnit, afterUnit));
                        var bDet = detailsMap.get(beforeUnit);
                        var aDet = detailsMap.get(afterUnit);
                        if (bDet == null || aDet == null) {
                            return ValidationResult.invalid("Unsupported unit conversion between " + beforeUnit + " and " + afterUnit);
                        }
                        if (!"MASS".equalsIgnoreCase(bDet.dimension()) || !"MASS".equalsIgnoreCase(aDet.dimension())) {
                            return ValidationResult.invalid("Incompatible unit dimensions for conversion: " + bDet.dimension() + " vs " + aDet.dimension());
                        }
                        BigDecimal inBase = beforeLoad.multiply(bDet.multiplierToBase()).add(bDet.offsetToBase());
                        beforeInAfterUnit = inBase.subtract(aDet.offsetToBase()).divide(aDet.multiplierToBase(), 4, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
                    } else {
                        if ("LB".equals(beforeUnit) && "KG".equals(afterUnit)) {
                            beforeInAfterUnit = beforeLoad.multiply(new BigDecimal("0.45359237")).setScale(2, RoundingMode.HALF_UP);
                        } else if ("KG".equals(beforeUnit) && "LB".equals(afterUnit)) {
                            beforeInAfterUnit = beforeLoad.divide(new BigDecimal("0.45359237"), 4, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
                        } else {
                            return ValidationResult.invalid("Incompatible unit dimensions or unsupported unit conversion: " + beforeUnit + " to " + afterUnit);
                        }
                    }
                }

                BigDecimal computedDelta = targetLoad.subtract(beforeInAfterUnit);
                if (deltaLoad != null && computedDelta.compareTo(deltaLoad) != 0) {
                    return ValidationResult.invalid("deltaLoad (" + deltaLoad + ") does not match targetLoad - beforeLoad (" + computedDelta + ").");
                }

                if ("INCREASE".equals(direction)) {
                    if (computedDelta.compareTo(BigDecimal.ZERO) <= 0) {
                        return ValidationResult.invalid("deltaLoad must be strictly positive when direction is INCREASE.");
                    }
                    if (!sourceSnapshot.allowLoadIncrease()) {
                        return ValidationResult.invalid("LOAD_INCREASE_NOT_PERMITTED: Load increase is prohibited due to lack of recent RPE confirmation or training continuity constraints.");
                    }
                } else if ("DECREASE".equals(direction)) {
                    if (computedDelta.compareTo(BigDecimal.ZERO) >= 0) {
                        return ValidationResult.invalid("deltaLoad must be strictly negative when direction is DECREASE.");
                    }
                    if (targetLoad.compareTo(BigDecimal.ZERO) < 0) {
                        return ValidationResult.invalid("targetLoad cannot be negative.");
                    }
                } else {
                    if (computedDelta.compareTo(BigDecimal.ZERO) != 0) {
                        return ValidationResult.invalid("deltaLoad must be 0 when direction is MAINTAIN.");
                    }
                }
            }

            case "VOLUME_ADJUSTMENT" -> {
                int beforeSets = beforeNode.path("targetSets").asInt(0);
                if (sourceSnapshot.beforeTargetSets() != null && beforeSets != sourceSnapshot.beforeTargetSets()) {
                    return ValidationResult.invalid("before.targetSets does not match context baseline.");
                }

                int targetSets = afterNode.path("targetSets").asInt(0);
                if (targetSets < 1 || targetSets > 20) {
                    return ValidationResult.invalid("targetSets must be between 1 and 20.");
                }

                int deltaSets = afterNode.path("deltaSets").asInt(0);
                if (targetSets - beforeSets != deltaSets) {
                    return ValidationResult.invalid("deltaSets does not match targetSets - beforeSets.");
                }

                int targetRepsMin = afterNode.path("targetRepsMin").asInt(-1);
                int targetRepsMax = afterNode.path("targetRepsMax").asInt(-1);
                if (targetRepsMin >= 0 && targetRepsMax >= 0 && targetRepsMax < targetRepsMin) {
                    return ValidationResult.invalid("targetRepsMax cannot be less than targetRepsMin.");
                }

                // Check volume increase prohibition
                boolean isVolumeIncrease = deltaSets > 0
                        || (afterNode.has("deltaRepsMin") && afterNode.path("deltaRepsMin").asInt() > 0)
                        || (afterNode.has("deltaRepsMax") && afterNode.path("deltaRepsMax").asInt() > 0);

                if (isVolumeIncrease && !sourceSnapshot.allowLoadIncrease()) {
                    return ValidationResult.invalid("VOLUME_INCREASE_NOT_PERMITTED: Volume increase is prohibited due to lack of recent RPE confirmation or training continuity constraints.");
                }
            }

            default -> {
                return ValidationResult.invalid("Unsupported changeType: " + changeTypeStr);
            }
        }

        return ValidationResult.valid();
    }

    private ValidationResult validateUnitConsistency(
            String role,
            String canonicalUnit,
            Integer unitId,
            Integer expectedBaselineUnitId
    ) {
        if (unitReferenceQuery == null) {
            return ValidationResult.valid();
        }

        if (unitId != null) {
            var refs = unitReferenceQuery.resolveAll(List.of(unitId.shortValue()));
            var ref = refs.get(unitId.shortValue());
            if (ref == null) {
                return ValidationResult.invalid(role + ".loadUnitId " + unitId + " does not exist in measurement units catalog.");
            }
            if (!"MASS".equalsIgnoreCase(ref.dimension())) {
                return ValidationResult.invalid(role + ".loadUnitId " + unitId + " dimension must be MASS, but was " + ref.dimension() + ".");
            }
            String refCanonical = canonicalUnitCode(ref.code());
            if (canonicalUnit != null && !canonicalUnit.equalsIgnoreCase(refCanonical)) {
                return ValidationResult.invalid("Conflicting " + role + " loadUnit code and ID: code '" + canonicalUnit + "' does not match unit ID " + unitId + " ('" + ref.code() + "').");
            }
            if (expectedBaselineUnitId != null && !expectedBaselineUnitId.equals(unitId)) {
                return ValidationResult.invalid(role + ".loadUnitId " + unitId + " does not match context baseline loadUnitId " + expectedBaselineUnitId + ".");
            }
        } else if (canonicalUnit != null) {
            var detailsMap = unitReferenceQuery.resolveByCodes(List.of(canonicalUnit));
            var details = detailsMap.get(canonicalUnit);
            if (details == null) {
                return ValidationResult.invalid("Unknown or unsupported " + role + ".loadUnit: " + canonicalUnit);
            }
            if (!"MASS".equalsIgnoreCase(details.dimension())) {
                return ValidationResult.invalid(role + ".loadUnit '" + canonicalUnit + "' dimension must be MASS, but was " + details.dimension() + ".");
            }
        }

        return ValidationResult.valid();
    }

    public static String canonicalUnitCode(String unit) {
        if (unit == null || unit.isBlank()) return null;
        String u = unit.trim().toUpperCase();
        if ("LBS".equals(u) || "LB".equals(u)) return "LB";
        if ("KG".equals(u)) return "KG";
        return u;
    }
}
