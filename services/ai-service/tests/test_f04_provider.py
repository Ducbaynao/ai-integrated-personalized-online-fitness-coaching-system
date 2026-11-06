import asyncio
from datetime import date, datetime, timezone
from decimal import Decimal
from uuid import uuid4

import pytest

from app.integrations.simulated_f04_provider import SimulatedF04ProgressProvider
from app.schemas.f04_schemas import (
    ContinuitySignalSchema,
    DateWindowSchema,
    F04ProgressSummaryContext,
    F04ProgressSummaryRequest,
    HistoricalLimitsSummarySchema,
    MeasurementSectionSchema,
    MetricTrendDeltaSchema,
    WorkoutAdherenceSummarySchema,
    WorkoutSectionSchema,
    WorkoutVolumeSummarySchema,
)


def create_base_request(
    workout_availability="AVAILABLE",
    measurement_availability="AVAILABLE",
    continuity_state="RECENT_ACTIVITY_OBSERVED",
    days_since_workout=2,
    safety_flags=None,
    has_historical_limits=False,
) -> F04ProgressSummaryRequest:
    return F04ProgressSummaryRequest(
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
            effectiveAsOf=datetime(2026, 10, 28, 12, 0, 0, tzinfo=timezone.utc),
            workout=WorkoutSectionSchema(
                availability=workout_availability,
                restrictionReason="Access denied" if workout_availability == "RESTRICTED" else None,
                adherenceSummary=WorkoutAdherenceSummarySchema(
                    scheduledDueCount=10,
                    completedDueCount=9,
                    completedOnTimeCount=8,
                    completedOffScheduleCount=1,
                    missedCount=1,
                    rawCompletionRate=Decimal("0.9000"),
                    onTimeAdherenceRate=Decimal("0.8000"),
                ) if workout_availability == "AVAILABLE" else None,
                volumeSummary=WorkoutVolumeSummarySchema(
                    knownVolumeKg=Decimal("15000.00"),
                    totalSetsCompleted=45,
                    workingSetsCompleted=36,
                    totalRepsCompleted=360,
                    loadCoverageRate=Decimal("1.0"),
                    averageSessionRpe=Decimal("7.5"),
                ) if workout_availability == "AVAILABLE" else None,
                sourceWatermark="wm-workout" if workout_availability == "AVAILABLE" else None,
            ),
            measurement=MeasurementSectionSchema(
                availability=measurement_availability,
                restrictionReason="Access denied" if measurement_availability == "RESTRICTED" else None,
                metricDeltas=[
                    MetricTrendDeltaSchema(
                        metricCode="F_WEIGHT",
                        metricDisplayName="Cân nặng",
                        unitCode="KG",
                        baselineValue=Decimal("72.0"),
                        latestValue=Decimal("71.5"),
                        deltaValue=Decimal("-0.5"),
                        percentageChange=Decimal("-0.6944"),
                        trendDirection="DECREASED",
                        numericObservationsCount=4,
                    )
                ] if measurement_availability == "AVAILABLE" else [],
                sourceWatermark="wm-meas" if measurement_availability == "AVAILABLE" else None,
            ),
            continuity=ContinuitySignalSchema(
                state=continuity_state,
                daysSinceLastWorkout=days_since_workout,
                lastWorkoutDate=date(2026, 10, 26) if days_since_workout is not None else None,
                safetyFlags=safety_flags or [],
            ),
            historicalLimits=HistoricalLimitsSummarySchema(
                hasHistoricalLimits=has_historical_limits,
                workoutLimits=has_historical_limits,
                measurementLimits=False,
                explanation="Modified after asOf" if has_historical_limits else None,
            ),
            compositeWatermark="ENGINE:test-watermark-123",
        ),
    )


def test_full_available_data_generates_information_summary():
    provider = SimulatedF04ProgressProvider()
    request = create_base_request()
    result = asyncio.run(provider.summarize_progress(request))

    assert result.runId == request.requestId
    assert result.resultType == "INFORMATION"
    assert result.runStatus == "SUCCEEDED"
    assert result.reasonCode == "NONE"
    assert "9/10 buổi tập" in result.summary or "Hoàn thành" in result.summary
    assert result.workoutProgressSummary is not None
    assert result.measurementProgressSummary is not None
    assert len(result.measurementProgressSummary.metricsEvaluations) == 1
    assert result.measurementProgressSummary.metricsEvaluations[0].metricCode == "F_WEIGHT"
    # Invariant: Never claim fat loss/muscle gain from weight alone
    assert any("Body Fat" in lim for lim in result.limitations)
    assert not result.continuityEvaluation.hasSafetyAlert


def test_prolonged_inactivity_flags_safety_alert():
    provider = SimulatedF04ProgressProvider()
    request = create_base_request(
        workout_availability="NO_DATA",
        continuity_state="PROLONGED_INACTIVITY",
        days_since_workout=120,
        safety_flags=["RETURN_AFTER_100_DAYS_V1"],
    )
    result = asyncio.run(provider.summarize_progress(request))

    assert result.resultType == "INFORMATION"
    assert result.continuityEvaluation.hasSafetyAlert is True
    assert "CẢNH BÁO AN TOÀN" in result.summary
    assert any("RETURN_AFTER_100_DAYS_V1" in lim for lim in result.limitations)


def test_restricted_measurement_does_not_leak_facts():
    provider = SimulatedF04ProgressProvider()
    request = create_base_request(measurement_availability="RESTRICTED")
    result = asyncio.run(provider.summarize_progress(request))

    assert result.resultType == "INFORMATION"
    assert "measurement" in result.missingData
    assert any("hạn chế theo quyền chia sẻ" in lim for lim in result.limitations)
    assert result.measurementProgressSummary is None


def test_both_no_data_returns_insufficient_data():
    provider = SimulatedF04ProgressProvider()
    request = create_base_request(
        workout_availability="NO_DATA",
        measurement_availability="NO_DATA",
        continuity_state="UNKNOWN_HISTORY",
        days_since_workout=None,
    )
    result = asyncio.run(provider.summarize_progress(request))

    assert result.resultType == "INSUFFICIENT_DATA"
    assert result.reasonCode == "INSUFFICIENT_DATA"
    assert "Không có đủ dữ liệu" in result.summary
