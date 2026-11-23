from app.schemas.f06_schemas import (
    F06FoodTextEstimateRequest,
    F06FoodTextEstimateResponse,
    F06FoodTextItem,
)


class SimulatedF06FoodTextProvider:
    """Safe fallback fixture provider: it never asserts food identity or nutrients."""

    async def estimate(self, request: F06FoodTextEstimateRequest) -> F06FoodTextEstimateResponse:
        # Request validation occurs at the internal route. A simulated response
        # deliberately requires Student correction instead of inventing a match.
        return F06FoodTextEstimateResponse(
            items=[
                F06FoodTextItem(
                    detectedName=request.text.strip()[:200],
                    candidateNames=[],
                    quantity=None,
                    unitText=None,
                    confidence=None,
                    missingData=["FOOD_IDENTITY", "QUANTITY", "UNIT"],
                    limitations=["No food identity or portion was inferred."],
                )
            ],
            missingData=["FOOD_IDENTITY", "QUANTITY", "UNIT"],
            limitations=["Simulated provider has not identified food; use catalog/manual entry."],
        )
