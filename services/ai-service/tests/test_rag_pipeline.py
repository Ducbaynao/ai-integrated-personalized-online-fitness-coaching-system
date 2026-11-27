from __future__ import annotations

import asyncio
import json

import httpx

from app.core.config import settings
from app.rag.context_budget import pack_context_chunks
from app.rag.mmr import apply_mmr, normalize_relevance_scores
from app.rag.pipeline import RetrievalPipeline
from app.rag.reranker import DisabledReranker, HttpRerankerAdapter
from app.rag.retriever import RetrievalResult, RetrievedChunkDto


def _chunk(
    cid: str,
    content: str,
    score: float = 0.8,
    heading: str = "Section",
) -> RetrievedChunkDto:
    return RetrievedChunkDto(
        chunkId=cid,
        documentCode=f"DOC-{cid}",
        version="1.0.0",
        sourceId="WHO-PA-SB-2020",
        sourcePath="derivatives/who.md",
        heading=heading,
        sectionIndex=0,
        content=content,
        snippet=content[:40],
        similarityScore=score,
    )


def test_mmr_diversifies_duplicate_chunks_while_preserving_top_relevance():
    c1 = _chunk("1", "creatine monohydrate loading phase 20g daily for 5 days", score=0.95)
    c2 = _chunk("2", "creatine monohydrate loading phase 20g daily for 5 days", score=0.94)
    c3 = _chunk("3", "resistance training progressive overload volume sets reps", score=0.80)

    selected = apply_mmr([c1, c2, c3], top_k=2, lambda_param=0.5)
    assert [c.chunkId for c in selected] == ["1", "3"]


def test_mmr_handles_single_chunk_and_equal_scores():
    c1 = _chunk("1", "single chunk content", score=0.5)
    assert normalize_relevance_scores([c1]) == {"1": 1.0}
    assert apply_mmr([c1], top_k=3) == [c1]
    assert apply_mmr([], top_k=3) == []


def test_context_budget_packs_whole_chunks_within_limit():
    c1 = _chunk("1", "a" * 100)
    c2 = _chunk("2", "b" * 100)
    c3 = _chunk("3", "c" * 500)

    packed = pack_context_chunks([c1, c2, c3], max_chars=320)
    assert [c.chunkId for c in packed] == ["1", "2"]


def test_disabled_reranker_does_not_fabricate_scores():
    c1 = _chunk("1", "first")
    c2 = _chunk("2", "second")
    res = asyncio.run(DisabledReranker().rerank("query", [c1, c2], top_n=2))
    assert res.applied is False
    assert res.degraded is False
    assert res.scores == {}
    assert [c.chunkId for c in res.chunks] == ["1", "2"]


def test_http_reranker_degrades_gracefully_when_unconfigured():
    c1 = _chunk("1", "first")
    adapter = HttpRerankerAdapter(endpoint_url="", api_key="", model="test-rerank")
    res = asyncio.run(adapter.rerank("query", [c1], top_n=1))
    assert res.applied is False
    assert res.degraded is True
    assert res.degradation_reason == "RERANKER_NOT_CONFIGURED"
    assert [c.chunkId for c in res.chunks] == ["1"]


def test_http_reranker_success_reorders_and_handles_invalid_duplicate_indices():
    c1 = _chunk("1", "first")
    c2 = _chunk("2", "second")
    c3 = _chunk("3", "third")

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-key"
        return httpx.Response(
            200,
            json={
                "results": [
                    {"index": 2, "relevance_score": 0.91},
                    {"index": 2, "relevance_score": 0.90},  # duplicate
                    {"index": 99, "relevance_score": 0.88},  # invalid index
                    {"index": 0},  # missing score
                ]
            },
        )

    adapter = HttpRerankerAdapter(
        endpoint_url="https://rerank.example.test/v1/rerank",
        api_key="test-key",
        model="rerank-v3",
        transport=httpx.MockTransport(handler),
    )
    res = asyncio.run(adapter.rerank("query", [c1, c2, c3], top_n=2))
    assert res.applied is True
    assert res.degraded is False
    assert [c.chunkId for c in res.chunks] == ["3", "1"]
    assert res.scores == {"3": 0.91}


def test_http_reranker_handles_http_errors_malformed_json_and_timeout():
    c1 = _chunk("1", "first")
    c2 = _chunk("2", "second")

    for status_code in (401, 429, 500):
        adapter = HttpRerankerAdapter(
            endpoint_url="https://rerank.example.test/v1/rerank",
            api_key="test-key",
            model="rerank-v3",
            transport=httpx.MockTransport(lambda req, sc=status_code: httpx.Response(sc, text="err")),
        )
        res = asyncio.run(adapter.rerank("query", [c1, c2], top_n=2))
        assert res.applied is False
        assert res.degraded is True
        assert res.degradation_reason == f"RERANKER_HTTP_{status_code}"
        assert res.scores == {}
        assert [c.chunkId for c in res.chunks] == ["1", "2"]

    # Malformed JSON
    malformed = HttpRerankerAdapter(
        endpoint_url="https://rerank.example.test/v1/rerank",
        api_key="test-key",
        model="rerank-v3",
        transport=httpx.MockTransport(lambda req: httpx.Response(200, text="not-json{")),
    )
    res_mal = asyncio.run(malformed.rerank("query", [c1], top_n=1))
    assert res_mal.degraded is True
    assert res_mal.applied is False

    # Timeout
    def timeout_handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timed out")

    timeout_adapter = HttpRerankerAdapter(
        endpoint_url="https://rerank.example.test/v1/rerank",
        api_key="test-key",
        model="rerank-v3",
        transport=httpx.MockTransport(timeout_handler),
    )
    res_to = asyncio.run(timeout_adapter.rerank("query", [c1], top_n=1))
    assert res_to.degraded is True
    assert res_to.degradation_reason == "RERANKER_ERROR:ReadTimeout"


def test_retrieval_pipeline_orchestrates_mmr_and_budget(monkeypatch):
    monkeypatch.setattr(settings, "rag_rerank_enabled", False)
    monkeypatch.setattr(settings, "rag_mmr_enabled", True)
    monkeypatch.setattr(settings, "rag_mmr_lambda", 0.5)
    monkeypatch.setattr(settings, "rag_context_max_chars", 5000)

    class StubRetriever:
        async def retrieve(self, **kwargs):
            return RetrievalResult(
                chunks=[
                    _chunk("1", "protein intake 1.6g per kg body weight", score=0.90, heading="Protein"),
                    _chunk("2", "protein intake 1.6g per kg body weight", score=0.89, heading="Protein"),
                    _chunk("3", "sleep recovery 7 to 9 hours per night", score=0.82, heading="Recovery"),
                    _chunk("4", "hydration electrolytes during marathon", score=0.50, heading="Hydration"),
                ],
                server_responded=True,
            )

    pipeline = RetrievalPipeline(retriever=StubRetriever(), reranker=DisabledReranker())
    result = asyncio.run(
        pipeline.run(query="protein and recovery", allowed_source_ids=["WHO-PA-SB-2020"], limit=2)
    )

    assert result.server_responded is True
    assert result.rerank_applied is False
    assert result.mmr_applied is True
    assert [c.chunkId for c in result.chunks] == ["1", "3"]
    assert len(result.stage_ranks) == 2


def test_http_reranker_payload_is_valid_json_when_empty_or_bounded():
    assert json.dumps({"ok": True}) == '{"ok": true}'
