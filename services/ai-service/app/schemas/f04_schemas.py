from datetime import date, datetime
from decimal import Decimal
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class DateWindowSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    fromDate: date
    toDate: date
    timezone: str = Field(min_length=1)


class WorkoutAdherenceSummarySchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    scheduledDueCount: int = Field(default=0, ge=0)
    completedDueCount: int = Field(default=0, ge=0)
    completedOnTimeCount: int = Field(default=0, ge=0)
    completedOffScheduleCount: int = Field(default=0, ge=0)
    missedCount: int = Field(default=0, ge=0)
    todayPendingCount: int = Field(default=0, ge=0)
    inProgressCount: int = Field(default=0, ge=0)
    partiallyCompletedCount: int = Field(default=0, ge=0)
    abortedCount: int = Field(default=0, ge=0)
    rescheduledCount: int = Field(default=0, ge=0)
    cancelledCount: int = Field(default=0, ge=0)
    upcomingCount: int = Field(default=0, ge=0)
    unplannedCompletedCount: int = Field(default=0, ge=0)
    unverifiableDueCount: int = Field(default=0, ge=0)
    rawCompletionRate: Decimal | None = Field(default=None, ge=0, le=1)
    onTimeAdherenceRate: Decimal | None = Field(default=None, ge=0, le=1)
    availabilityReason: str | None = None


class WorkoutVolumeSummarySchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    knownVolumeKg: Decimal | None = Field(default=None, ge=0)
    totalSetsCompleted: int = Field(default=0, ge=0)
    workingSetsCompleted: int = Field(default=0, ge=0)
    warmupSetsCompleted: int = Field(default=0, ge=0)
    bodyweightSetsCompleted: int = Field(default=0, ge=0)
    setsWithMissingLoadCount: int = Field(default=0, ge=0)
    setsWithZeroLoadCount: int = Field(default=0, ge=0)
    setsWithMissingRepsCount: int = Field(default=0, ge=0)
    setsWithUnknownUnitCount: int = Field(default=0, ge=0)
    setsWithCalculableVolumeCount: int = Field(default=0, ge=0)
    loadCoverageRate: Decimal | None = Field(default=None, ge=0, le=1)
    hasIncompleteVolumeCoverage: bool = False
    totalRepsCompleted: int = Field(default=0, ge=0)
    totalSetDurationSeconds: int | None = Field(default=None, ge=0)
    totalSessionElapsedSeconds: int | None = Field(default=None, ge=0)
    averageSessionRpe: Decimal | None = Field(default=None, ge=0, le=10)
    averageSetRpe: Decimal | None = Field(default=None, ge=0, le=10)


class WorkoutSectionSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    availability: Literal["AVAILABLE", "RESTRICTED", "NO_DATA"]
    restrictionReason: str | None = None
    adherenceSummary: WorkoutAdherenceSummarySchema | None = None
    volumeSummary: WorkoutVolumeSummarySchema | None = None
    sourceWatermark: str | None = None


class MeasurementSummarySchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    totalObservationsCount: int = Field(default=0, ge=0)
    distinctDaysCount: int = Field(default=0, ge=0)
    trackedMetricsCount: int = Field(default=0, ge=0)


class MetricTrendDeltaSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    metricCode: str = Field(min_length=1)
    metricDisplayName: str | None = None
    unitCode: str | None = None
    baselineValue: Decimal | None = None
    baselineMeasuredAt: datetime | None = None
    latestValue: Decimal | None = None
    latestMeasuredAt: datetime | None = None
    deltaValue: Decimal | None = None
    percentageChange: Decimal | None = None
    trendDirection: Literal["INCREASED", "DECREASED", "STABLE", "INSUFFICIENT_DATA"]
    numericObservationsCount: int = Field(default=0, ge=0)


class MeasurementSectionSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    availability: Literal["AVAILABLE", "RESTRICTED", "NO_DATA"]
    restrictionReason: str | None = None
    summary: MeasurementSummarySchema | None = None
    metricDeltas: list[MetricTrendDeltaSchema] = Field(default_factory=list)
    sourceWatermark: str | None = None


class ContinuitySignalSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    state: Literal[
        "CONFIRMED_NEW",
        "PROLONGED_INACTIVITY",
        "RECENT_ACTIVITY_OBSERVED",
        "UNKNOWN_HISTORY",
        "RESTRICTED_HISTORY",
    ]
    daysSinceLastWorkout: int | None = Field(default=None, ge=0)
    lastWorkoutDate: date | None = None
    safetyFlags: list[str] = Field(default_factory=list)
    rationale: str | None = None


class HistoricalLimitsSummarySchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    hasHistoricalLimits: bool
    workoutLimits: bool
    measurementLimits: bool
    explanation: str | None = None


class F04ProgressSummaryContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dateWindow: DateWindowSchema
    effectiveAsOf: datetime
    workout: WorkoutSectionSchema
    measurement: MeasurementSectionSchema
    continuity: ContinuitySignalSchema
    historicalLimits: HistoricalLimitsSummarySchema
    compositeWatermark: str = Field(min_length=1)


class F04ProgressSummaryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requestId: UUID
    correlationId: UUID
    studentId: UUID
    actorId: UUID
    actorRole: Literal["STUDENT", "TRAINER"]
    context: F04ProgressSummaryContext


class WorkoutProgressSummaryResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    completionEvaluation: str | None = None
    adherenceEvaluation: str | None = None
    volumeEvaluation: str | None = None


class MetricEvaluationResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    metricCode: str
    trendDirection: Literal["INCREASED", "DECREASED", "STABLE", "INSUFFICIENT_DATA"] | None = None
    message: str


class MeasurementProgressSummaryResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    metricsEvaluations: list[MetricEvaluationResult] = Field(default_factory=list)


class ContinuityEvaluationResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    state: Literal[
        "CONFIRMED_NEW",
        "PROLONGED_INACTIVITY",
        "RECENT_ACTIVITY_OBSERVED",
        "UNKNOWN_HISTORY",
        "RESTRICTED_HISTORY",
    ]
    message: str
    hasSafetyAlert: bool = False


class UsageTokensResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    promptTokens: int = Field(default=0, ge=0)
    completionTokens: int = Field(default=0, ge=0)
    totalTokens: int = Field(default=0, ge=0)


class F04ProgressSummaryResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    runId: UUID
    correlationId: UUID | None = None
    runStatus: Literal["SUCCEEDED", "FAILED", "REJECTED_BY_VALIDATOR"]
    resultType: Literal["INFORMATION", "INSUFFICIENT_DATA", "BLOCKED"]
    reasonCode: Literal[
        "NONE",
        "MISSING_REQUIRED_CONTEXT",
        "RESTRICTED_SOURCE",
        "INSUFFICIENT_DATA",
        "RULE_BLOCKED",
        "VALIDATION_FAILED",
        "MODEL_ERROR",
        "PROVIDER_ERROR",
        "TIMEOUT",
        "PROCESSING_ERROR",
    ]
    summary: str = Field(min_length=1, max_length=5000)
    workoutProgressSummary: WorkoutProgressSummaryResult | None = None
    measurementProgressSummary: MeasurementProgressSummaryResult | None = None
    continuityEvaluation: ContinuityEvaluationResult | None = None
    limitations: list[str] = Field(default_factory=list)
    missingData: list[str] = Field(default_factory=list)
    factsReferenced: list[str] = Field(default_factory=list)
    asOf: datetime
    sourceWatermark: str | None = None
    usage: UsageTokensResult | None = None
