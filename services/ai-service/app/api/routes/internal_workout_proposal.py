from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.security import verify_internal_service_key
from app.integrations.simulated_f02_provider import SimulatedF02WorkoutProvider
from app.schemas.f02_schemas import F02WorkoutProposalRequest, F02WorkoutProposalResponse

router = APIRouter(
    prefix="/internal/v1/workout-proposals",
    tags=["internal-workout-proposals"],
    dependencies=[Depends(verify_internal_service_key)],
)


def get_f02_provider() -> SimulatedF02WorkoutProvider:
    return SimulatedF02WorkoutProvider()


@router.post("/generate", response_model=F02WorkoutProposalResponse)
async def generate_workout_proposal(
    request: F02WorkoutProposalRequest,
    provider: Annotated[SimulatedF02WorkoutProvider, Depends(get_f02_provider)],
) -> F02WorkoutProposalResponse:
    """Internal endpoint invoked by Spring Boot to generate a 1-week structured workout proposal."""
    return await provider.generate_proposal(request)
