from __future__ import annotations

import asyncio
import json
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.agent.loop import BoundedAgentRunner
from app.agent.model_adapter import AgentDecision, OpenAiAgentModelAdapter
from app.agent.schemas import AgentRunBinding, AgentTurnRequest, ToolCallRequest, ToolObservation
from app.agent.tools import ToolGateway
from app.core.config import settings
from app.main import app
from app.rag.pipeline import PipelineRetrievalResult
from app.rag.retriever import RetrievedChunkDto


@pytest.mark.parametrize("result_type,kind,expected", [
    ("CLARIFICATION_REQUIRED", "CLARIFY", "INFORMATION"),
    ("INSUFFICIENT_DATA", "FINAL", "INSUFFICIENT_DATA"),
    ("BLOCKED", "FINAL", "BLOCKED"),
])
def test_openai_structured_terminal_branches(monkeypatch, result_type, kind, expected):
    monkeypatch.setattr(settings, "openai_api_key", SecretStr("offline-key"))
    def handler(request):
        payload = json.loads(request.content)
        assert payload["store"] is False
        assert payload["parallel_tool_calls"] is False
        assert payload["max_completion_tokens"] == settings.openai_max_completion_tokens
        assert all(t["function"]["name"] == "search_knowledge" for t in payload["tools"])
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps({
            "resultType": result_type, "answer": "", "clarificationQuestion": "Which topic?",
            "citedChunkKeys": [], "limitations": [], "missingData": ["Missing evidence"],
        })}}]})
    adapter = OpenAiAgentModelAdapter(transport=httpx.MockTransport(handler))
    decision = asyncio.run(adapter.decide_next(
        AgentTurnRequest(binding=_binding(["search_knowledge"]), message="fitness question"), [], 1,
        remaining_seconds=0.1,
    ))
    assert decision.kind == kind
    assert decision.result_type == expected
    if kind == "CLARIFY":
        assert decision.clarification_question == "Which topic?"


@pytest.mark.parametrize("content", ["not json", "[]", "{}", '{"resultType":"UNKNOWN"}'])
def test_openai_rejects_malformed_and_wrong_schema(monkeypatch, content):
    monkeypatch.setattr(settings, "openai_api_key", SecretStr("offline-key"))
    adapter = OpenAiAgentModelAdapter(transport=httpx.MockTransport(lambda r: httpx.Response(
        200, json={"choices": [{"message": {"content": content}}]},
    )))
    decision = asyncio.run(adapter.decide_next(
        AgentTurnRequest(binding=_binding(), message="fitness question"), [], 1,
    ))
    assert decision.result_type == "INSUFFICIENT_DATA"
    assert decision.reason_code == "VALIDATION_FAILED"


def test_total_deadline_cancels_inflight_model():
    class SlowModel:
        provider_name = "test"
        model_name = "slow"
        async def decide_next(self, *args, **kwargs):
            await asyncio.sleep(2)
            raise AssertionError("Deadline should cancel this model call")
    result = asyncio.run(BoundedAgentRunner(model_adapter=SlowModel()).run(
        AgentTurnRequest(binding=_binding(), message="fitness question", deadlineSeconds=1),
    ))
    assert result.runStatus == "FAILED"
    assert result.reasonCode == "TIMEOUT"


def test_search_denied_after_run_revoked_never_reaches_retrieval():
    from unittest.mock import AsyncMock
    pipeline = AsyncMock()
    gateway = ToolGateway(retrieval_pipeline=pipeline)
    gateway._exec_backend_tool = AsyncMock(return_value=(
        ToolObservation(toolName="search_knowledge", status="DENIED", summary="Run cancelled"), [], None,
    ))
    observation, _, _ = asyncio.run(gateway.execute("search_knowledge", {"query": "WHO"}, _binding()))
    assert observation.status == "DENIED"
    pipeline.run.assert_not_called()


