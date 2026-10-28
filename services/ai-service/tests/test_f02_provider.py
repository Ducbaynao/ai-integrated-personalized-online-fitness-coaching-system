import asyncio
from uuid import UUID
from fastapi.testclient import TestClient

from app.core.config import settings
from app.integrations.simulated_f02_provider import SimulatedF02WorkoutProvider
from app.main import app
from app.schemas.f02_schemas import F02WorkoutProposalRequest

client = TestClient(app)


def test_simulated_f02_workout_provider_returns_valid_proposal():
    provider = SimulatedF02WorkoutProvider()
    request = F02WorkoutProposalRequest(
        studentId=UUID("11111111-1111-4111-8111-111111111111"),
        coachingPeriodId=UUID("22222222-2222-4222-8222-222222222222"),
        mode="SELF_DIRECTED",
        fitnessLevel="INTERMEDIATE",
        goalType="HYPERTROPHY",
        equipmentAvailable=["DUMBBELLS", "BARBELL"],
    )

    response = asyncio.run(provider.generate_proposal(request))

    assert response.recommendationType == "WORKOUT_PLAN_PROPOSAL"
    assert response.confidence > 0.8
    assert response.plan.durationWeeks == 1
    assert len(response.plan.sessions) == 3
    assert all(session.weekNumber == 1 for session in response.plan.sessions)
    assert all(len(session.prescriptions) >= 1 for session in response.plan.sessions)


def test_simulated_f02_workout_provider_handles_four_days_selection():
    provider = SimulatedF02WorkoutProvider()
    request = F02WorkoutProposalRequest(
        studentId=UUID("11111111-1111-4111-8111-111111111111"),
        mode="SELF_DIRECTED",
        fitnessLevel="BEGINNER",
        goalType="STRENGTH",
        preferences={"daysPerWeek": 4},
    )

    response = asyncio.run(provider.generate_proposal(request))

    assert response.recommendationType == "WORKOUT_PLAN_PROPOSAL"
    assert response.plan.durationWeeks == 1
    assert len(response.plan.sessions) == 4
    assert any("4-day" in r for r in response.reasoning)


def test_internal_workout_proposal_endpoint_requires_auth():
    payload = {
        "studentId": "11111111-1111-4111-8111-111111111111",
        "mode": "SELF_DIRECTED",
        "fitnessLevel": "BEGINNER",
        "goalType": "GENERAL_FITNESS",
        "equipmentAvailable": ["BODYWEIGHT"],
    }
    # Without key -> 401
    res = client.post("/internal/v1/workout-proposals/generate", json=payload)
    assert res.status_code == 401

    # With invalid key -> 403
    res = client.post(
        "/internal/v1/workout-proposals/generate",
        json=payload,
        headers={"X-Internal-Service-Key": "wrong-key"},
    )
    assert res.status_code == 403

    # With valid key -> 200
    res = client.post(
        "/internal/v1/workout-proposals/generate",
        json=payload,
        headers={"X-Internal-Service-Key": settings.internal_service_key},
    )
    assert res.status_code == 200
    data = res.json()
    assert data["recommendationType"] == "WORKOUT_PLAN_PROPOSAL"
    assert data["plan"]["durationWeeks"] == 1
    assert len(data["plan"]["sessions"]) == 3
    assert data["confidence"] > 0.8
