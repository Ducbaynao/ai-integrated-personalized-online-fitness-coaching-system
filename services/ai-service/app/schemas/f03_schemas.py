from decimal import Decimal
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator


class TargetRefSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    scope: Literal["ONE_MATERIALIZED_OCCURRENCE", "TEMPLATE_OR_FUTURE_OCCURRENCES"]
    plannedWorkoutId: UUID | None = None
    plannedSessionExerciseId: UUID | None = None
    planId: UUID | None = None
    planVersionNumber: int | None = Field(default=None, ge=1)
    dayNumber: int | None = Field(default=None, ge=1, le=7)
    exercisePrescriptionId: UUID | None = None

    @model_validator(mode="after")
    def validate_target_scope(self) -> "TargetRefSchema":
        if (
            self.scope == "ONE_MATERIALIZED_OCCURRENCE"
            and (self.plannedWorkoutId is None or self.plannedSessionExerciseId is None)
        ):
            raise ValueError(
                "Scope ONE_MATERIALIZED_OCCURRENCE requires plannedWorkoutId and plannedSessionExerciseId"
            )
        elif (
            self.scope == "TEMPLATE_OR_FUTURE_OCCURRENCES"
            and (self.planId is None or self.planVersionNumber is None or self.exercisePrescriptionId is None)
        ):
            raise ValueError(
                "Scope TEMPLATE_OR_FUTURE_OCCURRENCES requires planId, planVersionNumber, and exercisePrescriptionId"
            )
        return self


class ExerciseSubstitutionBefore(BaseModel):
    model_config = ConfigDict(extra="forbid")

    exerciseVariationId: UUID
    exerciseName: str | None = None
    movementPattern: str | None = None
    equipmentType: str | None = None


class ExerciseSubstitutionAfter(BaseModel):
    model_config = ConfigDict(extra="forbid")

    replacementVariationId: UUID
    replacementExerciseName: str | None = None
    movementPattern: str | None = None
    equipmentType: str | None = None
    substitutionCategory: (
        Literal[
            "EQUIPMENT_UNAVAILABLE",
            "LIMITATION_OR_PAIN",
            "PREFERENCE",
            "FATIGUE_MANAGEMENT",
            "OTHER",
        ]
        | None
    ) = None


class LoadAdjustmentBefore(BaseModel):
    model_config = ConfigDict(extra="forbid")

    targetLoad: Decimal | None = None
    loadUnit: Literal["KG", "LBS"] | None = "KG"
    loadUnitId: int | None = 1


class LoadAdjustmentAfter(BaseModel):
    model_config = ConfigDict(extra="forbid")

    targetLoad: Decimal | None = None
    loadUnit: Literal["KG", "LBS"] | None = "KG"
    loadUnitId: int | None = 1
    direction: Literal["INCREASE", "DECREASE", "MAINTAIN"]
    deltaLoad: Decimal | None = None
    deltaPercent: Decimal | None = None

    @model_validator(mode="after")
    def validate_non_negative_target_load(self) -> "LoadAdjustmentAfter":
        if self.targetLoad is not None and self.targetLoad < Decimal("0.0"):
            raise ValueError("targetLoad cannot be negative")
        return self


class VolumeAdjustmentBefore(BaseModel):
    model_config = ConfigDict(extra="forbid")

    targetSets: int | None = Field(default=None, ge=1, le=20)
    targetRepsMin: int | None = Field(default=None, ge=0)
    targetRepsMax: int | None = Field(default=None, ge=0)
    durationSeconds: int | None = Field(default=None, ge=0)


class VolumeAdjustmentAfter(BaseModel):
    model_config = ConfigDict(extra="forbid")

    targetSets: int = Field(ge=1, le=20)
    targetRepsMin: int | None = Field(default=None, ge=0)
    targetRepsMax: int | None = Field(default=None, ge=0)
    durationSeconds: int | None = Field(default=None, ge=0)
    deltaSets: int | None = None
    deltaRepsMin: int | None = None
    deltaRepsMax: int | None = None
    deltaDurationSeconds: int | None = None

    @model_validator(mode="after")
    def validate_reps_min_max(self) -> "VolumeAdjustmentAfter":
        if self.targetRepsMin is not None and self.targetRepsMax is not None and self.targetRepsMin > self.targetRepsMax:
            raise ValueError("targetRepsMin must be <= targetRepsMax")
        return self


class EvidenceSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    movementPatternMatch: bool | None = None
    equipmentAvailable: bool | None = None
    rpeWindowCoverage: str | None = None
    continuityState: str | None = None
    knowledgeCitations: list[str] = Field(default_factory=list)


class VersionGuardsSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    expectedOccurrenceVersion: int | None = None
    expectedPlanAggregateVersion: int | None = None


class ExerciseSubstitutionChange(BaseModel):
    model_config = ConfigDict(extra="forbid")

    changeId: UUID
    changeType: Literal["EXERCISE_SUBSTITUTION"] = "EXERCISE_SUBSTITUTION"
    scope: Literal["ONE_MATERIALIZED_OCCURRENCE", "TEMPLATE_OR_FUTURE_OCCURRENCES"]
    targetRef: TargetRefSchema
    before: ExerciseSubstitutionBefore
    after: ExerciseSubstitutionAfter
    reason: str = Field(min_length=1)
    evidence: EvidenceSchema | None = None
    versionGuards: VersionGuardsSchema | None = None

    @model_validator(mode="after")
    def validate_different_variation(self) -> "ExerciseSubstitutionChange":
        if self.before.exerciseVariationId == self.after.replacementVariationId:
            raise ValueError("replacementVariationId must not be identical to original exerciseVariationId")
        return self


