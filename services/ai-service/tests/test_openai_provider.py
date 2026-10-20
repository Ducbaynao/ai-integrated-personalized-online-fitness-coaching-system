import asyncio
import json
from uuid import UUID

from fastapi.testclient import TestClient
import httpx
import pytest

from app.api.routes.internal_knowledge import get_f01_provider
from app.core.config import settings
from app.integrations.openai_provider import (
    DEFAULT_LIMITATIONS,
    OpenAiKnowledgeProvider,
    normalize_text_for_comparison,
)
from app.integrations.simulated_provider import SimulatedF01KnowledgeProvider
from app.main import app
from app.rag.retriever import KnowledgeRetriever, RetrievalResult, RetrievedChunkDto
from app.schemas.f01_schemas import (
    ActorContext,
    F01InternalContextEnvelope,
    KnowledgeScopeContext,
)

SAMPLE_ENVELOPE = F01InternalContextEnvelope(
    requestId=UUID("44444444-4444-4444-8444-444444444444"),
    requestType="F01_GENERAL_KNOWLEDGE",
    schemaVersion="1.0.0",
    correlationId=UUID("22222222-2222-4222-8222-222222222222"),
    actor=ActorContext(
        userId=UUID("11111111-1111-4111-8111-111111111111"),
        role="STUDENT",
    ),
    purpose="GENERAL_FITNESS_EXPLANATION",
    allowedActions=["READ_INFORMATION"],
    asOf="2026-10-21T10:00:00Z",
    question="Lượng leucine tối thiểu mỗi bữa ăn là bao nhiêu để tối ưu MPS?",
    language="vi",
    exerciseContext=None,
    knowledgeScope=KnowledgeScopeContext(
        status="ACTIVE",
        allowedSourceIds=["ISSN-PROTEIN-EXERCISE-2017"],
    ),
)

SAMPLE_CHUNK = RetrievedChunkDto(
    chunkId="chunk-uuid-1",
    documentCode="ISSN-PROTEIN-EXERCISE-2017-VI-04",
    version="1.0.0",
    sourceId="ISSN-PROTEIN-EXERCISE-2017",
    sourcePath="data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
    heading="Khuyến nghị Leucine",
    sectionIndex=0,
    snippet="Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa MPS.",
    content="Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa tổng hợp protein cơ bắp (MPS).",
    similarityScore=0.92,
)


class MockRetriever:
    def __init__(self, chunks=None, server_responded=True):
        self.chunks = chunks if chunks is not None else [SAMPLE_CHUNK]
        self.server_responded = server_responded

    async def retrieve(self, query, allowed_source_ids, limit=5, run_id=None, correlation_id=None):
        return RetrievalResult(chunks=self.chunks, server_responded=self.server_responded)


def make_openai_response(
    content_dict: dict,
    finish_reason: str = "stop",
    prompt_tokens: int = 120,
    completion_tokens: int = 45,
) -> dict:
    return {
        "id": "chatcmpl-test-123",
        "object": "chat.completion",
        "created": 1729500000,
        "model": "gpt-4o-mini-2024-07-18",
        "choices": [
            {
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": json.dumps(content_dict),
                },
                "finish_reason": finish_reason,
            }
        ],
        "usage": {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens,
        },
    }


# ==============================================================================
# 1. Routing & Provider Factory Tests
# ==============================================================================


def test_get_f01_provider_routing(monkeypatch):
    monkeypatch.setattr(settings, "f01_provider", "simulated")
    provider = get_f01_provider()
    assert isinstance(provider, SimulatedF01KnowledgeProvider)

    monkeypatch.setattr(settings, "f01_provider", "openai")
    provider = get_f01_provider()
    assert isinstance(provider, OpenAiKnowledgeProvider)


# ==============================================================================
# 2. Scope & Retrieval Precondition Tests
# ==============================================================================


@pytest.mark.anyio
async def test_openai_empty_allowed_sources():
    provider = OpenAiKnowledgeProvider(retriever=MockRetriever())
    envelope = SAMPLE_ENVELOPE.model_copy(
        update={"knowledgeScope": KnowledgeScopeContext(status="ACTIVE", allowedSourceIds=[])}
    )
    result = await provider.query(envelope)
    assert result.runStatus == "SUCCEEDED"
    assert result.resultType == "INSUFFICIENT_DATA"
    assert result.reasonCode == "NO_RELEVANT_EVIDENCE"
    assert len(result.missingData) > 0