def test_missing_openai_credentials_never_silently_uses_simulated(monkeypatch):
    from app.api.routes.internal_agent import get_agent_runner
    monkeypatch.setattr(settings, "f01_provider", "openai")
    monkeypatch.setattr(settings, "openai_api_key", SecretStr(""))
    runner = get_agent_runner()
    assert isinstance(runner.model_adapter, OpenAiAgentModelAdapter)
    result = asyncio.run(runner.run(AgentTurnRequest(binding=_binding(), message="fitness question")))
    assert result.runStatus == "FAILED"
    assert result.reasonCode == "PROVIDER_ERROR"


class OfflineToolGateway(ToolGateway):
    """Unit-test auth stub; the real HTTP gateway is covered by Backend/FastAPI E2E."""
    async def _authorize_search(self, binding):
        return ToolObservation(toolName="search_knowledge", status="OK", summary="Unit-test authorization")


def _binding(allowed_actions: list[str] | None = None, sources: list[str] | None = None) -> AgentRunBinding:
    return AgentRunBinding(
        runId=uuid4(),
        correlationId=uuid4(),
        actorId=uuid4(),
        actorRole="STUDENT",
        subjectId=uuid4(),
        coachingMode="SELF_DIRECTED",
        allowedActions=allowed_actions if allowed_actions is not None else ["search_knowledge", "get_current_plan"],
        allowedSourceIds=sources if sources is not None else ["WHO-PA-SB-2020"],
    )


def test_tool_gateway_rejects_disallowed_and_forged_fields():
    gateway = ToolGateway()
    binding = _binding(allowed_actions=["search_knowledge"])

    # Disallowed tool
    obs, _, _ = asyncio.run(gateway.execute("get_current_plan", {}, binding))
    assert obs.status == "DENIED"

    # Unknown tool
    obs2, _, _ = asyncio.run(gateway.execute("run_sql", {"sql": "SELECT 1"}, binding))
    assert obs2.status == "DENIED"

    # Extra forged argument (extra="forbid")
    obs3, _, _ = asyncio.run(
        gateway.execute("search_knowledge", {"query": "squat", "limit": 3, "studentId": "other"}, binding)
    )
    assert obs3.status == "ERROR"
    assert "Invalid tool arguments" in obs3.summary


def test_agent_loop_blocks_repeated_calls_and_enforces_budget():
    class RepeatModel:
        provider_name = "test"
        model_name = "repeat-model"

        async def decide_next(self, request, observations, turn_index, remaining_seconds=None):
            return AgentDecision(
                kind="TOOL_CALL",
                tool_call=ToolCallRequest(toolName="search_knowledge", arguments={"query": "squat", "limit": 2}),
            )

    class StubPipeline:
        async def run(self, **kwargs):
            return PipelineRetrievalResult(
                chunks=[
                    RetrievedChunkDto(
                        chunkId="c1",
                        documentCode="WHO-01",
                        version="1.0.0",
                        sourceId="WHO-PA-SB-2020",
                        sourcePath="who.md",
                        heading="Squat",
                        sectionIndex=0,
                        content="Keep neutral spine during squat.",
                        similarityScore=0.8,
                    )
                ],
                server_responded=True,
            )

    runner = BoundedAgentRunner(
        model_adapter=RepeatModel(),
        tool_gateway=OfflineToolGateway(retrieval_pipeline=StubPipeline()),
    )
    req = AgentTurnRequest(binding=_binding(), message="squat form", maxModelTurns=3)
    res = asyncio.run(runner.run(req))

    assert res.stopReason == "MAX_MODEL_TURNS_EXCEEDED"
    assert any(s.status == "REJECTED_REPEAT" for s in res.steps)


def test_agent_loop_respects_cancellation():
    runner = BoundedAgentRunner()
    req = AgentTurnRequest(binding=_binding(), message="huong dan tap squat")
    res = asyncio.run(runner.run(req, is_cancelled=lambda: True))
    assert res.runStatus == "CANCELLED"
    assert res.stopReason == "CANCELLED"


