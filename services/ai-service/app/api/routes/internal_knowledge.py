from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import settings
from app.core.security import verify_internal_service_key
from app.integrations.openai_provider import OpenAiKnowledgeProvider
from app.integrations.provider_interface import F01KnowledgeProvider
from app.integrations.simulated_provider import SimulatedF01KnowledgeProvider
from app.schemas.f01_schemas import F01InternalContextEnvelope, F01KnowledgeQueryResult

router = APIRouter(
    prefix="/internal/v1/knowledge",
    tags=["internal-knowledge"],
    dependencies=[Depends(verify_internal_service_key)],
)


def get_f01_provider() -> F01KnowledgeProvider:
    if settings.f01_provider.lower() == "openai":
        return OpenAiKnowledgeProvider()
    return SimulatedF01KnowledgeProvider()


@router.post("/query", response_model=F01KnowledgeQueryResult)
async def query_knowledge(
    envelope: F01InternalContextEnvelope,
    provider: Annotated[F01KnowledgeProvider, Depends(get_f01_provider)],
) -> F01KnowledgeQueryResult:
    """Internal endpoint invoked by Spring Boot to evaluate general fitness knowledge inquiries.

    Accepts server-constructed F01InternalContextEnvelope, enforces service authentication,
    and returns a structured F01KnowledgeQueryResult matching the contract schema.
    """
    return await provider.query(envelope)
