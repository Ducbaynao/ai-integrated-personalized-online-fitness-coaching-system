from datetime import date, datetime, timezone
from decimal import Decimal
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.schemas.f04_schemas import (
    ContinuitySignalSchema,
    DateWindowSchema,
    F04ProgressSummaryContext,
    F04ProgressSummaryRequest,
    F04ProgressSummaryResult,
    HistoricalLimitsSummarySchema,
    MeasurementSectionSchema,
    MetricTrendDeltaSchema,
    WorkoutAdherenceSummarySchema,
    WorkoutSectionSchema,
    WorkoutVolumeSummarySchema,
)


def test_valid_f04_progress_summary_request():
    req = F04ProgressSummaryRequest(
        requestId=uuid4(),
        correlationId=uuid4(),
        studentId=uuid4(),
        actorId=uuid4(),
        actorRole="STUDENT",
        context=F04ProgressSummaryContext(
            dateWindow=DateWindowSchema(
                fromDate=date(2026, 10, 1),
                toDate=date(2026, 10, 28),
                timezone="Asia/Ho_Chi_Minh",
            ),
            effectiveAsOf=datetime.now(timezone.utc),
            workout=WorkoutSectionSchema(
                availability="AVAILABLE",
                adherenceSummary=WorkoutAdherenceSummarySchema(
                    scheduledDueCount=12,
                    completedDueCount=10,
                    completedOnTimeCount=9,
                    completedOffScheduleCount=1,
                    missedCount=2,
                    rawCompletionRate=Decimal("0.8333"),
                    onTimeAdherenceRate=Decimal("0.7500"),
                ),
                volumeSummary=WorkoutVolumeSummarySchema(
                    knownVolumeKg=Decimal("12000.00"),
                    totalSetsCompleted=40,
                    workingSetsCompleted=32,
                    totalRepsCompleted=320,
                    loadCoverageRate=Decimal("1.0"),
                ),
                sourceWatermark="wm-workout-1",
            ),
            measurement=MeasurementSectionSchema(
                availability="AVAILABLE",
                metricDeltas=[
                    MetricTrendDeltaSchema(
                        metricCode="F_WEIGHT",
                        metricDisplayName="Weight",
                        unitCode="KG",
                        baselineValue=Decimal("75.0"),
                        latestValue=Decimal("74.2"),
                        deltaValue=Decimal("-0.8"),
                        percentageChange=Decimal("-1.0667"),
                        trendDirection="DECREASED",
                        numericObservationsCount=4,
                    )
                ],
                sourceWatermark="wm-meas-1",
            ),
            continuity=ContinuitySignalSchema(
                state="RECENT_ACTIVITY_OBSERVED",
                daysSinceLastWorkout=3,
                lastWorkoutDate=date(2026, 10, 25),
            ),
            historicalLimits=HistoricalLimitsSummarySchema(
                hasHistoricalLimits=False,
                workoutLimits=False,
                measurementLimits=False,
            ),
            compositeWatermark="ENGINE:composite-wm-1",
        ),
    )
    assert req.actorRole == "STUDENT"
    assert req.context.workout.availability == "AVAILABLE"
    assert req.context.measurement.metricDeltas[0].metricCode == "F_WEIGHT"


def test_invalid_f04_request_missing_required_fields():
    with pytest.raises(ValidationError):
        F04ProgressSummaryRequest.model_validate({
            "requestId": str(uuid4()),
            "correlationId": str(uuid4()),
        })


def test_f04_result_rejects_unauthorized_result_type():
    with pytest.raises(ValidationError):
        F04ProgressSummaryResult(
            runId=uuid4(),
            runStatus="SUCCEEDED",
            resultType="PROPOSAL",  # Not allowed in F04 INFORMATION contract
            reasonCode="NONE",
            summary="Invalid proposal",
            asOf=datetime.now(timezone.utc),
        )