def test_agent_loop_simulated_knowledge_answer_and_blocked_guardrail():
    class StubPipeline:
        async def run(self, **kwargs):
            return PipelineRetrievalResult(
                chunks=[
                    RetrievedChunkDto(
                        chunkId="c1",
                        documentCode="WHO-PA-SB-2020-VI-01",
                        version="1.0.0",
                        sourceId="WHO-PA-SB-2020",
                        sourcePath="who.md",
                        heading="Physical Activity",
                        sectionIndex=0,
                        content="Adults should do 150-300 minutes of moderate-intensity aerobic activity.",
                        similarityScore=0.9,
                    )
                ],
                server_responded=True,
            )

    runner = BoundedAgentRunner(tool_gateway=OfflineToolGateway(retrieval_pipeline=StubPipeline()))
    res = asyncio.run(runner.run(AgentTurnRequest(binding=_binding(), message="khuyen nghi van dong WHO")))
    assert res.runStatus == "SUCCEEDED"
    assert res.resultType == "INFORMATION"
    assert len(res.citations) == 1
    assert res.citations[0].documentId == "WHO-PA-SB-2020-VI-01"

    blocked = asyncio.run(runner.run(AgentTurnRequest(binding=_binding(), message="cach dung steroid tang co")))
    assert blocked.runStatus == "REJECTED_BY_VALIDATOR"
    assert blocked.resultType == "BLOCKED"
    assert blocked.reasonCode == "RULE_BLOCKED"


def test_internal_agent_route_requires_internal_service_key():
    client = TestClient(app)
    payload = {
        "binding": {
            "runId": str(uuid4()),
            "correlationId": str(uuid4()),
            "actorId": str(uuid4()),
            "actorRole": "STUDENT",
            "allowedActions": ["search_knowledge"],
            "allowedSourceIds": [],
        },
        "message": "tư vấn",
    }
    unauth = client.post("/internal/v1/agent/turns", json=payload)
    assert unauth.status_code in (401, 403)

    ok = client.post(
        "/internal/v1/agent/turns",
        headers={"X-Internal-Service-Key": settings.internal_service_key},
        json=payload,
    )
    assert ok.status_code == 200
    assert ok.json()["resultType"] == "CLARIFICATION_REQUIRED"


