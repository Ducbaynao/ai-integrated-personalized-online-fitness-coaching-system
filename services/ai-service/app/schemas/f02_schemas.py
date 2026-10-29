from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class ExercisePrescriptionSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    exerciseVariationId: UUID
    sequenceNumber: int = Field(ge=1)
    targetSets: int = Field(ge=1, le=20)
    targetRepsMin: int | None = Field(default=None, ge=1, le=100)
    targetRepsMax: int | None = Field(default=None, ge=1, le=100)
    targetLoad: float | None = Field(default=None, ge=0)
    restSeconds: int | None = Field(default=None, ge=0, le=600)
    durationSeconds: int | None = Field(default=None, ge=0, le=7200)
    instructions: str | None = None


class SessionTemplateSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    weekNumber: int = Field(default=1, ge=1, le=1)
    dayNumber: int = Field(ge=1, le=7)
    sequenceNumber: int = Field(ge=1)
    name: str = Field(min_length=1, max_length=180)
    focus: str | None = Field(default=None, max_length=160)
    estimatedDurationMinutes: int | None = Field(default=None, ge=1, le=300)
    supervision: Literal["SELF_PERFORMABLE", "COACH_OPTIONAL", "COACH_REQUIRED"] = "SELF_PERFORMABLE"
    notes: str | None = None
    prescriptions: list[ExercisePrescriptionSchema] = Field(min_length=1)


class WorkoutPlanPayloadSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=200)
    description: str | None = None
    durationWeeks: int = Field(default=1, ge=1, le=1)
    sessions: list[SessionTemplateSchema] = Field(min_length=1, max_length=7)


class F02WorkoutProposalRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    studentId: UUID
    coachingPeriodId: UUID | None = None
    mode: Literal["SELF_DIRECTED", "HUMAN_COACH"]
    fitnessLevel: str | None = None
    goalType: str | None = None
    equipmentAvailable: list[str] = Field(default_factory=list)
    preferences: dict[str, str | int] | None = None
    candidateVariationIds: list[UUID] = Field(default_factory=list)


class F02WorkoutProposalResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    recommendationType: Literal["WORKOUT_PLAN_PROPOSAL"] = "WORKOUT_PLAN_PROPOSAL"
    summary: str = Field(min_length=1)
    reasoning: list[str] = Field(min_length=1)
    usedData: list[str] = Field(default_factory=list)
    missingData: list[str] = Field(default_factory=list)
    limitations: list[str] = Field(default_factory=list)
    confidence: float = Field(ge=0.0, le=1.0)
    plan: WorkoutPlanPayloadSchema