class LoadAdjustmentChange(BaseModel):
    model_config = ConfigDict(extra="forbid")

    changeId: UUID
    changeType: Literal["LOAD_ADJUSTMENT"] = "LOAD_ADJUSTMENT"
    scope: Literal["ONE_MATERIALIZED_OCCURRENCE", "TEMPLATE_OR_FUTURE_OCCURRENCES"]
    targetRef: TargetRefSchema
    before: LoadAdjustmentBefore
    after: LoadAdjustmentAfter
    reason: str = Field(min_length=1)
    evidence: EvidenceSchema | None = None
    versionGuards: VersionGuardsSchema | None = None

    @model_validator(mode="after")
    def validate_load_deltas(self) -> "LoadAdjustmentChange":
        if self.after.direction == "MAINTAIN":
            return self
        if self.before.targetLoad is not None and self.after.targetLoad is not None and self.after.deltaLoad is not None:
            expected_delta = self.after.targetLoad - self.before.targetLoad
            if self.after.deltaLoad != expected_delta:
                raise ValueError(
                    f"Contradictory deltaLoad: expected {expected_delta}, got {self.after.deltaLoad}"
                )
        if self.after.direction == "INCREASE" and self.after.deltaLoad is not None and self.after.deltaLoad <= 0:
            raise ValueError("direction is INCREASE but deltaLoad is not positive")
        if self.after.direction == "DECREASE" and self.after.deltaLoad is not None and self.after.deltaLoad >= 0:
            raise ValueError("direction is DECREASE but deltaLoad is not negative")
        return self


class VolumeAdjustmentChange(BaseModel):
    model_config = ConfigDict(extra="forbid")

    changeId: UUID
    changeType: Literal["VOLUME_ADJUSTMENT"] = "VOLUME_ADJUSTMENT"
    scope: Literal["ONE_MATERIALIZED_OCCURRENCE", "TEMPLATE_OR_FUTURE_OCCURRENCES"]
    targetRef: TargetRefSchema
    before: VolumeAdjustmentBefore
    after: VolumeAdjustmentAfter
    reason: str = Field(min_length=1)
    evidence: EvidenceSchema | None = None
    versionGuards: VersionGuardsSchema | None = None

    @model_validator(mode="after")
    def validate_volume_deltas(self) -> "VolumeAdjustmentChange":
        if self.before.targetSets is not None and self.after.targetSets is not None:
            expected_delta_sets = self.after.targetSets - self.before.targetSets
            if self.after.deltaSets is not None and self.after.deltaSets != expected_delta_sets:
                raise ValueError(
                    f"Contradictory deltaSets: expected {expected_delta_sets}, got {self.after.deltaSets}"
                )
        if self.before.durationSeconds is not None and self.after.durationSeconds is not None:
            expected_delta_dur = self.after.durationSeconds - self.before.durationSeconds
            if self.after.deltaDurationSeconds is not None and self.after.deltaDurationSeconds != expected_delta_dur:
                raise ValueError(
                    f"Contradictory deltaDurationSeconds: expected {expected_delta_dur}, got {self.after.deltaDurationSeconds}"
                )
        return self


TypedWorkoutChange = Annotated[
    ExerciseSubstitutionChange | LoadAdjustmentChange | VolumeAdjustmentChange,
    Field(discriminator="changeType"),
]


class F03WorkoutAdjustmentRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    studentId: UUID
    coachingPeriodId: UUID | None = None
    mode: Literal["SELF_DIRECTED", "HUMAN_COACH"] | None = None
    coachingMode: Literal["SELF_DIRECTED", "HUMAN_COACH"] | None = None
    scope: Literal["ONE_MATERIALIZED_OCCURRENCE", "TEMPLATE_OR_FUTURE_OCCURRENCES"]
    target: TargetRefSchema | None = None
    targetRef: TargetRefSchema | None = None
    requestedChangeType: Literal[
        "EXERCISE_SUBSTITUTION",
        "LOAD_ADJUSTMENT",
        "VOLUME_ADJUSTMENT",
        "ANY",
    ] = "ANY"
    userReason: str | None = None
    physicalLimitations: list[str] = Field(default_factory=list)
    equipmentAvailable: list[str] = Field(default_factory=list)
    allowLoadIncrease: bool = False
    continuityState: (
        Literal[
            "CONFIRMED_NEW",
            "RECENT_CONSISTENT",
            "PROLONGED_INACTIVITY",
            "UNKNOWN_HISTORY",
            "RESTRICTED_HISTORY",
        ]
        | None
    ) = None
    baselinePrescription: dict[str, Any] | None = None
    sourceSnapshot: dict[str, Any] | None = None

    @model_validator(mode="after")
    def normalize_fields(self) -> "F03WorkoutAdjustmentRequest":
        if self.mode is None and self.coachingMode is not None:
            self.mode = self.coachingMode
        if self.target is None and self.targetRef is not None:
            self.target = self.targetRef
        if self.mode is None:
            raise ValueError("mode or coachingMode is required")
        if self.target is None:
            raise ValueError("target or targetRef is required")
        return self


class F03WorkoutAdjustmentResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    recommendationType: Literal["WORKOUT_ADJUSTMENT_PROPOSAL"] = "WORKOUT_ADJUSTMENT_PROPOSAL"
    summary: str = Field(min_length=1)
    reasoning: list[str] = Field(min_length=1)
    usedData: list[str] = Field(default_factory=list)
    missingData: list[str] = Field(default_factory=list)
    limitations: list[str] = Field(default_factory=list)
    confidence: float = Field(ge=0.0, le=1.0)
    changes: list[TypedWorkoutChange] = Field(min_length=1)