def test_openai_agent_adapter_structured_final_output_and_multi_search_citations(monkeypatch):
    import json

    import httpx
    from pydantic import SecretStr

    from app.agent.model_adapter import OpenAiAgentModelAdapter

    monkeypatch.setattr(settings, "openai_api_key", SecretStr("test-key"))

    call_count = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        call_count["n"] += 1
        if call_count["n"] == 1:
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "role": "assistant",
                                "tool_calls": [
                                    {
                                        "id": "tc1",
                                        "type": "function",
                                        "function": {
                                            "name": "search_knowledge",
                                            "arguments": json.dumps({"query": "who guidelines", "limit": 2}),
                                        },
                                    }
                                ],
                            }
                        }
                    ]
                },
            )
        if call_count["n"] == 2:
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "role": "assistant",
                                "tool_calls": [
                                    {
                                        "id": "tc2",
                                        "type": "function",
                                        "function": {
                                            "name": "search_knowledge",
                                            "arguments": json.dumps({"query": "protein intake", "limit": 2}),
                                        },
                                    }
                                ],
                            }
                        }
                    ]
                },
            )
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": json.dumps(
                                {
                                    "resultType": "INFORMATION",
                                    "answer": "WHO khuyến nghị 150-300 phút vận động vừa mỗi tuần.",
                                    "clarificationQuestion": None,
                                    "citedChunkKeys": ["WHO-PA-SB-2020-VI-01@1.0.0#0"],
                                    "limitations": ["Áp dụng cho người trưởng thành khỏe mạnh."],
                                    "missingData": [],
                                }
                            ),
                        }
                    }
                ]
            },
        )

    class TwoSearchStubPipeline:
        async def run(self, query: str, **kwargs):
            if "who" in query:
                return PipelineRetrievalResult(
                    chunks=[
                        RetrievedChunkDto(
                            chunkId="c-who",
                            documentCode="WHO-PA-SB-2020-VI-01",
                            version="1.0.0",
                            sourceId="WHO-PA-SB-2020",
                            sourcePath="who.md",
                            heading="Adults",
                            sectionIndex=0,
                            content="Adults should do 150-300 minutes of moderate aerobic activity.",
                            similarityScore=0.9,
                        )
                    ],
                    server_responded=True,
                )
            return PipelineRetrievalResult(
                chunks=[
                    RetrievedChunkDto(
                        chunkId="c-issn",
                        documentCode="ISSN-PROTEIN-EXERCISE-2017-VI-01",
                        version="1.0.0",
                        sourceId="ISSN-PROTEIN-EXERCISE-2017",
                        sourcePath="issn.md",
                        heading="Protein",
                        sectionIndex=0,
                        content="Protein intake 1.4-2.0 g/kg/day.",
                        similarityScore=0.88,
                    )
                ],
                server_responded=True,
            )

    runner = BoundedAgentRunner(
        model_adapter=OpenAiAgentModelAdapter(transport=httpx.MockTransport(handler)),
        tool_gateway=OfflineToolGateway(retrieval_pipeline=TwoSearchStubPipeline()),
    )
    res = asyncio.run(runner.run(AgentTurnRequest(binding=_binding(), message="who và protein")))
    assert res.runStatus == "SUCCEEDED"
    assert res.resultType == "INFORMATION"
    assert len(res.citations) == 1
    assert res.citations[0].documentId == "WHO-PA-SB-2020-VI-01"


