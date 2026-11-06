from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.security import verify_internal_service_key
from app.integrations.simulated_f04_provider import SimulatedF04ProgressProvider
from app.schemas.f04_schemas import F04ProgressSummaryRequest, F04ProgressSummaryResult

router = APIRouter(
    prefix="/internal/v1/progress",
    tags=["internal-progress-summary"],
    dependencies=[Depends(verify_internal_service_key)],
)


def get_f04_provider() -> SimulatedF04ProgressProvider:
    return SimulatedF04ProgressProvider()


@router.post("/summarize", response_model=F04ProgressSummaryResult)
async def summarize_progress(
    request: F04ProgressSummaryRequest,
    provider: Annotated[SimulatedF04ProgressProvider, Depends(get_f04_provider)],
) -> F04ProgressSummaryResult:
    """Internal endpoint invoked by Spring Boot to summarize student fitness progress from verified facts."""
    return await provider.summarize_progress(request)
