from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    service_name: str = "fitness-coaching-ai-service"
    environment: str = "development"
    internal_service_key: str = "dev-ai-internal-key-change-me"
    backend_base_url: str = "http://localhost:8080"
    backend_internal_retrieval_enabled: bool = False
    f01_mock_enabled: bool = True
    f01_prompt_version: str = "v1.0"
    f01_rule_version: str = "f01-rule-v1"
    f01_model: str = "simulated-f01-v1"
    f01_provider: str = "simulated"

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
