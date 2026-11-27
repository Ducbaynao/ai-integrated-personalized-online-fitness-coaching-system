from typing import Annotated

from fastapi import APIRouter, Depends

from app.agent.loop import BoundedAgentRunner
from app.agent.model_adapter import OpenAiAgentModelAdapter, SimulatedAgentModelAdapter
from app.agent.schemas import AgentTurnRequest, AgentTurnResponse
from app.core.config import settings
from app.core.security import verify_internal_service_key

router = APIRouter(
    prefix="/internal/v1/agent",
    tags=["internal-agent"],
    dependencies=[Depends(verify_internal_service_key)],
)


def get_agent_runner() -> BoundedAgentRunner:
    if settings.f01_provider.strip().lower() == "openai":
        return BoundedAgentRunner(model_adapter=OpenAiAgentModelAdapter())
    if settings.f01_provider.strip().lower() == "simulated":
        return BoundedAgentRunner(model_adapter=SimulatedAgentModelAdapter())
    raise RuntimeError("Unsupported agent provider configuration")


@router.post("/turns", response_model=AgentTurnResponse)
async def execute_agent_turn(
    request: AgentTurnRequest,
    runner: Annotated[BoundedAgentRunner, Depends(get_agent_runner)],
) -> AgentTurnResponse:
    """Executes one bounded, non-authoritative agent turn bound to a Spring AI Run."""
    return await runner.run(request)
