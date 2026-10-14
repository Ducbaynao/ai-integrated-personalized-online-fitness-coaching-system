from fastapi import FastAPI

from app.api.routes.internal_knowledge import router as internal_knowledge_router

app = FastAPI(
    title="Fitness Coaching AI Service",
    version="0.1.0",
    description="Creates structured recommendations without applying business changes.",
)

app.include_router(internal_knowledge_router)


@app.get("/health", tags=["operations"])
def health() -> dict[str, str]:
    return {"status": "ok", "service": "fitness-coaching-ai-service"}
