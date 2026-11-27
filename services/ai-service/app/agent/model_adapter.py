from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.agent.prompts import SYSTEM_PROMPT_V1, wrap_untrusted_data
from app.agent.schemas import AgentTurnRequest, ToolCallRequest, ToolObservation
from app.agent.tools import READ_TOOLS, TOOL_OPENAI_DEFINITIONS
from app.core.config import settings

logger = logging.getLogger(__name__)

AGENT_FINAL_RESPONSE_SCHEMA: dict[str, Any] = {
    "name": "agent_turn_final_response",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "resultType": {
                "type": "string",
                "enum": ["INFORMATION", "CLARIFICATION_REQUIRED", "INSUFFICIENT_DATA", "BLOCKED"],
            },
            "answer": {"type": "string"},
            "clarificationQuestion": {"type": ["string", "null"]},
            "citedChunkKeys": {"type": "array", "items": {"type": "string"}},
            "limitations": {"type": "array", "items": {"type": "string"}},
            "missingData": {"type": "array", "items": {"type": "string"}},
        },
        "required": [
            "resultType",
            "answer",
            "clarificationQuestion",
            "citedChunkKeys",
            "limitations",
            "missingData",
        ],
        "additionalProperties": False,
    },
}


@dataclass(frozen=True)
class AgentDecision:
    kind: Literal["TOOL_CALL", "CLARIFY", "FINAL"]
    tool_call: ToolCallRequest | None = None
    clarification_question: str | None = None
    answer: str = ""
    result_type: Literal["INFORMATION", "PROPOSAL_DRAFT", "INSUFFICIENT_DATA", "BLOCKED"] = "INFORMATION"
    reason_code: str = "NONE"
    cited_chunk_keys: list[str] = field(default_factory=list)
    limitations: list[str] = field(default_factory=list)
    missing_data: list[str] = field(default_factory=list)


class AgentFinalOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    resultType: Literal["INFORMATION", "CLARIFICATION_REQUIRED", "INSUFFICIENT_DATA", "BLOCKED"]
    answer: str = Field(max_length=4000)
    clarificationQuestion: str | None = Field(max_length=4000)
    citedChunkKeys: list[str] = Field(max_length=40)
    limitations: list[str]
    missingData: list[str]


class AgentModelAdapter(Protocol):
    provider_name: str
    model_name: str

    async def decide_next(
        self,
        request: AgentTurnRequest,
        observations: list[ToolObservation],
        turn_index: int,
        remaining_seconds: float | None = None,
    ) -> AgentDecision: ...


