from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class RescheduleDestinationSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    localStart: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$")
    localEnd: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$")
    timezone: str = Field(min_length=1, max_length=64)
    startUtcOffset: str = Field(pattern=r"^[+-]\d{2}:\d{2}$")
    endUtcOffset: str = Field(pattern=r"^[+-]\d{2}:\d{2}$")
    proposedStartAt: str
    proposedEndAt: str


class WorkoutReschedulePayloadSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    occurrenceId: UUID
    planSessionId: UUID | None = None
    sessionName: str | None = None
    previousPlannedStartAt: str
    originalPlannedStartAt: str | None = None
    supervisionRequirement: Literal["SELF_PERFORMABLE", "COACH_OPTIONAL", "COACH_REQUIRED"]
    destination: RescheduleDestinationSchema


class F05WorkoutRescheduleRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    studentId: UUID
    coachingPeriodId: UUID | None = None
    mode: Literal["SELF_DIRECTED", "HUMAN_COACH"]
    occurrenceId: UUID
    occurrenceVersion: int = Field(ge=0)
    planVersionId: UUID | None = None
    planSessionId: UUID | None = None
    sessionName: str | None = None
    supervisionRequirement: Literal[
        "SELF_PERFORMABLE", "COACH_OPTIONAL", "COACH_REQUIRED", "SELF_ALLOWED", "COACH_PREFERRED"
    ] = "SELF_PERFORMABLE"
    timezone: str = "Asia/Ho_Chi_Minh"
    originalPlannedStartAt: str | None = None
    plannedStartAt: str
    plannedEndAt: str | None = None
    reason: str | None = None
    preferredTimeOfDay: str | None = None
    continuityState: str | None = None
    hasLinkedAppointment: bool = False
    appointmentId: UUID | None = None
    appointmentStartAt: str | None = None
    appointmentEndAt: str | None = None


class F05WorkoutRescheduleResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    recommendationType: Literal["WORKOUT_RESCHEDULE_PROPOSAL"] = "WORKOUT_RESCHEDULE_PROPOSAL"
    summary: str = Field(min_length=1)
    reasoning: list[str] = Field(min_length=1)
    usedData: list[str] = Field(default_factory=list)
    missingData: list[str] = Field(default_factory=list)
    limitations: list[str] = Field(default_factory=list)
    confidence: float = Field(ge=0.0, le=1.0)
    reschedule: WorkoutReschedulePayloadSchema
