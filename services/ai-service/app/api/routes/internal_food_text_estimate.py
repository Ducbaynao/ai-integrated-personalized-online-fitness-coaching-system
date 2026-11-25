from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.security import verify_internal_service_key
from app.integrations.simulated_f06_provider import SimulatedF06FoodTextProvider
from app.schemas.f06_schemas import F06FoodTextEstimateRequest, F06FoodTextEstimateResponse

router = APIRouter(
    prefix="/internal/v1/food-text-estimates",
    tags=["internal-food-text-estimates"],
    dependencies=[Depends(verify_internal_service_key)],
)


def get_f06_provider() -> SimulatedF06FoodTextProvider:
    return SimulatedF06FoodTextProvider()


@router.post("/generate", response_model=F06FoodTextEstimateResponse)
async def generate_food_text_estimate(
    request: F06FoodTextEstimateRequest,
    provider: Annotated[SimulatedF06FoodTextProvider, Depends(get_f06_provider)],
) -> F06FoodTextEstimateResponse:
    """Spring-only recognition boundary; no Food Log or catalog decision occurs here."""
    return await provider.estimate(request)
