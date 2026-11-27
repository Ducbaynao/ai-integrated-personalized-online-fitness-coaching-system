from __future__ import annotations

from dataclasses import dataclass, field

from app.core.config import settings
from app.rag.context_budget import pack_context_chunks
from app.rag.mmr import apply_mmr
from app.rag.reranker import DisabledReranker, HttpRerankerAdapter, Reranker
from app.rag.retriever import KnowledgeRetriever, RetrievedChunkDto


@dataclass(frozen=True)
class PipelineRetrievalResult:
    chunks: list[RetrievedChunkDto]
    server_responded: bool
    candidate_count: int = 0
    rerank_applied: bool = False
    rerank_provider: str = "disabled"
    rerank_model_version: str = "none"
    mmr_applied: bool = False
    degraded: bool = False
    degradation_reason: str | None = None
    stage_ranks: list[dict[str, object]] = field(default_factory=list)


def build_default_reranker() -> Reranker:
    if not settings.rag_rerank_enabled:
        return DisabledReranker()
    return HttpRerankerAdapter(
        endpoint_url=settings.rag_rerank_url,
        api_key=settings.rag_rerank_api_key.get_secret_value(),
        model=settings.rag_rerank_model,
        timeout_seconds=settings.rag_rerank_timeout_seconds,
    )


class RetrievalPipeline:
    """Retrieve -> Rerank -> Relevance Gate -> MMR -> Context Budget."""

    def __init__(
        self,
        retriever: KnowledgeRetriever | None = None,
        reranker: Reranker | None = None,
    ):
        self.retriever = retriever or KnowledgeRetriever()
        self.reranker = reranker or build_default_reranker()

    async def run(
        self,
        query: str,
        allowed_source_ids: list[str],
        limit: int = 5,
        run_id: str | None = None,
        correlation_id: str | None = None,
    ) -> PipelineRetrievalResult:
        rerank_or_mmr = settings.rag_rerank_enabled or settings.rag_mmr_enabled
        candidate_limit = max(limit, 15) if rerank_or_mmr else limit

        retrieval_res = await self.retriever.retrieve(
            query=query,
            allowed_source_ids=allowed_source_ids,
            limit=candidate_limit,
            run_id=run_id,
            correlation_id=correlation_id,
        )
        if not retrieval_res.server_responded or not retrieval_res.chunks:
            return PipelineRetrievalResult(
                chunks=[],
                server_responded=retrieval_res.server_responded,
                candidate_count=0,
            )

        candidates = list(retrieval_res.chunks)
        rerank_top_n = min(len(candidates), 15) if settings.rag_mmr_enabled else limit
        rerank_res = await self.reranker.rerank(query=query, chunks=candidates, top_n=rerank_top_n)

        selected = list(rerank_res.chunks)
        mmr_applied = False
        if settings.rag_mmr_enabled and len(selected) > 1:
            selected = apply_mmr(
                chunks=selected,
                top_k=limit,
                lambda_param=settings.rag_mmr_lambda,
                override_scores=rerank_res.scores or None,
            )
            mmr_applied = True
        else:
            selected = selected[:limit]

        packed = pack_context_chunks(selected, max_chars=settings.rag_context_max_chars)
        initial_rank = {c.chunkId: i + 1 for i, c in enumerate(candidates)}
        stage_ranks = [
            {
                "chunkId": c.chunkId,
                "documentCode": c.documentCode,
                "version": c.version,
                "retrievalRank": initial_rank.get(c.chunkId),
                "finalRank": idx + 1,
                "cosineSimilarity": c.similarityScore,
            }
            for idx, c in enumerate(packed)
        ]

        return PipelineRetrievalResult(
            chunks=packed,
            server_responded=True,
            candidate_count=len(candidates),
            rerank_applied=rerank_res.applied,
            rerank_provider=rerank_res.provider,
            rerank_model_version=rerank_res.model_version,
            mmr_applied=mmr_applied,
            degraded=rerank_res.degraded,
            degradation_reason=rerank_res.degradation_reason,
            stage_ranks=stage_ranks,
        )
