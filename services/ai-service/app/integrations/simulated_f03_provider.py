from decimal import Decimal
from uuid import UUID, uuid4

from app.schemas.f03_schemas import (
    EvidenceSchema,
    ExerciseSubstitutionAfter,
    ExerciseSubstitutionBefore,
    ExerciseSubstitutionChange,
    F03WorkoutAdjustmentRequest,
    F03WorkoutAdjustmentResponse,
    LoadAdjustmentAfter,
    LoadAdjustmentBefore,
    LoadAdjustmentChange,
    TargetRefSchema,
    VersionGuardsSchema,
    VolumeAdjustmentAfter,
    VolumeAdjustmentBefore,
    VolumeAdjustmentChange,
)


class SimulatedF03WorkoutProvider:
    """Deterministic simulated provider generating targeted workout adjustments with $0.00 spend."""

    async def generate_adjustment(self, request: F03WorkoutAdjustmentRequest) -> F03WorkoutAdjustmentResponse:
        target = request.target
        scope = request.scope
        base = request.baselinePrescription or {}

        change_type = request.requestedChangeType
        if change_type == "ANY":
            change_type = "LOAD_ADJUSTMENT"

        version_guards = None
        if scope == "ONE_MATERIALIZED_OCCURRENCE":
            expected_occ_ver = 1
            if request.sourceSnapshot and "occurrenceVersion" in request.sourceSnapshot:
                expected_occ_ver = request.sourceSnapshot["occurrenceVersion"]
            version_guards = VersionGuardsSchema(expectedOccurrenceVersion=expected_occ_ver)
        else:
            expected_plan_agg_ver = 1
            if request.sourceSnapshot and "planAggregateVersion" in request.sourceSnapshot:
                expected_plan_agg_ver = request.sourceSnapshot["planAggregateVersion"]
            version_guards = VersionGuardsSchema(expectedPlanAggregateVersion=expected_plan_agg_ver)

        evidence = EvidenceSchema(
            movementPatternMatch=True,
            equipmentAvailable=True,
            rpeWindowCoverage="LAST_5_SESSIONS",
            continuityState=request.continuityState or "RECENT_CONSISTENT",
            knowledgeCitations=["NSCA Resistance Training Guidelines Section 4"],
        )

        change_id = uuid4()

        if change_type == "EXERCISE_SUBSTITUTION":
            before_var_id = (
                UUID(str(base["exerciseVariationId"]))
                if "exerciseVariationId" in base
                else UUID("55555555-5555-4555-8555-555555555555")
            )
            rep_var_id = (
                UUID("b0000000-0000-4000-8000-000000000002")
                if before_var_id != UUID("b0000000-0000-4000-8000-000000000002")
                else UUID("b0000000-0000-4000-8000-000000000001")
            )

            change = ExerciseSubstitutionChange(
                changeId=change_id,
                changeType="EXERCISE_SUBSTITUTION",
                scope=scope,
                targetRef=target,
                before=ExerciseSubstitutionBefore(
                    exerciseVariationId=before_var_id,
                    exerciseName=base.get("exerciseName", "Exercise"),
                    movementPattern=base.get("movementPattern", "GENERAL"),
                    equipmentType=base.get("equipmentType", "BARBELL"),
                ),
                after=ExerciseSubstitutionAfter(
                    replacementVariationId=rep_var_id,
                    replacementExerciseName=base.get("exerciseName", "Exercise") + " (Alternative)",
                    movementPattern=base.get("movementPattern", "GENERAL"),
                    equipmentType="DUMBBELL",
                    substitutionCategory="EQUIPMENT_UNAVAILABLE",
                ),
                reason="Substituted for equivalent exercise using available dumbbell equipment.",
                evidence=evidence,
                versionGuards=version_guards,
            )

        elif change_type == "LOAD_ADJUSTMENT":
            before_load = Decimal(str(base.get("targetLoad", 20.0)))
            if request.allowLoadIncrease:
                delta = Decimal("2.5")
                direction = "INCREASE"
                target_load = before_load + delta
            else:
                delta = Decimal("-2.5")
                direction = "DECREASE"
                target_load = max(Decimal("0.0"), before_load - Decimal("2.5"))

            delta_pct = (delta / before_load * Decimal("100")).quantize(Decimal("0.1")) if before_load > 0 else Decimal("10.0")

            change = LoadAdjustmentChange(
                changeId=change_id,
                changeType="LOAD_ADJUSTMENT",
                scope=scope,
                targetRef=target,
                before=LoadAdjustmentBefore(
                    targetLoad=before_load,
                    loadUnit="KG",
                    loadUnitId=1,
                ),
                after=LoadAdjustmentAfter(
                    targetLoad=target_load,
                    loadUnit="KG",
                    loadUnitId=1,
                    direction=direction,
                    deltaLoad=delta,
                    deltaPercent=delta_pct,
                ),
                reason="Load adjusted based on recent RPE performance and safe overload criteria.",
                evidence=evidence,
                versionGuards=version_guards,
            )

        else:
            # VOLUME_ADJUSTMENT
            before_sets = int(base.get("targetSets", 3))
            before_reps_min = int(base.get("targetRepsMin", 8))
            before_reps_max = int(base.get("targetRepsMax", 12))

            if request.allowLoadIncrease:
                target_sets = min(20, before_sets + 1)
                delta_sets = target_sets - before_sets
            else:
                target_sets = max(1, before_sets - 1)
                delta_sets = target_sets - before_sets

            change = VolumeAdjustmentChange(
                changeId=change_id,
                changeType="VOLUME_ADJUSTMENT",
                scope=scope,
                targetRef=target,
                before=VolumeAdjustmentBefore(
                    targetSets=before_sets,
                    targetRepsMin=before_reps_min,
                    targetRepsMax=before_reps_max,
                ),
                after=VolumeAdjustmentAfter(
                    targetSets=target_sets,
                    targetRepsMin=before_reps_min,
                    targetRepsMax=before_reps_max,
                    deltaSets=delta_sets,
                    deltaRepsMin=0,
                    deltaRepsMax=0,
                ),
                reason="Set volume calibrated according to trainee recovery and session duration budget.",
                evidence=evidence,
                versionGuards=version_guards,
            )

        used_data = [
            f"student_id: {request.studentId}",
            f"coaching_mode: {request.mode}",
            f"scope: {scope}",
            f"requestedChangeType: {request.requestedChangeType}",
            f"allowLoadIncrease: {request.allowLoadIncrease}",
        ]
        if request.continuityState:
            used_data.append(f"continuity_state: {request.continuityState}")

        missing_data = [] if request.allowLoadIncrease else ["recent_exercise_rpe_logs"]

        return F03WorkoutAdjustmentResponse(
            summary="Personalized workout adjustment proposal.",
            reasoning=[
                "Prescription analyzed against historical workload.",
                "Target change calibrated for progressive safety.",
            ],
            usedData=used_data,
            missingData=missing_data,
            limitations=[
                "Proposal covers exactly one targeted change.",
                "Loads must be self-regulated using RPE guidelines.",
            ],
            confidence=0.92,
            changes=[change],
        )