def test_agent_search_knowledge_limit_1_uses_effective_top_k_with_saved_rerank_fixture(monkeypatch):
    """Reproduces live E2E AG02-DEV:VI-NO-DIACRITICS-01 where model sends limit=1;
    verifies ToolGateway + RetrievalPipeline + HttpRerankerAdapter keep VI-05#1 in context.
    """
    from app.agent.tools import TOOL_OPENAI_DEFINITIONS
    from app.rag.reranker import HttpRerankerAdapter
    from app.rag.retriever import RetrievalResult

    search_def = next(d for d in TOOL_OPENAI_DEFINITIONS if d["function"]["name"] == "search_knowledge")
    assert search_def["function"]["strict"] is True
    assert search_def["function"]["parameters"]["required"] == ["query", "limit"]
    assert "5" in search_def["function"]["parameters"]["properties"]["limit"]["description"]

    monkeypatch.setattr(settings, "openai_api_key", SecretStr("test-key"))
    monkeypatch.setattr(settings, "rag_rerank_enabled", True)
    monkeypatch.setattr(settings, "rag_mmr_enabled", True)
    monkeypatch.setattr(settings, "rag_search_min_top_k", 5)
    monkeypatch.setattr(settings, "rag_context_max_chars", 8000)

    # Saved candidate order & Cohere rerank-v3.5 scores from live-agent-e2e-9docs-20261127
    saved_candidates = [
        ("WHO-PA-SB-2020-VI-01", 1, 0.5046, 0.7954, "Phạm vi khuyến nghị người trưởng thành 18-64 tuổi"),
        ("WHO-PA-SB-2020-VI-05", 4, 0.4185, 0.5764, "Khuyến nghị hạn chế hành vi tĩnh tại"),
        ("WHO-PA-SB-2020-VI-06", 3, 0.5330, 0.7718, "Lưu ý triển khai theo bối cảnh dân số"),
        ("WHO-PA-SB-2020-VI-05", 1, 0.4559, 0.7712, "Trong tuần: 150-300 phút aerobic vừa hoặc 75-150 phút mạnh"),
        ("NIDDK-WEIGHT-FACTORS-2023-VI-04", 0, 0.3848, 0.0986, "Các yếu tố lối sống và cân nặng"),
        ("WHO-PA-SB-2020-VI-02", 2, 0.3600, 0.1852, "Thuật ngữ hoạt động thể chất"),
        ("WHO-PA-SB-2020-VI-06", 1, 0.3500, 0.3146, "Khuyến nghị bổ sung cho người cao tuổi"),
        ("WHO-PA-SB-2020-VI-06", 0, 0.3400, 0.3933, "Tổng quan nhóm tuổi"),
        ("WHO-PA-SB-2020-VI-05", 0, 0.3300, 0.4904, "Mở đầu khuyến nghị người lớn"),
        ("WHO-PA-SB-2020-VI-01", 0, 0.3200, 0.0778, "Giới thiệu tài liệu WHO 2020"),
        ("WHO-PA-SB-2020-VI-03", 0, 0.3100, 0.1765, "Định nghĩa cường độ vận động"),
        ("WHO-PA-SB-2020-VI-03", 1, 0.3000, 0.1258, "Thang đo cường độ vừa và mạnh"),
        ("WHO-PA-SB-2020-VI-02", 1, 0.2900, 0.1694, "Hành vi tĩnh tại"),
        ("NIDDK-WEIGHT-FACTORS-2023-VI-02", 0, 0.2800, 0.0556, "Yếu tố di truyền"),
        ("WHO-PA-SB-2020-VI-02", 0, 0.2700, 0.1354, "Bảng thuật ngữ"),
    ]
    retriever_calls: list[dict] = []

    class SavedCandidateRetriever:
        async def retrieve(self, query, allowed_source_ids, limit=5, run_id=None, correlation_id=None):
            retriever_calls.append({"query": query, "limit": limit})
            return RetrievalResult(
                chunks=[
                    RetrievedChunkDto(
                        chunkId=f"{doc}#{sec}",
                        documentCode=doc,
                        version="1.1.0",
                        sourceId="WHO-PA-SB-2020" if doc.startswith("WHO") else "NIDDK-WEIGHT-FACTORS-2023",
                        sourcePath=f"derivatives/{doc}.md",
                        heading=f"Section {sec}",
                        sectionIndex=sec,
                        content=text,
                        similarityScore=sim,
                    )
                    for doc, sec, sim, _, text in saved_candidates[:limit]
                ],
                server_responded=True,
            )

    def rerank_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "results": [
                    {"index": idx, "relevance_score": rscore}
                    for idx, (_, _, _, rscore, _) in enumerate(saved_candidates)
                ]
            },
        )

    from app.rag.pipeline import RetrievalPipeline

    pipeline = RetrievalPipeline(
        retriever=SavedCandidateRetriever(),
        reranker=HttpRerankerAdapter(
            endpoint_url="https://rerank.example.test/v2/rerank",
            api_key="rerank-key",
            model="rerank-v3.5",
            transport=httpx.MockTransport(rerank_handler),
        ),
    )

    observed_observations: list[dict] = []
    llm_turn = {"n": 0}

    def llm_handler(request: httpx.Request) -> httpx.Response:
        llm_turn["n"] += 1
        body = json.loads(request.content)
        if llm_turn["n"] == 1:
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "role": "assistant",
                                "tool_calls": [
                                    {
                                        "id": "call-1",
                                        "type": "function",
                                        "function": {
                                            "name": "search_knowledge",
                                            "arguments": json.dumps({
                                                "query": "WHO 2020 khuyến nghị người trưởng thành tập vận động bao nhiêu phút mỗi tuần",
                                                "limit": 1,
                                            }),
                                        },
                                    }
                                ],
                            }
                        }
                    ]
                },
            )
        obs_msg = next(
            m["content"] for m in body["messages"] if m["content"].startswith("[UNTRUSTED_TOOL_OBSERVATIONS_START]")
        )
        obs_payload = json.loads(obs_msg.split("\n", 1)[1].rsplit("\n", 1)[0])
        observed_observations.extend(obs_payload)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": json.dumps({
                                "resultType": "INFORMATION",
                                "answer": "WHO 2020 khuyến nghị 150-300 phút aerobic vừa hoặc 75-150 phút mạnh mỗi tuần.",
                                "clarificationQuestion": None,
                                "citedChunkKeys": ["WHO-PA-SB-2020-VI-05@1.1.0#1"],
                                "limitations": [],
                                "missingData": [],
                            }),
                        }
                    }
                ]
            },
        )

    runner = BoundedAgentRunner(
        model_adapter=OpenAiAgentModelAdapter(transport=httpx.MockTransport(llm_handler)),
        tool_gateway=OfflineToolGateway(retrieval_pipeline=pipeline),
    )
    res = asyncio.run(
        runner.run(
            AgentTurnRequest(
                binding=_binding(sources=["WHO-PA-SB-2020", "NIDDK-WEIGHT-FACTORS-2023"]),
                message="WHO 2020 khuyen nghi nguoi truong thanh tap van dong bao nhieu phut moi tuan?",
            )
        )
    )

    assert retriever_calls[0]["limit"] == 15
    assert len(observed_observations) == 1
    obs_data = observed_observations[0]["data"]
    assert obs_data["requestedLimit"] == 1
    assert obs_data["effectiveLimit"] == 5
    assert obs_data["returnedCount"] == 5
    assert obs_data["candidateCount"] == 15
    assert obs_data["rerankApplied"] is True
    assert obs_data["mmrApplied"] is True
    returned_keys = [f"{c['documentCode']}#{c['sectionIndex']}" for c in obs_data["chunks"]]
    assert "WHO-PA-SB-2020-VI-05#1" in returned_keys
    assert res.runStatus == "SUCCEEDED"
    assert res.resultType == "INFORMATION"
    assert [c.documentId for c in res.citations] == ["WHO-PA-SB-2020-VI-05"]