@pytest.mark.anyio
async def test_openai_retrieval_unreachable():
    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(chunks=[], server_responded=False))
    result = await provider.query(SAMPLE_ENVELOPE)
    assert result.runStatus == "FAILED"
    assert result.reasonCode == "MODEL_ERROR"
    assert result.error.code == "UPSTREAM_RETRIEVAL_FAILED"


@pytest.mark.anyio
async def test_openai_retrieval_no_chunks_found():
    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(chunks=[], server_responded=True))
    result = await provider.query(SAMPLE_ENVELOPE)
    assert result.runStatus == "SUCCEEDED"
    assert result.resultType == "INSUFFICIENT_DATA"
    assert result.reasonCode == "NO_RELEVANT_EVIDENCE"
    assert len(result.missingData) > 0


@pytest.mark.anyio
async def test_openai_missing_api_key(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "")
    provider = OpenAiKnowledgeProvider(retriever=MockRetriever())
    result = await provider.query(SAMPLE_ENVELOPE)
    assert result.runStatus == "FAILED"
    assert result.reasonCode == "PROVIDER_ERROR"
    assert result.error.code == "PROVIDER_CONFIGURATION_ERROR"


# ==============================================================================
# 3. Successful Generation Tests (DEC-A13-02 Contract Compliance)
# ==============================================================================


@pytest.mark.anyio
async def test_openai_successful_information_generation(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    captured_requests = []

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        captured_requests.append(request)
        req_body = json.loads(request.content.decode("utf-8"))

        # Verify DEC-A13-02 mandatory constraints on the request
        assert req_body["model"] == "gpt-4o-mini-2024-07-18"
        assert req_body["store"] is False
        assert req_body["response_format"]["type"] == "json_schema"
        assert req_body["response_format"]["json_schema"]["strict"] is True

        content = {
            "status": "ANSWERED",
            "answer": "Để tối ưu tổng hợp protein cơ bắp (MPS), khuyến nghị lượng leucine là 2.0 - 3.0g trong mỗi bữa ăn.",
            "citations": [
                {
                    "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
                    "version": "1.0.0",
                    "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                    "locator": {
                        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                        "heading": "Khuyến nghị Leucine",
                        "sectionIndex": 0,
                    },
                    "quoteOrExcerpt": "Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa MPS.",
                }
            ],
            "limitations": ["Áp dụng cho người trưởng thành khỏe mạnh."],
            "missingData": [],
            "refusalReason": "",
        }
        return httpx.Response(200, json=make_openai_response(content, prompt_tokens=210, completion_tokens=65))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runId == SAMPLE_ENVELOPE.requestId
    assert result.runStatus == "SUCCEEDED"
    assert result.resultType == "INFORMATION"
    assert result.reasonCode == "NONE"
    assert "2.0 - 3.0g" in result.answer
    assert len(result.citations) == 1
    assert result.citations[0].documentId == "ISSN-PROTEIN-EXERCISE-2017-VI-04"
    assert result.usage.promptTokens == 210
    assert result.usage.completionTokens == 65
    assert result.generationMetadata.provider == "openai"
    assert result.generationMetadata.model == "gpt-4o-mini-2024-07-18"

    assert len(captured_requests) == 1
    assert captured_requests[0].headers["Authorization"] == "Bearer sk-mock-test-key"


# ==============================================================================
# 4. Grounding & Citation Invariant Tests (Anti-Hallucination)
# ==============================================================================


@pytest.mark.anyio
async def test_openai_rejects_hallucinated_document_citation(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        content = {
            "status": "ANSWERED",
            "answer": "Câu trả lời bịa đặt tài liệu.",
            "citations": [
                {
                    "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-99",  # Document 99 NOT in evidence!
                    "version": "1.0.0",
                    "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                    "locator": {
                        "sourcePath": "data/knowledge/curated/issn-protein-2017/99-fake.vi.md",
                        "heading": "Fake",
                        "sectionIndex": 0,
                    },
                    "quoteOrExcerpt": "Fake quote",
                }
            ],
            "limitations": [],
            "missingData": [],
            "refusalReason": "",
        }
        return httpx.Response(200, json=make_openai_response(content))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "REJECTED_BY_VALIDATOR"
    assert result.reasonCode == "VALIDATION_FAILED"
    assert result.error.code == "UNVERIFIED_CITATION"
    assert "not present in retrieved evidence chunks" in result.error.message


