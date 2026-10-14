import secrets

from fastapi import HTTPException, Security, status
from fastapi.security import APIKeyHeader

from app.core.config import settings

INTERNAL_KEY_HEADER = APIKeyHeader(name="X-Internal-Service-Key", auto_error=False)


async def verify_internal_service_key(
    api_key: str | None = Security(INTERNAL_KEY_HEADER),
) -> str:
    """Verifies that incoming requests contain a valid internal service key from Spring Boot."""
    if not api_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing internal service authentication key",
        )
    if not secrets.compare_digest(api_key, settings.internal_service_key):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Invalid internal service authentication key",
        )
    return api_key