class SimulatedAgentModelAdapter:
    """Deterministic offline agent adapter for tests and local verification without paid API calls."""

    provider_name = "simulated"
    model_name = "simulated-agent-v1"

    async def decide_next(
        self,
        request: AgentTurnRequest,
        observations: list[ToolObservation],
        turn_index: int,
        remaining_seconds: float | None = None,
    ) -> AgentDecision:
        msg = request.message.strip()
        lower = msg.lower()

        # Guardrail: refuse prohibited pharmacological/doping queries deterministically
        if any(tok in lower for tok in ("steroid", "doping", "trigger_blocked")):
            return AgentDecision(
                kind="FINAL",
                result_type="BLOCKED",
                reason_code="RULE_BLOCKED",
                limitations=[
                    "Hệ thống không hướng dẫn sử dụng chất kích thích thuộc danh mục cấm hoặc kê đơn điều trị."
                ],
            )

        # Clarification if message is too vague/short and no observations yet
        if not observations and len(msg.split()) < 3 and any(
            w in lower for w in ("tư vấn", "giúp", "sao", "help", "plan")
        ):
            return AgentDecision(
                kind="CLARIFY",
                clarification_question="Bạn muốn hỏi về kỹ thuật bài tập, xem tiến độ hiện tại, hay điều chỉnh lịch tập?",
            )

        called_tools = [obs.toolName for obs in observations]
        allowed = set(request.binding.allowedActions)

        if (
            any(k in lower for k in ("tiến độ", "progress", "tuần qua", "tập được"))
            and "get_progress_summary" not in called_tools
            and (not allowed or "get_progress_summary" in allowed)
        ):
            return AgentDecision(
                kind="TOOL_CALL",
                tool_call=ToolCallRequest(toolName="get_progress_summary", arguments={"windowDays": 14}),
            )

        if (
            any(k in lower for k in ("giáo án", "plan", "bài tập hôm nay", "chương trình"))
            and "get_current_plan" not in called_tools
            and (not allowed or "get_current_plan" in allowed)
        ):
            return AgentDecision(
                kind="TOOL_CALL",
                tool_call=ToolCallRequest(toolName="get_current_plan", arguments={}),
            )

        if (
            any(k in lower for k in ("lịch", "rảnh", "availability", "đổi lịch", "reschedule"))
            and "get_availability" not in called_tools
            and (not allowed or "get_availability" in allowed)
        ):
            return AgentDecision(
                kind="TOOL_CALL",
                tool_call=ToolCallRequest(toolName="get_availability", arguments={"days": 7}),
            )

        if (
            "search_knowledge" not in called_tools
            and (
                not observations
                or any(k in lower for k in ("kỹ thuật", "nguyên tắc", "creatine", "protein", "who", "issn", "tập"))
            )
            and (not allowed or "search_knowledge" in allowed)
        ):
            return AgentDecision(
                kind="TOOL_CALL",
                tool_call=ToolCallRequest(toolName="search_knowledge", arguments={"query": msg[:300], "limit": 5}),
            )

        search_obs = next((o for o in observations if o.toolName == "search_knowledge" and o.status == "OK"), None)
        if search_obs:
            chunks = search_obs.data.get("chunks", [])
            cited_keys = [f"{c['documentCode']}@{c['version']}#{c['sectionIndex']}" for c in chunks[:2]]
            snippets = [str(c.get("rawContent", ""))[:220] for c in chunks[:2]]
            answer = "Dựa trên tài liệu đã kiểm duyệt:\n" + "\n".join(f"- {s}" for s in snippets)
            return AgentDecision(
                kind="FINAL",
                result_type="INFORMATION",
                reason_code="NONE",
                answer=answer,
                cited_chunk_keys=cited_keys,
                limitations=["Thông tin mang tính tham khảo từ kho tri thức ACTIVE."],
            )

        domain_ok = [o for o in observations if o.toolName != "search_knowledge" and o.status == "OK"]
        if domain_ok:
            summary_lines = [f"- {o.toolName}: {o.summary}" for o in domain_ok]
            return AgentDecision(
                kind="FINAL",
                result_type="INFORMATION",
                reason_code="NONE",
                answer="Thông tin hiện có trong phạm vi được phép:\n" + "\n".join(summary_lines),
                limitations=["Dữ liệu phản ánh trạng thái tại thời điểm truy vấn."],
            )

        return AgentDecision(
            kind="FINAL",
            result_type="INSUFFICIENT_DATA",
            reason_code="NO_RELEVANT_EVIDENCE",
            answer="",
            missing_data=["Chưa tìm thấy bằng chứng hoặc dữ liệu đủ điều kiện trong phạm vi được phép."],
            limitations=["Không tự suy đoán khi thiếu dữ liệu."],
        )


