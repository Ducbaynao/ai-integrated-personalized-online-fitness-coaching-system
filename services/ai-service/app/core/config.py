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
    openai_api_key: str = ""
    openai_api_url: str = "https://api.openai.com/v1/chat/completions"
    openai_model: str = "gpt-4o-mini-2024-07-18"
    openai_store: bool = False
    openai_connect_timeout_seconds: float = 5.0
    openai_read_timeout_seconds: float = 12.0
    openai_max_retries: int = 1
    openai_overall_timeout_seconds: float = 22.0

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
