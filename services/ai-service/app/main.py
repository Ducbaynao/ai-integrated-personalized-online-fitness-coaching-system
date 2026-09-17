from fastapi import FastAPI

app = FastAPI(
    title="Fitness Coaching AI Service",
    version="0.1.0",
    description="Creates structured recommendations without applying business changes.",
)


@app.get("/health", tags=["operations"])
def health() -> dict[str, str]:
    return {"status": "ok", "service": "fitness-coaching-ai-service"}
