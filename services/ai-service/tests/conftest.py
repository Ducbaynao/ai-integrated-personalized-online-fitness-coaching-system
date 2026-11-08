import os

import pytest
from pydantic import SecretStr

from app.core.config import settings


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

    yield

    # Restore settings
    settings.f01_provider = orig_provider
    settings.openai_api_key = orig_key
    settings.f01_mock_enabled = orig_mock
