from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Protocol

import httpx

from app.rag.retriever import RetrievedChunkDto

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class RerankResult:
    chunks: list[RetrievedChunkDto]
    applied: bool
    provider: str
    model_version: str
    degraded: bool = False
    degradation_reason: str | None = None
    scores: dict[str, float] = field(default_factory=dict)


class Reranker(Protocol):
    async def rerank(
        self,
        query: str,
        chunks: list[RetrievedChunkDto],
        top_n: int,
    ) -> RerankResult: ...


class DisabledReranker:
    """Explicit no-op reranker when reranking is disabled; never fabricates cross-encoder scores."""

    async def rerank(
        self,
        query: str,
        chunks: list[RetrievedChunkDto],
        top_n: int,
    ) -> RerankResult:
        return RerankResult(
            chunks=list(chunks[:top_n]),
            applied=False,
            provider="disabled",
            model_version="none",
            degraded=False,
            degradation_reason=None,
            scores={},
        )


class HttpRerankerAdapter:
    """HTTP cross-encoder reranker adapter (Cohere/Jina /v1/rerank compatible).

    Enforces bounded input size, timeout, and explicit degradation on upstream errors.
    """

    def __init__(
        self,
        endpoint_url: str,
        api_key: str,
        model: str,
        timeout_seconds: float = 4.0,
        max_documents: int = 40,
        max_chars_per_doc: int = 2000,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        self.endpoint_url = endpoint_url.strip()
        self.api_key = api_key
        self.model = model
        self.timeout_seconds = max(0.5, timeout_seconds)
        self.max_documents = max(1, max_documents)
        self.max_chars_per_doc = max(100, max_chars_per_doc)
        self._transport = transport

    async def rerank(
        self,
        query: str,
        chunks: list[RetrievedChunkDto],
        top_n: int,
    ) -> RerankResult:
        if not chunks or top_n <= 0:
            return RerankResult(
                chunks=[],
                applied=False,
                provider="http-rerank",
                model_version=self.model,
            )

        if not self.endpoint_url or not self.api_key:
            return RerankResult(
                chunks=list(chunks[:top_n]),
                applied=False,
                provider="http-rerank",
                model_version=self.model,
                degraded=True,
                degradation_reason="RERANKER_NOT_CONFIGURED",
            )

        bounded = list(chunks[: self.max_documents])
        documents = [
            f"{c.heading}\n{c.content}"[: self.max_chars_per_doc] if c.heading else c.content[: self.max_chars_per_doc]
            for c in bounded
        ]

        payload = {
            "model": self.model,
            "query": query[:2000],
            "documents": documents,
            "top_n": min(top_n, len(bounded)),
        }
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds, transport=self._transport) as client:
                resp = await client.post(self.endpoint_url, headers=headers, json=payload)
                if resp.status_code != 200:
                    logger.warning("Reranker returned HTTP %d", resp.status_code)
                    return RerankResult(
                        chunks=list(bounded[:top_n]),
                        applied=False,
                        provider="http-rerank",
                        model_version=self.model,
                        degraded=True,
                        degradation_reason=f"RERANKER_HTTP_{resp.status_code}",
                    )
                data = resp.json()
                results = data.get("results", [])
                reranked: list[RetrievedChunkDto] = []
                scores: dict[str, float] = {}
                seen_indices: set[int] = set()
                for item in results:
                    idx = item.get("index")
                    score = item.get("relevance_score")
                    if isinstance(idx, int) and 0 <= idx < len(bounded) and idx not in seen_indices:
                        seen_indices.add(idx)
                        chunk = bounded[idx]
                        reranked.append(chunk)
                        if isinstance(score, (int, float)):
                            scores[chunk.chunkId] = float(score)
                if not reranked:
                    return RerankResult(
                        chunks=list(bounded[:top_n]),
                        applied=False,
                        provider="http-rerank",
                        model_version=self.model,
                        degraded=True,
                        degradation_reason="RERANKER_EMPTY_RESULTS",
                    )
                return RerankResult(
                    chunks=reranked[:top_n],
                    applied=True,
                    provider="http-rerank",
                    model_version=self.model,
                    degraded=False,
                    scores=scores,
                )
        except (httpx.HTTPError, ValueError, OSError) as exc:
            logger.warning("Reranker call failed: %s", type(exc).__name__)
            return RerankResult(
                chunks=list(bounded[:top_n]),
                applied=False,
                provider="http-rerank",
                model_version=self.model,
                degraded=True,
                degradation_reason=f"RERANKER_ERROR:{type(exc).__name__}",
            )
