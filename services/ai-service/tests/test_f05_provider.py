import asyncio
from uuid import UUID

from fastapi.testclient import TestClient

from app.core.config import settings
from app.integrations.simulated_f05_provider import SimulatedF05WorkoutProvider
from app.main import app
from app.schemas.f05_schemas import F05WorkoutRescheduleRequest

client = TestClient(app)

STUDENT_ID = UUID("11111111-1111-4111-8111-111111111111")
OCCURRENCE_ID = UUID("22222222-2222-4222-8222-222222222222")
SESSION_ID = UUID("33333333-3333-4333-8333-333333333333")


def test_simulated_f05_provider_self_performable():
    provider = SimulatedF05WorkoutProvider()
    request = F05WorkoutRescheduleRequest(
        studentId=STUDENT_ID,
        mode="SELF_DIRECTED",
        occurrenceId=OCCURRENCE_ID,
        occurrenceVersion=0,
        planSessionId=SESSION_ID,
        sessionName="Full Body Hypertrophy",
        supervisionRequirement="SELF_PERFORMABLE",
        timezone="Asia/Ho_Chi_Minh",
        plannedStartAt="2026-11-16T02:00:00Z",
        plannedEndAt="2026-11-16T03:00:00Z",
        reason="Work conflict",
    )

    response = asyncio.run(provider.generate_reschedule(request))

    assert response.recommendationType == "WORKOUT_RESCHEDULE_PROPOSAL"
    assert response.confidence >= 0.9
    assert response.reschedule.occurrenceId == OCCURRENCE_ID
    assert response.reschedule.supervisionRequirement == "SELF_PERFORMABLE"
    assert response.reschedule.destination.timezone == "Asia/Ho_Chi_Minh"
    assert response.reschedule.destination.startUtcOffset == "+07:00"
    assert response.reschedule.destination.endUtcOffset == "+07:00"
    assert response.reschedule.destination.localStart.endswith("T09:00:00")
    assert response.reschedule.destination.localEnd.endswith("T10:00:00")


def test_simulated_f05_provider_preserves_coach_required_invariant():
    """T10: Provider must never degrade COACH_REQUIRED to SELF_PERFORMABLE."""
    provider = SimulatedF05WorkoutProvider()
    request = F05WorkoutRescheduleRequest(
        studentId=STUDENT_ID,
        mode="HUMAN_COACH",
        occurrenceId=OCCURRENCE_ID,
        occurrenceVersion=1,
        planSessionId=SESSION_ID,
        sessionName="Heavy Squat & Deadlift",
        supervisionRequirement="COACH_REQUIRED",
        timezone="Asia/Ho_Chi_Minh",
        plannedStartAt="2026-11-16T08:00:00Z",
        hasLinkedAppointment=True,
    )

    response = asyncio.run(provider.generate_reschedule(request))

    assert response.recommendationType == "WORKOUT_RESCHEDULE_PROPOSAL"
    assert response.reschedule.supervisionRequirement == "COACH_REQUIRED"


def test_internal_workout_reschedule_endpoint():
    req_body = {
        "studentId": str(STUDENT_ID),
        "mode": "SELF_DIRECTED",
        "occurrenceId": str(OCCURRENCE_ID),
        "occurrenceVersion": 0,
        "planSessionId": str(SESSION_ID),
        "sessionName": "Upper Body Push",
        "supervisionRequirement": "SELF_PERFORMABLE",
        "timezone": "Asia/Ho_Chi_Minh",
        "plannedStartAt": "2026-11-16T02:00:00Z",
        "reason": "Rainstorm",
    }

    # Without internal key -> 401/403
    resp_unauth = client.post("/internal/v1/workout-reschedules/generate", json=req_body)
    assert resp_unauth.status_code in (401, 403)

    # With internal key -> 200
    headers = {"X-Internal-Service-Key": settings.internal_service_key}
    resp = client.post("/internal/v1/workout-reschedules/generate", json=req_body, headers=headers)
    assert resp.status_code == 200
    data = resp.json()
    assert data["recommendationType"] == "WORKOUT_RESCHEDULE_PROPOSAL"
    assert data["reschedule"]["occurrenceId"] == str(OCCURRENCE_ID)
    assert data["reschedule"]["destination"]["timezone"] == "Asia/Ho_Chi_Minh"
