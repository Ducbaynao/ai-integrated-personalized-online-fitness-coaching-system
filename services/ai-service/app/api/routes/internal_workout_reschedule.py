from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.security import verify_internal_service_key
from app.integrations.simulated_f05_provider import SimulatedF05WorkoutProvider
from app.schemas.f05_schemas import F05WorkoutRescheduleRequest, F05WorkoutRescheduleResponse

router = APIRouter(
    prefix="/internal/v1/workout-reschedules",
    tags=["internal-workout-reschedules"],
    dependencies=[Depends(verify_internal_service_key)],
)


def get_f05_provider() -> SimulatedF05WorkoutProvider:
    return SimulatedF05WorkoutProvider()


@router.post("/generate", response_model=F05WorkoutRescheduleResponse)
async def generate_workout_reschedule(
    request: F05WorkoutRescheduleRequest,
    provider: Annotated[SimulatedF05WorkoutProvider, Depends(get_f05_provider)],
) -> F05WorkoutRescheduleResponse:
    """Internal endpoint invoked by Spring Boot to generate an AI workout rescheduling recommendation."""
    return await provider.generate_reschedule(request)
