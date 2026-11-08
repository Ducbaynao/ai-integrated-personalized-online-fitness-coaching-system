from uuid import UUID

import pytest
from pydantic import SecretStr

from app.api.routes.internal_knowledge import get_f01_provider
from app.core.config import settings
from app.integrations.openai_provider import OpenAiKnowledgeProvider
from app.integrations.simulated_provider import SimulatedF01KnowledgeProvider
from app.rag.retriever import RetrievalResult, RetrievedChunkDto
from app.schemas.f01_schemas import (
    ActorContext,
    F01InternalContextEnvelope,
    KnowledgeScopeContext,
)

SAMPLE_ENVELOPE = F01InternalContextEnvelope(
    requestId=UUID("12345678-1234-5678-1234-567812345678"),
    requestType="F01_GENERAL_KNOWLEDGE",
    schemaVersion="1.0.0",
    correlationId=UUID("87654321-4321-8765-4321-876543210987"),
    actor=ActorContext(
        userId=UUID("11111111-2222-3333-4444-555555555555"),
        role="STUDENT",
    ),
    purpose="GENERAL_FITNESS_EXPLANATION",
    allowedActions=["READ_INFORMATION"],
    asOf="2026-11-08T10:00:00Z",
    question="Lợi ích của việc khởi động trước khi tập kháng lực?",
    language="vi",
    exerciseContext=None,
    knowledgeScope=KnowledgeScopeContext(
        status="ACTIVE",
        allowedSourceIds=["WHO-PA-SB-2020"],
    ),
)

SAMPLE_CHUNK = RetrievedChunkDto(
    chunkId="chunk-mock-1",
    documentCode="WHO-PA-SB-2020-VI-01",
    version="1.0.0",
    sourceId="WHO-PA-SB-2020",
    sourcePath="derivatives/who-2020/01-scope.vi.md",
    heading="Khởi động",
    sectionIndex=0,
    snippet="Khởi động giúp làm ấm cơ bắp và giảm chấn thương.",
    content="Khởi động giúp làm ấm cơ bắp và giảm chấn thương trong tập luyện.",
    similarityScore=0.95,
)


class DummyRetriever:
    async def retrieve(self, query, allowed_source_ids, limit=5, run_id=None, correlation_id=None):
        return RetrievalResult(chunks=[SAMPLE_CHUNK], server_responded=True)


def test_default_test_suite_uses_simulated_provider():
    """Verify that by default, the test suite uses SimulatedF01KnowledgeProvider."""
    provider = get_f01_provider()
    assert isinstance(provider, SimulatedF01KnowledgeProvider)
    assert settings.f01_provider == "simulated"
    assert settings.openai_api_key.get_secret_value() == ""


@pytest.mark.anyio
async def test_live_openai_call_blocked_when_unmocked_and_no_opt_in(monkeypatch):
    """
    Verify that if an unmocked OpenAiKnowledgeProvider is instantiated with fake credentials,
    it is strictly blocked from making live external HTTP calls unless RUN_LIVE_OPENAI_TESTS=true.
    """
    monkeypatch.setattr(settings, "f01_provider", "openai")
    monkeypatch.setattr(settings, "openai_api_key", SecretStr("sk-fake-test-credential-never-live"))
    monkeypatch.setenv("RUN_LIVE_OPENAI_TESTS", "false")

    # Instantiate provider without injected mock HTTP client
    provider = OpenAiKnowledgeProvider(retriever=DummyRetriever(), http_client=None)

    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "FAILED"
    assert result.reasonCode == "PROVIDER_ERROR"
    assert result.error is not None
    assert result.error.code == "LIVE_CALL_BLOCKED"
    assert "Live external OpenAI calls are blocked" in result.error.message


@pytest.mark.anyio
async def test_runtime_mode_does_not_require_test_opt_in_flag(monkeypatch):
    """
    Verify that outside of the test environment (normal production runtime),
    the provider does not require RUN_LIVE_OPENAI_TESTS=true to initialize its client.
    A mock transport is used to guarantee no live network calls occur.
    """
    monkeypatch.setattr(settings, "f01_provider", "openai")
    monkeypatch.setattr(settings, "openai_api_key", SecretStr("sk-fake-runtime-key"))

    # Simulate non-test runtime environment
    monkeypatch.delenv("PYTEST_CURRENT_TEST", raising=False)
    monkeypatch.delenv("TESTING", raising=False)
    monkeypatch.delenv("RUN_LIVE_OPENAI_TESTS", raising=False)

    import json

    import httpx

    captured_requests = []

    def mock_transport_handler(request: httpx.Request) -> httpx.Response:
        captured_requests.append(request)
        return httpx.Response(
            status_code=200,
            json={
                "id": "chatcmpl-mock",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps({
                                "status": "ANSWERED",
                                "answer": "Khởi động giúp làm ấm cơ bắp và giảm chấn thương trong tập luyện.",
                                "citations": [
                                    {
                                        "documentId": "WHO-PA-SB-2020-VI-01",
                                        "version": "1.0.0",
                                        "sourceId": "WHO-PA-SB-2020",
                                        "locator": {
                                            "sourcePath": "derivatives/who-2020/01-scope.vi.md",
                                            "heading": "Khởi động",
                                            "sectionIndex": 0,
                                        },
                                        "quoteOrExcerpt": "Khởi động giúp làm ấm cơ bắp và giảm chấn thương trong tập luyện.",
                                    }
                                ],
                                "limitations": [],
                                "missingData": [],
                                "refusalReason": "",
                            }),
                        },
                        "finish_reason": "stop",
                    }
                ],
                "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30},
            },
        )

    # In runtime, httpx.AsyncClient is created with the mocked transport to verify flow without live calls
    mock_client = httpx.AsyncClient(transport=httpx.MockTransport(mock_transport_handler))
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: mock_client)

    provider = OpenAiKnowledgeProvider(retriever=DummyRetriever(), http_client=None)
    result = await provider.query(SAMPLE_ENVELOPE)

    assert result.runStatus == "SUCCEEDED"
    assert result.resultType == "INFORMATION"
    assert len(captured_requests) == 1