class OpenAiAgentModelAdapter:
    """Native OpenAI tool-calling + structured final output adapter."""

    provider_name = "openai"

    def __init__(
        self,
        model: str | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        self.model_name = model or settings.openai_model
        self._transport = transport

    async def decide_next(
        self,
        request: AgentTurnRequest,
        observations: list[ToolObservation],
        turn_index: int,
        remaining_seconds: float | None = None,
    ) -> AgentDecision:
        api_key = settings.openai_api_key.get_secret_value().strip()
        if not api_key:
            raise RuntimeError("OPENAI_API_KEY is not configured")

        messages: list[dict[str, Any]] = [
            {
                "role": "system",
                "content": (
                    SYSTEM_PROMPT_V1
                    + "\nKhi kết thúc (không gọi tool), trả JSON theo schema với citedChunkKeys dạng "
                    "'documentCode@version#sectionIndex' lấy từ tool search_knowledge."
                ),
            }
        ]
        for h in request.conversationHistory:
            messages.append({"role": h.role, "content": wrap_untrusted_data("HISTORY", h.content)})
        messages.append({"role": "user", "content": wrap_untrusted_data("USER_MESSAGE", request.message)})
        if observations:
            obs_text = json.dumps([o.model_dump() for o in observations], ensure_ascii=False)
            messages.append({"role": "user", "content": wrap_untrusted_data("TOOL_OBSERVATIONS", obs_text)})

        allowed_names = set(request.binding.allowedActions) if request.binding.allowedActions else READ_TOOLS
        allowed_defs = [
            d
            for d in TOOL_OPENAI_DEFINITIONS
            if d["function"]["name"] in allowed_names
        ]

        timeout_val = min(
            settings.openai_read_timeout_seconds,
            max(0.5, remaining_seconds) if remaining_seconds is not None else settings.openai_read_timeout_seconds,
        )

        payload: dict[str, Any] = {
            "model": self.model_name,
            "messages": messages,
            "tools": allowed_defs,
            "tool_choice": "auto",
            "max_completion_tokens": settings.openai_max_completion_tokens,
            "response_format": {"type": "json_schema", "json_schema": AGENT_FINAL_RESPONSE_SCHEMA},
            "temperature": 0.1,
            "store": False,
            "parallel_tool_calls": False,
        }
        headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
        max_attempts = max(1, settings.openai_max_retries + 1)
        data: dict[str, Any] | None = None
        async with httpx.AsyncClient(timeout=timeout_val, transport=self._transport) as client:
            for attempt in range(max_attempts):
                try:
                    resp = await client.post(settings.openai_api_url, headers=headers, json=payload)
                    resp.raise_for_status()
                    data = resp.json()
                    break
                except httpx.HTTPStatusError as exc:
                    status_code = exc.response.status_code
                    is_transient = status_code == 429 or status_code >= 500
                    if not is_transient or attempt >= max_attempts - 1:
                        raise
                except httpx.RequestError:
                    if attempt >= max_attempts - 1:
                        raise
        assert data is not None

        choice = data["choices"][0]["message"]
        if choice.get("refusal"):
            return AgentDecision(kind="FINAL", result_type="BLOCKED", reason_code="RULE_BLOCKED")
        tool_calls = choice.get("tool_calls") or []
        if tool_calls:
            # Execute one tool call per bounded turn so repeat/budget/cancel checks apply before each next step.
            tc = tool_calls[0]
            fn = tc["function"]
            args = json.loads(fn.get("arguments") or "{}")
            return AgentDecision(
                kind="TOOL_CALL",
                tool_call=ToolCallRequest(toolName=fn["name"], arguments=args),
            )

        raw_content = (choice.get("content") or "").strip()
        try:
            parsed = AgentFinalOutput.model_validate_json(raw_content).model_dump()
        except (ValueError, ValidationError):
            return AgentDecision(
                kind="FINAL",
                result_type="INSUFFICIENT_DATA",
                reason_code="VALIDATION_FAILED",
                missing_data=["Model output did not conform to structured JSON contract."],
            )

        res_type = parsed.get("resultType", "INFORMATION")
        if res_type == "CLARIFICATION_REQUIRED":
            return AgentDecision(
                kind="CLARIFY",
                clarification_question=parsed.get("clarificationQuestion")
                or "Vui lòng cung cấp thêm thông tin để tiếp tục.",
            )
        if res_type == "BLOCKED":
            return AgentDecision(
                kind="FINAL",
                result_type="BLOCKED",
                reason_code="RULE_BLOCKED",
                limitations=parsed.get("limitations") or [],
            )
        if res_type == "INSUFFICIENT_DATA":
            return AgentDecision(
                kind="FINAL",
                result_type="INSUFFICIENT_DATA",
                reason_code="NO_RELEVANT_EVIDENCE",
                missing_data=parsed.get("missingData") or [],
                limitations=parsed.get("limitations") or [],
            )
        return AgentDecision(
            kind="FINAL",
            result_type="INFORMATION",
            reason_code="NONE",
            answer=parsed.get("answer") or "",
            cited_chunk_keys=parsed.get("citedChunkKeys") or [],
            limitations=parsed.get("limitations") or [],
            missing_data=parsed.get("missingData") or [],
        )
