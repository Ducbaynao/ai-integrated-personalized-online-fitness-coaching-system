import os

import httpx
import pytest
from pydantic import SecretStr

from app.core.config import settings

# Protect collection, import, and test execution phases from live external calls
os.environ["AI_TEST_MODE"] = "true"


@pytest.fixture(autouse=True)
def isolate_ai_test_environment(monkeypatch):
    """
    Guarantees that default test executions use simulated providers,
    do not inherit live API credentials from .env, and block any live
    external calls unless an explicit opt-in (RUN_LIVE_OPENAI_TESTS=true) is configured.
    """
    orig_provider = settings.f01_provider
    orig_key = settings.openai_api_key
    orig_mock = settings.f01_mock_enabled

    # Default to simulated provider and clear live keys
    monkeypatch.setattr(settings, "f01_provider", "simulated")
    monkeypatch.setattr(settings, "f01_mock_enabled", True)
    monkeypatch.setattr(settings, "openai_api_key", SecretStr(""))

    # Ensure live tests are disabled by default
    if os.getenv("RUN_LIVE_OPENAI_TESTS", "").lower() != "true":
        monkeypatch.setenv("RUN_LIVE_OPENAI_TESTS", "false")

    # Install transport-level guard on AsyncHTTPTransport (live network client)
    orig_handle = httpx.AsyncHTTPTransport.handle_async_request

    async def guarded_handle_async_request(self, request: httpx.Request) -> httpx.Response:
        url_str = str(request.url).lower()
        is_openai_target = "openai" in url_str or (
            settings.openai_api_url and settings.openai_api_url.lower() in url_str
        )
        if is_openai_target and os.getenv("RUN_LIVE_OPENAI_TESTS", "").lower() != "true":
            raise RuntimeError(
                f"Blocked live outbound HTTP request to {request.url} in test environment. "
                "Set RUN_LIVE_OPENAI_TESTS=true to opt in."
            )
        return await orig_handle(self, request)

    monkeypatch.setattr(httpx.AsyncHTTPTransport, "handle_async_request", guarded_handle_async_request)

    yield

    # Restore settings
    settings.f01_provider = orig_provider
    settings.openai_api_key = orig_key
    settings.f01_mock_enabled = orig_mock
