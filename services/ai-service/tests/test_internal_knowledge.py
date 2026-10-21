from fastapi.testclient import TestClient

from app.core.config import settings
from app.main import app

client = TestClient(app)

VALID_ENVELOPE = {
    "requestId": "44444444-4444-4444-8444-444444444444",
    "requestType": "F01_GENERAL_KNOWLEDGE",
    "schemaVersion": "1.0.0",
    "correlationId": "22222222-2222-4222-8222-222222222222",
    "actor": {
        "userId": "11111111-1111-4111-8111-111111111111",
        "role": "STUDENT"
    },
    "purpose": "GENERAL_FITNESS_EXPLANATION",
    "allowedActions": ["READ_INFORMATION"],
    "asOf": "2026-10-10T12:00:00Z",
    "question": "Làm thế nào để phân bổ protein tối ưu cho phát triển cơ bắp?",
    "language": "vi",
    "knowledgeScope": {
        "status": "ACTIVE",
        "allowedSourceIds": ["ISSN-PROTEIN-EXERCISE-2017"]
    }
}

VALID_AUTH_HEADER = {"X-Internal-Service-Key": settings.internal_service_key}


def test_auth_missing_header():
    response = client.post("/internal/v1/knowledge/query", json=VALID_ENVELOPE)
    assert response.status_code == 401
    assert "Missing" in response.json()["detail"]


def test_auth_invalid_key():
    response = client.post(
        "/internal/v1/knowledge/query",
        headers={"X-Internal-Service-Key": "wrong-secret-key"},
        json=VALID_ENVELOPE,
    )
    assert response.status_code == 403
    assert "Invalid" in response.json()["detail"]


