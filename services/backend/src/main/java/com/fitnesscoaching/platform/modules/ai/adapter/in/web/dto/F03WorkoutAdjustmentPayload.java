package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record F03WorkoutAdjustmentPayload(
        String recommendationType,
        String summary,
        List<String> reasoning,
        List<String> usedData,
        List<String> missingData,
        List<String> limitations,
        Double confidence,
        List<TypedChange> changes
) {
    public enum ChangeType {
        EXERCISE_SUBSTITUTION,
        LOAD_ADJUSTMENT,
        VOLUME_ADJUSTMENT
    }

    public enum TargetScope {
        ONE_MATERIALIZED_OCCURRENCE,
        TEMPLATE_OR_FUTURE_OCCURRENCES
    }

    public record TargetRef(
            TargetScope scope,
            UUID plannedWorkoutId,
            UUID plannedSessionExerciseId,
            UUID planId,
            Integer planVersionNumber,
            Integer dayNumber,
            UUID exercisePrescriptionId
    ) {
        public TargetRef {
            if (scope == null) {
                throw new IllegalArgumentException("scope is required");
            }
            if (scope == TargetScope.ONE_MATERIALIZED_OCCURRENCE) {
                if (plannedWorkoutId == null || plannedSessionExerciseId == null) {
                    throw new IllegalArgumentException(
                            "ONE_MATERIALIZED_OCCURRENCE requires plannedWorkoutId and plannedSessionExerciseId");
                }
            } else if (scope == TargetScope.TEMPLATE_OR_FUTURE_OCCURRENCES) {
                if (planId == null || planVersionNumber == null || exercisePrescriptionId == null) {
                    throw new IllegalArgumentException(
                            "TEMPLATE_OR_FUTURE_OCCURRENCES requires planId, planVersionNumber, and exercisePrescriptionId");
                }
            }
        }
    }

    public record SubstitutionBefore(
            UUID exerciseVariationId,
            String exerciseName,
            String movementPattern,
            String equipmentType
    ) {}

    public record SubstitutionAfter(
            UUID replacementVariationId,
            String replacementExerciseName,
            String movementPattern,
            String equipmentType,
            String substitutionCategory
    ) {}

    public record LoadBefore(
            BigDecimal targetLoad,
            String loadUnit,
            Integer loadUnitId
    ) {}

    public record LoadAfter(
            BigDecimal targetLoad,
            String loadUnit,
            Integer loadUnitId,
            String direction,
            BigDecimal deltaLoad,
            BigDecimal deltaPercent
    ) {}

    public record VolumeBefore(
            Integer targetSets,
            Integer targetRepsMin,
            Integer targetRepsMax,
            Integer durationSeconds
    ) {}

    public record VolumeAfter(
            Integer targetSets,
            Integer targetRepsMin,
            Integer targetRepsMax,
            Integer durationSeconds,
            Integer deltaSets,
            Integer deltaRepsMin,
            Integer deltaRepsMax,
            Integer deltaDurationSeconds
    ) {}

    public record Evidence(
            Boolean movementPatternMatch,
            Boolean equipmentAvailable,
            String rpeWindowCoverage,
            String continuityState,
            List<String> knowledgeCitations
    ) {}

    public record VersionGuards(
            Long expectedOccurrenceVersion,
            Long expectedPlanAggregateVersion
    ) {}

    public record TypedChange(
            UUID changeId,
            ChangeType changeType,
            TargetScope scope,
            TargetRef targetRef,
            JsonNode before,
            JsonNode after,
            String reason,
            Evidence evidence,
            VersionGuards versionGuards
    ) {}
}
