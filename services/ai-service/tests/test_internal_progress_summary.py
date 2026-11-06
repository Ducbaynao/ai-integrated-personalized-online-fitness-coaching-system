from datetime import UTC, date, datetime
from decimal import Decimal
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.core.config import settings
from app.main import app
from app.schemas.f04_schemas import (
    ContinuitySignalSchema,
    DateWindowSchema,
    F04ProgressSummaryContext,
    F04ProgressSummaryRequest,
    HistoricalLimitsSummarySchema,
    MeasurementSectionSchema,
    WorkoutAdherenceSummarySchema,
    WorkoutSectionSchema,
    WorkoutVolumeSummarySchema,
)


@pytest.fixture
def client():
    return TestClient(app)


def test_internal_progress_summary_auth_failure(client):
    payload = {"foo": "bar"}
    response = client.post(
        "/internal/v1/progress/summarize",
        json=payload,
        headers={"X-Internal-Service-Key": "invalid-key"},
    )
    assert response.status_code == 403


def test_internal_progress_summary_success(client):
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
            effectiveAsOf=datetime(2026, 10, 28, 12, 0, 0, tzinfo=UTC),
            workout=WorkoutSectionSchema(
                availability="AVAILABLE",
                adherenceSummary=WorkoutAdherenceSummarySchema(
                    scheduledDueCount=12,
                    completedDueCount=11,
                    completedOnTimeCount=10,
                    completedOffScheduleCount=1,
                    missedCount=1,
                    rawCompletionRate=Decimal("0.9167"),
                    onTimeAdherenceRate=Decimal("0.8333"),
                ),
                volumeSummary=WorkoutVolumeSummarySchema(
                    knownVolumeKg=Decimal("14500.50"),
                    totalSetsCompleted=48,
                    workingSetsCompleted=40,
                    totalRepsCompleted=400,
                    loadCoverageRate=Decimal("1.0"),
                ),
                sourceWatermark="wm-workout-1",
            ),
            measurement=MeasurementSectionSchema(
                availability="NO_DATA",
            ),
            continuity=ContinuitySignalSchema(
                state="RECENT_ACTIVITY_OBSERVED",
                daysSinceLastWorkout=2,
            ),
            historicalLimits=HistoricalLimitsSummarySchema(
                hasHistoricalLimits=False,
                workoutLimits=False,
                measurementLimits=False,
            ),
            compositeWatermark="ENGINE:watermark-1",
        ),
    )

    response = client.post(
        "/internal/v1/progress/summarize",
        content=req.model_dump_json(),
        headers={
            "X-Internal-Service-Key": settings.internal_service_key,
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 200
    data = response.json()
    assert data["runId"] == str(req.requestId)
    assert data["resultType"] == "INFORMATION"
    assert data["runStatus"] == "SUCCEEDED"
    assert "workoutProgressSummary" in data
    assert data["workoutProgressSummary"] is not None
