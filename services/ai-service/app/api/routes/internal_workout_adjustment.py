from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.security import verify_internal_service_key
from app.integrations.simulated_f03_provider import SimulatedF03WorkoutProvider
from app.schemas.f03_schemas import F03WorkoutAdjustmentRequest, F03WorkoutAdjustmentResponse

router = APIRouter(
    prefix="/internal/v1/workout-adjustments",
    tags=["internal-workout-adjustments"],
    dependencies=[Depends(verify_internal_service_key)],
)


def get_f03_provider() -> SimulatedF03WorkoutProvider:
    return SimulatedF03WorkoutProvider()


@router.post("/generate", response_model=F03WorkoutAdjustmentResponse)
async def generate_workout_adjustment(
    request: F03WorkoutAdjustmentRequest,
    provider: Annotated[SimulatedF03WorkoutProvider, Depends(get_f03_provider)],
) -> F03WorkoutAdjustmentResponse:
    """Internal endpoint invoked by Spring Boot to generate a targeted workout adjustment proposal."""
    return await provider.generate_adjustment(request)