def test_query_knowledge_success_information(monkeypatch):
    from app.rag.retriever import KnowledgeRetriever, RetrievalResult, RetrievedChunkDto

    async def mock_retrieve(self, query, allowed_source_ids, limit=5, run_id=None, correlation_id=None):
        return RetrievalResult(
            chunks=[
                RetrievedChunkDto(
                    chunkId="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    documentCode="ISSN-PROTEIN-EXERCISE-2017-VI-04",
                    version="1.0.0",
                    sourceId="ISSN-PROTEIN-EXERCISE-2017",
                    sourcePath="data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                    heading="Protein, leucine và EAA trong từng lần ăn",
                    sectionIndex=0,
                    snippet="Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa MPS.",
                    similarityScore=0.92,
                )
            ],
            server_responded=True,
        )

    monkeypatch.setattr(KnowledgeRetriever, "retrieve", mock_retrieve)

    response = client.post(
        "/internal/v1/knowledge/query",
        headers=VALID_AUTH_HEADER,
        json=VALID_ENVELOPE,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["runId"] == VALID_ENVELOPE["requestId"]
    assert data["correlationId"] == VALID_ENVELOPE["correlationId"]
    assert data["runStatus"] == "SUCCEEDED"
    assert data["resultType"] == "INFORMATION"
    assert data["reasonCode"] == "NONE"
    assert len(data["answer"]) > 0
    assert len(data["citations"]) == 1
    assert data["citations"][0]["documentId"] == "ISSN-PROTEIN-EXERCISE-2017-VI-04"
    assert data["usage"]["promptTokens"] > 0
    assert data["generationMetadata"]["provider"] == "simulated"


def test_query_knowledge_insufficient_data():
    envelope = dict(VALID_ENVELOPE)
    envelope["knowledgeScope"] = {
        "status": "ACTIVE",
        "allowedSourceIds": []
    }
    response = client.post(
        "/internal/v1/knowledge/query",
        headers=VALID_AUTH_HEADER,
        json=envelope,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["runStatus"] == "SUCCEEDED"
    assert data["resultType"] == "INSUFFICIENT_DATA"
    assert data["reasonCode"] == "NO_RELEVANT_EVIDENCE"
    assert len(data["missingData"]) >= 1
    assert data.get("answer") is None
    assert data.get("citations") is None


def test_query_knowledge_blocked():
    envelope = dict(VALID_ENVELOPE)
    envelope["question"] = "Tôi muốn mua steroid tăng cơ nhanh thì dùng loại nào?"
    response = client.post(
        "/internal/v1/knowledge/query",
        headers=VALID_AUTH_HEADER,
        json=envelope,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["runStatus"] == "REJECTED_BY_VALIDATOR"
    assert data["resultType"] == "BLOCKED"
    assert data["reasonCode"] == "RULE_BLOCKED"
    assert data["error"]["code"] == "POLICY_GUARDRAIL_TRIGGERED"
    assert data.get("answer") is None


def test_query_knowledge_trigger_timeout():
    envelope = dict(VALID_ENVELOPE)
    envelope["question"] = "trigger_timeout simulation test"
    response = client.post(
        "/internal/v1/knowledge/query",
        headers=VALID_AUTH_HEADER,
        json=envelope,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["runStatus"] == "FAILED"
    assert data["reasonCode"] == "TIMEOUT"
    assert data["error"]["code"] == "TIMEOUT"


def test_query_knowledge_trigger_provider_error():
    envelope = dict(VALID_ENVELOPE)
    envelope["question"] = "trigger_provider_error simulation test"
    response = client.post(
        "/internal/v1/knowledge/query",
        headers=VALID_AUTH_HEADER,
        json=envelope,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["runStatus"] == "FAILED"
    assert data["reasonCode"] == "MODEL_ERROR"
    assert data["error"]["code"] == "MODEL_ERROR"


def test_query_knowledge_invalid_envelope_constraints():
    # 1. Invalid purpose
    bad_envelope = dict(VALID_ENVELOPE)
    bad_envelope["purpose"] = "UNAUTHORIZED_ACTION"
    res = client.post("/internal/v1/knowledge/query", headers=VALID_AUTH_HEADER, json=bad_envelope)
    assert res.status_code == 422

    # 2. Invalid knowledgeScope status (must be ACTIVE)
    bad_envelope2 = dict(VALID_ENVELOPE)
    bad_envelope2["knowledgeScope"] = {"status": "DRAFT", "allowedSourceIds": ["ISSN"]}
    res2 = client.post("/internal/v1/knowledge/query", headers=VALID_AUTH_HEADER, json=bad_envelope2)
    assert res2.status_code == 422

    # 3. Invalid role (ADMIN not allowed in actor for F01)
    bad_envelope3 = dict(VALID_ENVELOPE)
    bad_envelope3["actor"] = {"userId": "11111111-1111-4111-8111-111111111111", "role": "ADMIN"}
    res3 = client.post("/internal/v1/knowledge/query", headers=VALID_AUTH_HEADER, json=bad_envelope3)
    assert res3.status_code == 422


def test_disabled_mock_provider(monkeypatch):
    monkeypatch.setattr(settings, "f01_mock_enabled", False)
    response = client.post(
        "/internal/v1/knowledge/query",
        headers=VALID_AUTH_HEADER,
        json=VALID_ENVELOPE,
    )
    assert response.status_code == 503
    assert "disabled" in response.json()["detail"]


def test_retrieval_enabled_backend_unreachable_fails_without_fixture(monkeypatch):
    monkeypatch.setattr(settings, "backend_internal_retrieval_enabled", True)
    monkeypatch.setattr(settings, "backend_base_url", "http://127.0.0.1:59999")
    # Backend is unreachable during isolated unit test
    response = client.post(
        "/internal/v1/knowledge/query",
        headers=VALID_AUTH_HEADER,
        json=VALID_ENVELOPE,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["runStatus"] == "FAILED"
    assert data["error"]["code"] == "UPSTREAM_RETRIEVAL_FAILED"
    assert "citations" not in data or data["citations"] is None

