import asyncio
from uuid import UUID

from fastapi.testclient import TestClient

from app.core.config import settings
from app.integrations.simulated_f03_provider import SimulatedF03WorkoutProvider
from app.main import app
from app.schemas.f03_schemas import F03WorkoutAdjustmentRequest, TargetRefSchema

client = TestClient(app)

STUDENT_ID = UUID("11111111-1111-4111-8111-111111111111")
OCCURRENCE_ID = UUID("22222222-2222-4222-8222-222222222222")
PRESCRIPTION_ID = UUID("33333333-3333-4333-8333-333333333333")
VARIATION_ID = UUID("44444444-4444-4444-8444-444444444444")


def test_simulated_f03_workout_provider_load_increase():
    provider = SimulatedF03WorkoutProvider()
    request = F03WorkoutAdjustmentRequest(
        studentId=STUDENT_ID,
        mode="SELF_DIRECTED",
        scope="ONE_MATERIALIZED_OCCURRENCE",
        target=TargetRefSchema(
            scope="ONE_MATERIALIZED_OCCURRENCE",
            plannedWorkoutId=OCCURRENCE_ID,
            plannedSessionExerciseId=PRESCRIPTION_ID,
        ),
        requestedChangeType="LOAD_ADJUSTMENT",
        allowLoadIncrease=True,
        baselinePrescription={
            "exerciseVariationId": str(VARIATION_ID),
            "exerciseName": "Bench Press",
            "targetLoad": 50.0,
        },
    )

    response = asyncio.run(provider.generate_adjustment(request))

    assert response.recommendationType == "WORKOUT_ADJUSTMENT_PROPOSAL"
    assert response.confidence > 0.8
    assert len(response.changes) == 1
    change = response.changes[0]
    assert change.changeType == "LOAD_ADJUSTMENT"
    assert change.after.direction == "INCREASE"
    assert change.after.deltaLoad == 2.5
    assert change.after.targetLoad == 52.5


def test_simulated_f03_workout_provider_exercise_substitution():
    provider = SimulatedF03WorkoutProvider()
    request = F03WorkoutAdjustmentRequest(
        studentId=STUDENT_ID,
        mode="SELF_DIRECTED",
        scope="ONE_MATERIALIZED_OCCURRENCE",
        target=TargetRefSchema(
            scope="ONE_MATERIALIZED_OCCURRENCE",
            plannedWorkoutId=OCCURRENCE_ID,
            plannedSessionExerciseId=PRESCRIPTION_ID,
        ),
        requestedChangeType="EXERCISE_SUBSTITUTION",
        baselinePrescription={
            "exerciseVariationId": str(VARIATION_ID),
            "exerciseName": "Barbell Squat",
            "movementPattern": "SQUAT",
            "equipmentType": "BARBELL",
        },
    )

    response = asyncio.run(provider.generate_adjustment(request))

    assert response.recommendationType == "WORKOUT_ADJUSTMENT_PROPOSAL"
    change = response.changes[0]
    assert change.changeType == "EXERCISE_SUBSTITUTION"
    assert change.before.exerciseVariationId != change.after.replacementVariationId


def test_internal_f03_generate_endpoint_requires_auth():
    payload = {
        "studentId": str(STUDENT_ID),
        "mode": "SELF_DIRECTED",
        "scope": "ONE_MATERIALIZED_OCCURRENCE",
        "target": {
            "scope": "ONE_MATERIALIZED_OCCURRENCE",
            "plannedWorkoutId": str(OCCURRENCE_ID),
            "plannedSessionExerciseId": str(PRESCRIPTION_ID),
        },
    }

    # Without header
    res_no_auth = client.post("/internal/v1/workout-adjustments/generate", json=payload)
    assert res_no_auth.status_code == 401

    # With invalid header
    res_bad_auth = client.post(
        "/internal/v1/workout-adjustments/generate",
        json=payload,
        headers={"X-Internal-Service-Key": "wrong-key"},
    )
    assert res_bad_auth.status_code in (401, 403)

    # With valid header
    res_valid = client.post(
        "/internal/v1/workout-adjustments/generate",
        json=payload,
        headers={"X-Internal-Service-Key": settings.internal_service_key},
    )
    assert res_valid.status_code == 200
    data = res_valid.json()
    assert data["recommendationType"] == "WORKOUT_ADJUSTMENT_PROPOSAL"
