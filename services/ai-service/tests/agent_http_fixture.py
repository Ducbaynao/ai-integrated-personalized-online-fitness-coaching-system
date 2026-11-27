"""Real production FastAPI/agent routes; only LLM HTTP responses are deterministic.

Launched by AiAgentCrossLayerHttpE2EIntegrationTest on loopback, never by production.
"""
import asyncio
import json
import os

from fastapi import Request

from app.agent.loop import BoundedAgentRunner
from app.agent.model_adapter import OpenAiAgentModelAdapter
from app.api.routes.internal_agent import get_agent_runner
from app.core.config import settings
from app.main import app

if os.environ.get("AGENT_HTTP_E2E") != "true":
    raise RuntimeError("This fixture is only for isolated HTTP integration tests")

settings.openai_api_url = os.environ["E2E_MODEL_URL"]
settings.rag_rerank_enabled = True
settings.rag_rerank_url = os.environ["E2E_RERANK_URL"]
app.dependency_overrides[get_agent_runner] = lambda: BoundedAgentRunner(
    model_adapter=OpenAiAgentModelAdapter()
)
control = {"waiting": False, "release": True, "last_observations": []}


@app.get("/test/control")
def get_control():
    return control


@app.post("/test/control")
async def set_control(request: Request):
    control.update(await request.json())
    return control


def final(result_type="INFORMATION", keys=None, question=None, answer=None):
    default_ans = "Verified response" if result_type == "INFORMATION" else ""
    return {"choices": [{"message": {"role": "assistant", "content": json.dumps({
        "resultType": result_type,
        "answer": answer if answer is not None else default_ans,
        "clarificationQuestion": question,
        "citedChunkKeys": keys or [], "limitations": [], "missingData": [],
    })}}]}


@app.post("/test/model")
async def model(request: Request):
    body = await request.json()
    user = next(m["content"] for m in reversed(body["messages"])
                if "[UNTRUSTED_USER_MESSAGE_START]" in m.get("content", ""))
    observations = []
    for message in body["messages"]:
        content = message.get("content", "")
        if content.startswith("[UNTRUSTED_TOOL_OBSERVATIONS_START]"):
            observations = json.loads(content.split("\n", 1)[1].rsplit("\n", 1)[0])
    control["last_observations"] = observations
    if "CLARIFY" in user:
        return final("CLARIFICATION_REQUIRED", question="Which training topic?")
    if "FOLLOW_UP" in user:
        assert any("Which training topic?" in m.get("content", "") for m in body["messages"])
    if "SLOW" in user and not observations:
        control["waiting"] = True
        while not control["release"]:
            await asyncio.sleep(0.02)
    if "DOMAIN_REVOKE_MID_TURN" in user:
        called = [o["toolName"] for o in observations]
        if "get_current_plan" not in called:
            return {"choices": [{"message": {"tool_calls": [{"id": "call-plan", "type": "function",
                "function": {"name": "get_current_plan", "arguments": "{}"}}]}}]}
        if "get_progress_summary" not in called:
            control["waiting"] = True
            while not control["release"]:
                await asyncio.sleep(0.02)
            return {"choices": [{"message": {"tool_calls": [{"id": "call-prog", "type": "function",
                "function": {"name": "get_progress_summary", "arguments": json.dumps({"windowDays": 14})}}]}}]}
        prog = next((o for o in observations if o["toolName"] == "get_progress_summary"), {})
        if prog.get("status") == "DENIED":
            return final("INSUFFICIENT_DATA", answer="")
        return final("INFORMATION", answer=f"Progress: {prog.get('data', {})}")
    if "DOMAIN_ALL" in user:
        called = [o["toolName"] for o in observations]
        if "get_current_plan" not in called:
            return {"choices": [{"message": {"tool_calls": [{"id": "call-plan", "type": "function",
                "function": {"name": "get_current_plan", "arguments": "{}"}}]}}]}
        if "get_progress_summary" not in called:
            return {"choices": [{"message": {"tool_calls": [{"id": "call-prog", "type": "function",
                "function": {"name": "get_progress_summary", "arguments": json.dumps({"windowDays": 14})}}]}}]}
        if "get_availability" not in called:
            return {"choices": [{"message": {"tool_calls": [{"id": "call-avail", "type": "function",
                "function": {"name": "get_availability", "arguments": json.dumps({"days": 7})}}]}}]}
        plan_obs = next((o for o in observations if o["toolName"] == "get_current_plan"), {})
        prog_obs = next((o for o in observations if o["toolName"] == "get_progress_summary"), {})
        avail_obs = next((o for o in observations if o["toolName"] == "get_availability"), {})
        has_plan = plan_obs.get("status") == "OK"
        has_progress_history = prog_obs.get("data", {}).get("daysSinceLastWorkout") is not None
        has_schedule = (avail_obs.get("data", {}).get("scheduledCount") or 0) > 0
        if not has_plan and not has_progress_history and not has_schedule:
            return final("INSUFFICIENT_DATA", answer="")
        title = plan_obs.get("data", {}).get("title", "unknown")
        cont = prog_obs.get("data", {}).get("continuityAvailable", False)
        sched = avail_obs.get("data", {}).get("scheduledCount", "unknown")
        return final("INFORMATION", answer=f"Plan={title}; continuity={cont}; scheduled={sched}")
    if "DOMAIN" in user:
        if not observations:
            return {"choices": [{"message": {"tool_calls": [{"id": "call-domain", "type": "function",
                "function": {"name": "get_current_plan", "arguments": "{}"}}]}}]}
        return final("INSUFFICIENT_DATA")
    searches = [o for o in observations if o["toolName"] == "search_knowledge"]
    if len(searches) < 2:
        query = "WHO physical activity" if not searches else "protein intake"
        return {"choices": [{"message": {"tool_calls": [{"id": f"call-{len(searches)}", "type": "function",
            "function": {"name": "search_knowledge", "arguments": json.dumps({"query": query, "limit": 1})}}]}}]}
    chunks = searches[0].get("data", {}).get("chunks", [])
    source_refs = searches[0].get("sourceRefs") or []
    if not chunks or not source_refs:
        return final("INSUFFICIENT_DATA")
    # Copy the exact key advertised by ToolGateway in sourceRefs rather than rebuilding it.
    return final(keys=[source_refs[0]])


@app.post("/test/rerank")
async def rerank(request: Request):
    body = await request.json()
    # Cross-encoder HTTP protocol is real; scores are fixtures, not quality evidence.
    return {"results": [{"index": i, "relevance_score": 0.95 if "Adults" in doc else 0.2}
                        for i, doc in enumerate(body["documents"])]}
