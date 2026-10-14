from typing import Protocol

from app.schemas.f01_schemas import F01InternalContextEnvelope, F01KnowledgeQueryResult


class F01KnowledgeProvider(Protocol):
    """Protocol defining the interface for F01 knowledge inquiry generation."""

    async def query(self, envelope: F01InternalContextEnvelope) -> F01KnowledgeQueryResult:
        ...
