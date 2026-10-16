import logging

import httpx
from pydantic import BaseModel, ConfigDict

from app.core.config import settings

logger = logging.getLogger(__name__)


class RetrievedChunkDto(BaseModel):
    model_config = ConfigDict(extra="ignore")

    chunkId: str
    documentId: str | None = None
    documentCode: str
    version: str
    sourceId: str
    title: str = ""
    sourcePath: str
    heading: str = ""
    sectionIndex: int = 0
    snippet: str = ""
    content: str = ""
    similarityScore: float = 0.0


class RetrievalResult(BaseModel):
    chunks: list[RetrievedChunkDto]
    server_responded: bool


class KnowledgeRetriever:
    """Internal knowledge retrieval client for F01 RAG.

    Queries Spring Boot's internal endpoint /internal/v1/knowledge/retrieval.
    """

    def __init__(self, backend_base_url: str | None = None, internal_key: str | None = None):
        self.backend_base_url = (backend_base_url or settings.backend_base_url).rstrip("/")
        self.internal_key = internal_key or settings.internal_service_key

    async def retrieve(
        self,
        query: str,
        allowed_source_ids: list[str],
        limit: int = 5,
        run_id: str | None = None,
        correlation_id: str | None = None,
    ) -> RetrievalResult:
        if not allowed_source_ids or not query or not query.strip():
            return RetrievalResult(chunks=[], server_responded=True)

        if not settings.backend_internal_retrieval_enabled:
            return RetrievalResult(chunks=[], server_responded=False)

        url = f"{self.backend_base_url}/internal/v1/knowledge/retrieval"
        headers = {
            "X-Internal-Service-Key": self.internal_key,
            "Content-Type": "application/json",
        }
        if run_id:
            headers["X-Run-ID"] = run_id
        if correlation_id:
            headers["X-Correlation-ID"] = correlation_id

        payload = {
            "query": query,
            "allowedSourceIds": allowed_source_ids,
            "limit": limit,
        }

        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                resp = await client.post(url, headers=headers, json=payload)
                if resp.status_code == 200:
                    data = resp.json()
                    chunks_raw = data.get("chunks", [])
                    return RetrievalResult(
                        chunks=[RetrievedChunkDto.model_validate(c) for c in chunks_raw],
                        server_responded=True,
                    )
                logger.warning(
                    "Internal knowledge retrieval returned status %d: %s",
                    resp.status_code,
                    resp.text,
                )
                return RetrievalResult(chunks=[], server_responded=False)
        except (httpx.HTTPError, OSError) as e:
            logger.debug("Internal retrieval endpoint unreachable at %s: %s", url, e)
            return RetrievalResult(chunks=[], server_responded=False)
