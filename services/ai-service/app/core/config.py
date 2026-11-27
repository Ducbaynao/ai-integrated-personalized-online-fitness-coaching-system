from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    service_name: str = "fitness-coaching-ai-service"
    environment: str = "development"
    internal_service_key: str = "dev-ai-internal-key-change-me"
    backend_base_url: str = "http://localhost:8080"
    backend_internal_retrieval_enabled: bool = True
    f01_mock_enabled: bool = True
    f01_prompt_version: str = "v1.0"
    f01_rule_version: str = "f01-rule-v1"
    f01_model: str = "simulated-f01-v1"
    f01_provider: str = "simulated"

    # OpenAI Settings for F01 Generation (DEC-A13-02)
    openai_api_key: SecretStr = SecretStr("")
    openai_api_url: str = "https://api.openai.com/v1/chat/completions"
    openai_model: str = "gpt-4o-mini-2024-07-18"
    openai_store: bool = False
    openai_connect_timeout_seconds: float = 5.0
    openai_read_timeout_seconds: float = 12.0
    openai_max_retries: int = 1
    openai_max_completion_tokens: int = 800
    openai_overall_timeout_seconds: float = 22.0

    # AG-M2: Rerank, MMR, and Context Budget flags (disabled by default)
    rag_rerank_enabled: bool = False
    rag_rerank_url: str = ""
    rag_rerank_api_key: SecretStr = SecretStr("")
    rag_rerank_model: str = "rerank-multilingual-v3.0"
    rag_rerank_timeout_seconds: float = 4.0
    rag_mmr_enabled: bool = False
    rag_mmr_lambda: float = 0.7
    rag_context_max_chars: int = 8000
    rag_search_min_top_k: int = 5

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
