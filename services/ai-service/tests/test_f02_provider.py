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


def test_simulated_f02_workout_provider_prolonged_inactivity_differentiates_from_beginner():
    """T08: Trainee with >= 100 days inactivity receives return-to-training plan and conservative limitations."""
    provider = SimulatedF02WorkoutProvider()
    request = F02WorkoutProposalRequest(
        studentId=UUID("11111111-1111-4111-8111-111111111111"),
        mode="SELF_DIRECTED",
        fitnessLevel="INTERMEDIATE",
        goalType="STRENGTH",
        continuityState="PROLONGED_INACTIVITY",
        daysSinceLastWorkout=120,
    )

    response = asyncio.run(provider.generate_proposal(request))

    assert "Return-to-Training Plan" in response.plan.name
    assert any("prolonged inactivity" in r.lower() for r in response.reasoning)
    assert any(">=100 days" in lim for lim in response.limitations)
    assert any("continuity_state: PROLONGED_INACTIVITY" in d for d in response.usedData)
    assert any("days_since_last_workout: 120" in d for d in response.usedData)


def test_simulated_f02_workout_provider_confirmed_new_beginner():
    """T08: Confirmed new trainee receives foundation routine with mechanics prioritized."""
    provider = SimulatedF02WorkoutProvider()
    request = F02WorkoutProposalRequest(
        studentId=UUID("11111111-1111-4111-8111-111111111111"),
        mode="SELF_DIRECTED",
        fitnessLevel="BEGINNER",
        goalType="GENERAL_FITNESS",
        continuityState="CONFIRMED_NEW",
    )

    response = asyncio.run(provider.generate_proposal(request))

    assert "Starter Plan" in response.plan.name
    assert any("confirmed new" in r.lower() for r in response.reasoning)
    assert any("introductory foundation" in lim.lower() for lim in response.limitations)
    assert any("continuity_state: CONFIRMED_NEW" in d for d in response.usedData)


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


def test_simulated_f02_workout_provider_restricted_history():
    provider = SimulatedF02WorkoutProvider()
    request = F02WorkoutProposalRequest(
        studentId=UUID("11111111-1111-4111-8111-111111111111"),
        mode="HUMAN_COACH",
        fitnessLevel="INTERMEDIATE",
        goalType="GENERAL_FITNESS",
        continuityState="RESTRICTED_HISTORY",
    )
    response = asyncio.run(provider.generate_proposal(request))
    assert any("access restricted" in lim.lower() for lim in response.limitations)
    assert any("continuity_state: RESTRICTED_HISTORY" in d for d in response.usedData)


def test_simulated_f02_workout_provider_prolonged_inactivity_clamps_to_three_days():
    provider = SimulatedF02WorkoutProvider()
    request = F02WorkoutProposalRequest(
        studentId=UUID("11111111-1111-4111-8111-111111111111"),
        mode="SELF_DIRECTED",
        fitnessLevel="INTERMEDIATE",
        goalType="STRENGTH",
        continuityState="PROLONGED_INACTIVITY",
        daysSinceLastWorkout=150,
        preferences={"daysPerWeek": 4},
    )
    response = asyncio.run(provider.generate_proposal(request))
    assert len(response.plan.sessions) == 3
    assert "return-to-training" in response.plan.description.lower()