@pytest.mark.anyio
async def test_openai_rejects_hallucinated_quote(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        content = {
            "status": "ANSWERED",
            "answer": "Câu trả lời có trích dẫn bịa đặt nội dung.",
            "citations": [
                {
                    "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
                    "version": "1.0.0",
                    "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                    "locator": {
                        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                        "heading": "Khuyến nghị Leucine",
                        "sectionIndex": 0,
                    },
                    "quoteOrExcerpt": "Nội dung trích dẫn này hoàn toàn không có trong đoạn trích thật của ISSN.",
                }
            ],
            "limitations": [],
            "missingData": [],
            "refusalReason": "",
        }
        return httpx.Response(200, json=make_openai_response(content))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "REJECTED_BY_VALIDATOR"
    assert result.reasonCode == "VALIDATION_FAILED"
    assert result.error.code == "UNVERIFIED_CITATION"
    assert "quoteOrExcerpt is not supported" in result.error.message


@pytest.mark.anyio
async def test_openai_rejects_answered_with_empty_citations(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        content = {
            "status": "ANSWERED",
            "answer": "Câu trả lời không có nguồn trích dẫn.",
            "citations": [],  # Violates F01 INFORMATION contract requirement
            "limitations": [],
            "missingData": [],
            "refusalReason": "",
        }
        return httpx.Response(200, json=make_openai_response(content))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "REJECTED_BY_VALIDATOR"
    assert result.reasonCode == "VALIDATION_FAILED"
    assert result.error.code == "UNVERIFIED_CITATION"


# ==============================================================================
# 5. Refusal, Insufficient Evidence & Finish Reason Tests
# ==============================================================================


@pytest.mark.anyio
async def test_openai_model_refusal_status(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        content = {
            "status": "REFUSED",
            "answer": "",
            "citations": [],
            "limitations": ["Chính sách an toàn từ chối câu hỏi doping."],
            "missingData": [],
            "refusalReason": "Câu hỏi yêu cầu hướng dẫn sử dụng steroid anabolic cấm bởi WADA.",
        }
        return httpx.Response(200, json=make_openai_response(content))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "REJECTED_BY_VALIDATOR"
    assert result.resultType == "BLOCKED"
    assert result.reasonCode == "RULE_BLOCKED"
    assert result.error.code == "POLICY_GUARDRAIL_TRIGGERED"
    assert "WADA" in result.error.message


@pytest.mark.anyio
async def test_openai_model_insufficient_evidence_status(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        content = {
            "status": "INSUFFICIENT_EVIDENCE",
            "answer": "",
            "citations": [],
            "limitations": ["Chỉ áp dụng nguồn ISSN."],
            "missingData": ["Thiếu thông tin về liều lượng đối với phụ nữ có thai."],
            "refusalReason": "",
        }
        return httpx.Response(200, json=make_openai_response(content))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "SUCCEEDED"
    assert result.resultType == "INSUFFICIENT_DATA"
    assert result.reasonCode == "NO_RELEVANT_EVIDENCE"
    assert len(result.missingData) == 1
    assert "phụ nữ có thai" in result.missingData[0]


@pytest.mark.anyio
async def test_openai_finish_reason_content_filter(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        content = {"status": "REFUSED", "answer": "", "citations": [], "limitations": [], "missingData": [], "refusalReason": ""}
        return httpx.Response(200, json=make_openai_response(content, finish_reason="content_filter"))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "REJECTED_BY_VALIDATOR"
    assert result.resultType == "BLOCKED"
    assert result.reasonCode == "RULE_BLOCKED"
    assert result.error.code == "PROVIDER_REFUSAL"


@pytest.mark.anyio
async def test_openai_finish_reason_length_incomplete(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        content = {"status": "ANSWERED", "answer": "Cắt ngang...", "citations": [], "limitations": [], "missingData": [], "refusalReason": ""}
        return httpx.Response(200, json=make_openai_response(content, finish_reason="length"))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "FAILED"
    assert result.reasonCode == "PROCESSING_ERROR"
    assert result.error.code == "INCOMPLETE_OUTPUT"


# ==============================================================================
# 6. Rate Limit (429), Server Error (5xx) & Timeout Tests
# ==============================================================================


@pytest.mark.anyio
async def test_openai_429_quota_exceeded_non_retryable(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    call_count = 0

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(
            429,
            json={
                "error": {
                    "message": "You exceeded your current quota.",
                    "type": "insufficient_quota",
                    "code": "insufficient_quota",
                }
            },
        )

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert call_count == 1  # Must NOT retry quota exhaustion!
    assert result.runStatus == "FAILED"
    assert result.reasonCode == "PROVIDER_ERROR"
    assert result.error.code == "QUOTA_EXCEEDED"


@pytest.mark.anyio
async def test_openai_429_rate_limit_retries_and_succeeds(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    call_count = 0

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            return httpx.Response(
                429,
                headers={"Retry-After": "0.01"},
                json={"error": {"message": "Rate limit exceeded", "type": "tokens"}},
            )
        content = {
            "status": "ANSWERED",
            "answer": "Trả lời thành công sau retry.",
            "citations": [
                {
                    "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
                    "version": "1.0.0",
                    "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                    "locator": {
                        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                        "heading": "Khuyến nghị Leucine",
                        "sectionIndex": 0,
                    },
                    "quoteOrExcerpt": "Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa MPS.",
                }
            ],
            "limitations": [],
            "missingData": [],
            "refusalReason": "",
        }
        return httpx.Response(200, json=make_openai_response(content))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert call_count == 2
    assert result.runStatus == "SUCCEEDED"
    assert result.resultType == "INFORMATION"


@pytest.mark.anyio
async def test_openai_500_server_error_retries_and_fails(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    call_count = 0

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(500, text="Internal Server Error")

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert call_count == 2  # 1 initial + 1 retry
    assert result.runStatus == "FAILED"
    assert result.reasonCode == "PROVIDER_ERROR"
    assert result.error.code == "PROVIDER_ERROR"


@pytest.mark.anyio
async def test_openai_overall_timeout_handling(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")
    monkeypatch.setattr(settings, "openai_overall_timeout_seconds", 0.05)

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.2)
        return httpx.Response(200, json=make_openai_response({}))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "FAILED"
    assert result.reasonCode == "TIMEOUT"
    assert result.error.code == "TIMEOUT"
    assert "overall deadline" in result.error.message


# ==============================================================================
# 7. End-to-End Route Test via TestClient
# ==============================================================================


def test_fastapi_route_with_openai_provider(monkeypatch):
    monkeypatch.setattr(settings, "f01_provider", "openai")
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_retrieve(self, query, allowed_source_ids, limit=5, run_id=None, correlation_id=None):
        return RetrievalResult(chunks=[SAMPLE_CHUNK], server_responded=True)

    monkeypatch.setattr(KnowledgeRetriever, "retrieve", mock_retrieve)

    # Mock the internal OpenAI call within OpenAiKnowledgeProvider
    async def mock_call(self, payload, api_key, start_mono, run_id, correlation_id=None):
        content = {
            "status": "ANSWERED",
            "answer": "Giải thích chi tiết về leucine theo ISSN.",
            "citations": [
                {
                    "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
                    "version": "1.0.0",
                    "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                    "locator": {
                        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                        "heading": "Khuyến nghị Leucine",
                        "sectionIndex": 0,
                    },
                    "quoteOrExcerpt": "Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa MPS.",
                }
            ],
            "limitations": ["Người trưởng thành"],
            "missingData": [],
            "refusalReason": "",
        }
        return make_openai_response(content, prompt_tokens=150, completion_tokens=50), 45

    monkeypatch.setattr(OpenAiKnowledgeProvider, "_call_openai_with_retry", mock_call)

    client = TestClient(app)
    response = client.post(
        "/internal/v1/knowledge/query",
        headers={"X-Internal-Service-Key": settings.internal_service_key},
        json=SAMPLE_ENVELOPE.model_dump(mode="json"),
    )
    assert response.status_code == 200
    data = response.json()
    assert data["runStatus"] == "SUCCEEDED"
    assert data["resultType"] == "INFORMATION"
    assert data["generationMetadata"]["provider"] == "openai"
    assert data["generationMetadata"]["model"] == "gpt-4o-mini-2024-07-18"
    assert len(data["citations"]) == 1
    assert data["citations"][0]["documentId"] == "ISSN-PROTEIN-EXERCISE-2017-VI-04"


# ==============================================================================
# 8. Additional Edge Cases & Resilience Tests
# ==============================================================================


@pytest.mark.anyio
async def test_openai_malformed_json_response(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        raw_res = {
            "choices": [{"message": {"role": "assistant", "content": "not-valid-json {"}, "finish_reason": "stop"}]
        }
        return httpx.Response(200, json=raw_res)

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "REJECTED_BY_VALIDATOR"
    assert result.reasonCode == "VALIDATION_FAILED"
    assert result.error.code == "VALIDATION_FAILED"


@pytest.mark.anyio
async def test_openai_empty_choices_response(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"choices": []})

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "FAILED"
    assert result.reasonCode == "MODEL_ERROR"
    assert result.error.code == "EMPTY_CHOICES"


@pytest.mark.anyio
async def test_openai_unexpected_status_value(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        content = {"status": "UNKNOWN_CUSTOM_STATUS", "answer": "text", "citations": [], "limitations": [], "missingData": [], "refusalReason": ""}
        return httpx.Response(200, json=make_openai_response(content))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "REJECTED_BY_VALIDATOR"
    assert result.reasonCode == "VALIDATION_FAILED"
    assert result.error.code == "VALIDATION_FAILED"


@pytest.mark.anyio
async def test_openai_with_exercise_context(monkeypatch):
    from app.schemas.f01_schemas import ExerciseContext
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    captured_prompt = []

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        req_body = json.loads(request.content.decode("utf-8"))
        for msg in req_body["messages"]:
            if msg["role"] == "user":
                captured_prompt.append(msg["content"])
        content = {
            "status": "ANSWERED",
            "answer": "Giải thích kết hợp bài tập Barbell Squat.",
            "citations": [
                {
                    "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
                    "version": "1.0.0",
                    "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                    "locator": {
                        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                        "heading": "Khuyến nghị Leucine",
                        "sectionIndex": 0,
                    },
                    "quoteOrExcerpt": "Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa MPS.",
                }
            ],
            "limitations": [],
            "missingData": [],
            "refusalReason": "",
        }
        return httpx.Response(200, json=make_openai_response(content))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    envelope_with_exercise = SAMPLE_ENVELOPE.model_copy(
        update={
            "exerciseContext": ExerciseContext(
                exerciseId=UUID("99999999-9999-4999-8999-999999999999"),
                exerciseName="Barbell Squat",
                movementPattern="SQUAT",
                equipmentRequired="BARBELL",
            )
        }
    )
    result = await provider.query(envelope_with_exercise)

    assert result.runStatus == "SUCCEEDED"
    assert len(captured_prompt) == 1
    assert "Barbell Squat" in captured_prompt[0]
    assert "SQUAT" in captured_prompt[0]


def test_get_f01_provider_invalid_raises_500(monkeypatch):
    from fastapi import HTTPException
    monkeypatch.setattr(settings, "f01_provider", "invalid_provider_name")
    with pytest.raises(HTTPException) as exc_info:
        get_f01_provider()
    assert exc_info.value.status_code == 500
    assert "Unsupported F01 knowledge provider" in exc_info.value.detail


@pytest.mark.anyio
async def test_openai_duplicate_citations_deduplicated(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        content = {
            "status": "ANSWERED",
            "answer": "Giải thích protein với trích dẫn lặp.",
            "citations": [
                {
                    "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
                    "version": "1.0.0",
                    "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                    "locator": {
                        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                        "heading": "Khuyến nghị Leucine",
                        "sectionIndex": 0,
                    },
                    "quoteOrExcerpt": "Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa MPS.",
                },
                {
                    "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
                    "version": "1.0.0",
                    "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                    "locator": {
                        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                        "heading": "Khuyến nghị Leucine",
                        "sectionIndex": 0,
                    },
                    "quoteOrExcerpt": "Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa MPS.",
                },
            ],
            "limitations": [],
            "missingData": [],
            "refusalReason": "",
        }
        return httpx.Response(200, json=make_openai_response(content))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "SUCCEEDED"
    assert len(result.citations) == 1  # Successfully deduplicated!


def test_openai_api_key_secret_str_masking():
    from pydantic import SecretStr
    secret = SecretStr("sk-super-secret-key-12345")
    assert "sk-super-secret-key-12345" not in repr(secret)
    assert "sk-super-secret-key-12345" not in str(secret)
    assert secret.get_secret_value() == "sk-super-secret-key-12345"


@pytest.mark.anyio
async def test_openai_invalid_retry_after_handled_gracefully(monkeypatch):
    monkeypatch.setattr(settings, "openai_api_key", "sk-mock-test-key")

    call_count = 0

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            return httpx.Response(
                429,
                headers={"Retry-After": "-10"},  # Negative / invalid Retry-After header
                json={"error": {"message": "Rate limit", "type": "tokens"}},
            )
        content = {
            "status": "ANSWERED",
            "answer": "Thành công sau retry với retry-after âm.",
            "citations": [
                {
                    "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
                    "version": "1.0.0",
                    "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                    "locator": {
                        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                        "heading": "Khuyến nghị Leucine",
                        "sectionIndex": 0,
                    },
                    "quoteOrExcerpt": "Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa MPS.",
                }
            ],
            "limitations": [],
            "missingData": [],
            "refusalReason": "",
        }
        return httpx.Response(200, json=make_openai_response(content))

    transport = httpx.MockTransport(mock_handler)
    mock_client = httpx.AsyncClient(transport=transport)

    provider = OpenAiKnowledgeProvider(retriever=MockRetriever(), http_client=mock_client)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert call_count == 2
    assert result.runStatus == "SUCCEEDED"