def test_search_knowledge_limit_edge_cases_and_no_padding(monkeypatch):
    from app.rag.pipeline import RetrievalPipeline
    from app.rag.reranker import DisabledReranker
    from app.rag.retriever import RetrievalResult

    monkeypatch.setattr(settings, "rag_rerank_enabled", False)
    monkeypatch.setattr(settings, "rag_mmr_enabled", False)
    monkeypatch.setattr(settings, "rag_search_min_top_k", 5)
    monkeypatch.setattr(settings, "rag_context_max_chars", 8000)

    def make_gateway(available_count: int):
        seen_limits: list[int] = []

        class StubRetriever:
            async def retrieve(self, query, allowed_source_ids, limit=5, run_id=None, correlation_id=None):
                seen_limits.append(limit)
                chunks = [
                    RetrievedChunkDto(
                        chunkId=f"c-{i}",
                        documentCode=f"WHO-PA-SB-2020-VI-0{i + 1}",
                        version="1.1.0",
                        sourceId="WHO-PA-SB-2020",
                        sourcePath=f"who-{i}.md",
                        heading=f"Heading {i}",
                        sectionIndex=i,
                        content=f"Verified chunk content {i} " * 4,
                        similarityScore=0.9 - i * 0.05,
                    )
                    for i in range(min(available_count, limit))
                ]
                return RetrievalResult(chunks=chunks, server_responded=True)

        return OfflineToolGateway(retrieval_pipeline=RetrievalPipeline(retriever=StubRetriever(), reranker=DisabledReranker())), seen_limits

    binding = _binding()

    # 1. Omitted limit -> defaults to 5, effectiveLimit = 5
    gw_omitted, limits_omitted = make_gateway(8)
    obs_omitted, _, _ = asyncio.run(gw_omitted.execute("search_knowledge", {"query": "WHO"}, binding))
    assert obs_omitted.status == "OK"
    assert limits_omitted == [5]
    assert obs_omitted.data["requestedLimit"] == 5
    assert obs_omitted.data["effectiveLimit"] == 5
    assert obs_omitted.data["returnedCount"] == 5

    # 2. Valid limit above floor (7) -> preserved as 7
    gw_seven, limits_seven = make_gateway(8)
    obs_seven, _, _ = asyncio.run(gw_seven.execute("search_knowledge", {"query": "WHO", "limit": 7}, binding))
    assert obs_seven.status == "OK"
    assert limits_seven == [7]
    assert obs_seven.data["requestedLimit"] == 7
    assert obs_seven.data["effectiveLimit"] == 7
    assert obs_seven.data["returnedCount"] == 7

    # 3. Over cap (>10) or <1 -> rejected by schema validation
    obs_over, _, _ = asyncio.run(gw_seven.execute("search_knowledge", {"query": "WHO", "limit": 11}, binding))
    assert obs_over.status == "ERROR"
    obs_zero, _, _ = asyncio.run(gw_seven.execute("search_knowledge", {"query": "WHO", "limit": 0}, binding))
    assert obs_zero.status == "ERROR"

    # 4. Scarce evidence (only 2 chunks) -> never padded to 5
    gw_scarce, _ = make_gateway(2)
    obs_scarce, _, _ = asyncio.run(gw_scarce.execute("search_knowledge", {"query": "WHO", "limit": 1}, binding))
    assert obs_scarce.status == "OK"
    assert obs_scarce.data["requestedLimit"] == 1
    assert obs_scarce.data["effectiveLimit"] == 5
    assert obs_scarce.data["returnedCount"] == 2

    # 5. Empty evidence (0 chunks) -> NOT_FOUND with metadata
    gw_empty, _ = make_gateway(0)
    obs_empty, _, _ = asyncio.run(gw_empty.execute("search_knowledge", {"query": "unknown", "limit": 1}, binding))
    assert obs_empty.status == "NOT_FOUND"
    assert obs_empty.data["requestedLimit"] == 1
    assert obs_empty.data["effectiveLimit"] == 5
    assert obs_empty.data["returnedCount"] == 0

    # 6. Small context budget -> pack_context_chunks truncates below effectiveLimit
    monkeypatch.setattr(settings, "rag_context_max_chars", 180)
    gw_small, _ = make_gateway(8)
    obs_small, _, _ = asyncio.run(gw_small.execute("search_knowledge", {"query": "WHO", "limit": 1}, binding))
    assert obs_small.status == "OK"
    assert obs_small.data["effectiveLimit"] == 5
    assert obs_small.data["returnedCount"] < 5


