from fastapi.testclient import TestClient

from app.core.config import settings
from app.main import app


client = TestClient(app)
PATH = "/internal/v1/food-text-estimates/generate"


def test_internal_f06_requires_spring_service_key():
    assert client.post(PATH, json={"text": "hai quả trứng"}).status_code == 401
    assert client.post(
        PATH, json={"text": "hai quả trứng"}, headers={"X-Internal-Service-Key": "wrong"}
    ).status_code == 403


def test_simulated_f06_keeps_identity_and_quantity_unknown():
    response = client.post(
        PATH,
        json={"text": "hai quả trứng"},
        headers={"X-Internal-Service-Key": settings.internal_service_key},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["resultType"] == "ESTIMATE"
    assert len(body["items"]) == 1
    assert body["items"][0]["detectedName"] == "hai quả trứng"
    assert body["items"][0]["quantity"] is None
    assert body["items"][0]["candidateNames"] == []
    assert body["missingData"] == ["FOOD_IDENTITY", "QUANTITY", "UNIT"]
    assert "energyKcal" not in body


def test_internal_f06_rejects_blank_and_extra_fields():
    headers = {"X-Internal-Service-Key": settings.internal_service_key}
    assert client.post(PATH, json={"text": "   "}, headers=headers).status_code == 422
    assert client.post(
        PATH, json={"text": "cơm", "studentId": "not-authorized-here"}, headers=headers
    ).status_code == 422