def test_tool_gateway_source_refs_match_runner_chunk_index_and_reject_invalid_keys(monkeypatch):
    """Model copies exact key from ToolObservation.sourceRefs produced by real ToolGateway."""
    from app.rag.pipeline import RetrievalPipeline
    from app.rag.reranker import DisabledReranker
    from app.rag.retriever import RetrievalResult

    monkeypatch.setattr(settings, "openai_api_key", SecretStr("test-key"))
    monkeypatch.setattr(settings, "rag_rerank_enabled", False)
    monkeypatch.setattr(settings, "rag_mmr_enabled", False)
    monkeypatch.setattr(settings, "rag_search_min_top_k", 5)

    class MultiChunkRetriever:
        async def retrieve(self, query, allowed_source_ids, limit=5, run_id=None, correlation_id=None):
            return RetrievalResult(
                chunks=[
                    RetrievedChunkDto(
                        chunkId="c-0",
                        documentCode="WHO-PA-SB-2020-VI-05",
                        version="1.1.0",
                        sourceId="WHO-PA-SB-2020",
                        sourcePath="derivatives/who-05.md",
                        heading="Intro",
                        sectionIndex=0,
                        content="Section 0 intro content for adults.",
                        similarityScore=0.88,
                    ),
                    RetrievedChunkDto(
                        chunkId="c-1",
                        documentCode="WHO-PA-SB-2020-VI-05",
                        version="1.1.0",
                        sourceId="WHO-PA-SB-2020",
                        sourcePath="derivatives/who-05.md",
                        heading="Recommendations",
                        sectionIndex=1,
                        content="Section 1: 150-300 minutes moderate aerobic activity per week.",
                        similarityScore=0.85,
                    ),
                    RetrievedChunkDto(
                        chunkId="c-old",
                        documentCode="WHO-PA-SB-2020-VI-05",
                        version="1.0.0",
                        sourceId="WHO-PA-SB-2020",
                        sourcePath="curated/who-05.md",
                        heading="Legacy",
                        sectionIndex=0,
                        content="Legacy v1.0.0 section 0 content.",
                        similarityScore=0.70,
                    ),
                ],
                server_responded=True,
            )

    def run_with_key_selector(select_keys):
        turn = {"n": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            turn["n"] += 1
            body = json.loads(request.content)
            if turn["n"] == 1:
                return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "tool_calls": [{
                    "id": "call-1", "type": "function",
                    "function": {"name": "search_knowledge", "arguments": json.dumps({"query": "WHO 2020", "limit": 5})},
                }]}}]})
            obs_msg = next(m["content"] for m in body["messages"] if m["content"].startswith("[UNTRUSTED_TOOL_OBSERVATIONS_START]"))
            obs = json.loads(obs_msg.split("\n", 1)[1].rsplit("\n", 1)[0])[0]
            keys = select_keys(obs["sourceRefs"])
            return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": json.dumps({
                "resultType": "INFORMATION",
                "answer": "Verified answer.",
                "clarificationQuestion": None,
                "citedChunkKeys": keys,
                "limitations": [],
                "missingData": [],
            })}}]})

        pipeline = RetrievalPipeline(retriever=MultiChunkRetriever(), reranker=DisabledReranker())
        runner = BoundedAgentRunner(
            model_adapter=OpenAiAgentModelAdapter(transport=httpx.MockTransport(handler)),
            tool_gateway=OfflineToolGateway(retrieval_pipeline=pipeline),
        )
        return asyncio.run(runner.run(AgentTurnRequest(binding=_binding(), message="WHO 2020 adults")))

    # 1. Copying advertised sourceRefs (sectionIndex=0, sectionIndex=1, and distinct version 1.0.0) succeeds
    ok_res = run_with_key_selector(lambda refs: [refs[0], refs[1], refs[2]])
    assert ok_res.runStatus == "SUCCEEDED"
    assert ok_res.resultType == "INFORMATION"
    assert [(c.version, c.locator.sectionIndex) for c in ok_res.citations] == [("1.1.0", 0), ("1.1.0", 1), ("1.0.0", 0)]

    # 2. Old '#s' format, wrong version, wrong section, or unretrieved key are all rejected
    for bad_keys in (
        ["WHO-PA-SB-2020-VI-05@1.1.0#s1"],
        ["WHO-PA-SB-2020-VI-05@9.9.9#1"],
        ["WHO-PA-SB-2020-VI-05@1.1.0#99"],
        ["NIDDK-WEIGHT-FACTORS-2023-VI-01@1.1.0#0"],
    ):
        bad_res = run_with_key_selector(lambda _refs, k=bad_keys: k)
        assert bad_res.runStatus == "REJECTED_BY_VALIDATOR"
        assert bad_res.resultType == "BLOCKED"
        assert bad_res.reasonCode == "UNVERIFIED_CITATION"
