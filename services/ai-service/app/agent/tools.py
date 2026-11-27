from __future__ import annotations

import json
import logging
from typing import Any, Literal

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.agent.prompts import wrap_untrusted_data
from app.agent.schemas import (
    AgentRunBinding,
    ProposedActionDraft,
    ToolObservation,
    format_chunk_citation_key,
)
from app.core.config import settings
from app.rag.pipeline import RetrievalPipeline
from app.rag.retriever import RetrievedChunkDto

logger = logging.getLogger(__name__)


class SearchKnowledgeArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str = Field(min_length=1, max_length=500)
    limit: int = Field(default=5, ge=1, le=10)


class GetCurrentPlanArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")


class GetProgressSummaryArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")
    windowDays: int = Field(default=14, ge=1, le=90)


class GetAvailabilityArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")
    days: int = Field(default=7, ge=1, le=30)


class PrepareWorkoutAdjustmentArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")
    scope: Literal["ONE_MATERIALIZED_OCCURRENCE", "TEMPLATE_OR_FUTURE_OCCURRENCES"] = (
        "TEMPLATE_OR_FUTURE_OCCURRENCES"
    )
    targetPlanId: str | None = Field(default=None, max_length=64)
    planVersionNumber: int | None = Field(default=None, ge=1)
    exercisePrescriptionId: str | None = Field(default=None, max_length=64)
    plannedWorkoutId: str | None = Field(default=None, max_length=64)
    plannedSessionExerciseId: str | None = Field(default=None, max_length=64)
    reason: str = Field(min_length=3, max_length=500)
    requestedChange: str = Field(min_length=3, max_length=500)


class PrepareRescheduleArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")
    occurrenceId: str = Field(min_length=1, max_length=64)
    reason: str = Field(min_length=3, max_length=500)


TOOL_ARG_MODELS: dict[str, type[BaseModel]] = {
    "search_knowledge": SearchKnowledgeArgs,
    "get_current_plan": GetCurrentPlanArgs,
    "get_progress_summary": GetProgressSummaryArgs,
    "get_availability": GetAvailabilityArgs,
    "prepare_workout_adjustment": PrepareWorkoutAdjustmentArgs,
    "prepare_reschedule": PrepareRescheduleArgs,
}

READ_TOOLS: frozenset[str] = frozenset({
    "search_knowledge",
    "get_current_plan",
    "get_progress_summary",
    "get_availability",
})

PROPOSAL_TOOLS: frozenset[str] = frozenset({
    "prepare_workout_adjustment",
    "prepare_reschedule",
})

TOOL_OPENAI_DEFINITIONS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "search_knowledge",
            "description": "Tìm kiếm tài liệu kiến thức thể hình ACTIVE được phép cho câu hỏi kỹ thuật/nguyên tắc tập.",
            "strict": True,
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string"},
                    "limit": {
                        "type": "integer",
                        "description": "Số lượng đoạn tài liệu mục tiêu (mặc định và tối thiểu theo policy hệ thống là 5, tối đa 10).",
                    },
                },
                "required": ["query", "limit"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_current_plan",
            "description": "Đọc kế hoạch tập luyện hiện tại của học viên trong phạm vi quyền được cấp.",
            "strict": True,
            "parameters": {
                "type": "object",
                "properties": {},
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_progress_summary",
            "description": "Đọc tóm tắt tiến độ và chỉ số từ Progress Engine trong cửa sổ ngày.",
            "strict": True,
            "parameters": {
                "type": "object",
                "properties": {
                    "windowDays": {"type": "integer"},
                },
                "required": ["windowDays"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_availability",
            "description": "Đọc lịch tập dự kiến và xung đột lịch trong cửa sổ ngày.",
            "strict": True,
            "parameters": {
                "type": "object",
                "properties": {
                    "days": {"type": "integer"},
                },
                "required": ["days"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "prepare_workout_adjustment",
            "description": "Chuẩn bị bản nháp đề xuất điều chỉnh giáo án (F03) chờ người có thẩm quyền xác nhận. Không tự áp dụng.",
            "strict": True,
            "parameters": {
                "type": "object",
                "properties": {
                    "scope": {
                        "type": "string",
                        "enum": ["ONE_MATERIALIZED_OCCURRENCE", "TEMPLATE_OR_FUTURE_OCCURRENCES"],
                    },
                    "targetPlanId": {"type": ["string", "null"]},
                    "planVersionNumber": {"type": ["integer", "null"]},
                    "exercisePrescriptionId": {"type": ["string", "null"]},
                    "plannedWorkoutId": {"type": ["string", "null"]},
                    "plannedSessionExerciseId": {"type": ["string", "null"]},
                    "reason": {"type": "string"},
                    "requestedChange": {"type": "string"},
                },
                "required": [
                    "scope",
                    "targetPlanId",
                    "planVersionNumber",
                    "exercisePrescriptionId",
                    "plannedWorkoutId",
                    "plannedSessionExerciseId",
                    "reason",
                    "requestedChange",
                ],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "prepare_reschedule",
            "description": "Chuẩn bị bản nháp đề xuất xếp lại một buổi tập đã lên lịch (F05) chờ người có thẩm quyền xác nhận. Không tự áp dụng.",
            "strict": True,
            "parameters": {
                "type": "object",
                "properties": {
                    "occurrenceId": {"type": "string"},
                    "reason": {"type": "string"},
                },
                "required": ["occurrenceId", "reason"],
                "additionalProperties": False,
            },
        },
    },
]


class ToolGateway:
    """Strict allowlist + schema + server-bound authorization gateway for agent tools."""

    def __init__(
        self,
        retrieval_pipeline: RetrievalPipeline | None = None,
        backend_base_url: str | None = None,
        internal_key: str | None = None,
    ):
        self.retrieval_pipeline = retrieval_pipeline or RetrievalPipeline()
        self.backend_base_url = (backend_base_url or settings.backend_base_url).rstrip("/")
        self.internal_key = internal_key or settings.internal_service_key

    def is_tool_allowed(self, tool_name: str, binding: AgentRunBinding) -> bool:
        if tool_name not in TOOL_ARG_MODELS:
            return False
        if binding.allowedActions:
            return tool_name in binding.allowedActions
        return tool_name in READ_TOOLS

    @staticmethod
    def canonical_call_key(tool_name: str, args: dict[str, Any]) -> str:
        return f"{tool_name}:{json.dumps(args, sort_keys=True, ensure_ascii=False)}"

    async def execute(
        self,
        tool_name: str,
        raw_args: dict[str, Any],
        binding: AgentRunBinding,
    ) -> tuple[ToolObservation, list[RetrievedChunkDto], ProposedActionDraft | None]:
        if not self.is_tool_allowed(tool_name, binding):
            return (
                ToolObservation(
                    toolName=tool_name,
                    status="DENIED",
                    summary=f"Tool '{tool_name}' is not allowed for this run.",
                ),
                [],
                None,
            )

        arg_model = TOOL_ARG_MODELS[tool_name]
        try:
            parsed_args = arg_model.model_validate(raw_args)
        except ValidationError as exc:
            return (
                ToolObservation(
                    toolName=tool_name,
                    status="ERROR",
                    summary=f"Invalid tool arguments: {exc.errors()[0].get('msg', 'validation error')}",
                ),
                [],
                None,
            )

        if tool_name == "search_knowledge":
            assert isinstance(parsed_args, SearchKnowledgeArgs)
            return await self._exec_search_knowledge(parsed_args, binding), [], None

        path_map: dict[str, str] = {
            "get_current_plan": "/internal/v1/agent/tools/current-plan",
            "get_progress_summary": "/internal/v1/agent/tools/progress-summary",
            "get_availability": "/internal/v1/agent/tools/availability",
            "prepare_workout_adjustment": "/internal/v1/agent/tools/prepare-workout-adjustment",
            "prepare_reschedule": "/internal/v1/agent/tools/prepare-reschedule",
        }
        return await self._exec_backend_tool(tool_name, path_map[tool_name], parsed_args.model_dump(), binding)

    async def _exec_search_knowledge(
        self,
        args: SearchKnowledgeArgs,
        binding: AgentRunBinding,
    ) -> ToolObservation:
        if not binding.allowedSourceIds:
            return ToolObservation(
                toolName="search_knowledge",
                status="DENIED",
                summary="No allowed knowledge sources bound to this run.",
            )

        authorization = await self._authorize_search(binding)
        if authorization.status != "OK":
            return authorization

        min_top_k = max(1, min(settings.rag_search_min_top_k, 10))
        effective_limit = max(args.limit, min_top_k)

        res = await self.retrieval_pipeline.run(
            query=args.query,
            allowed_source_ids=binding.allowedSourceIds,
            limit=effective_limit,
            run_id=str(binding.runId),
            correlation_id=str(binding.correlationId),
        )
        if not res.server_responded:
            return ToolObservation(
                toolName="search_knowledge",
                status="ERROR",
                summary="Knowledge retrieval backend unavailable.",
            )
        if not res.chunks:
            return ToolObservation(
                toolName="search_knowledge",
                status="NOT_FOUND",
                summary="No ACTIVE verified knowledge chunks matched the query.",
                data={
                    "requestedLimit": args.limit,
                    "effectiveLimit": effective_limit,
                    "returnedCount": 0,
                    "candidateCount": res.candidate_count,
                },
            )

        refs = [format_chunk_citation_key(c.documentCode, c.version, c.sectionIndex) for c in res.chunks]
        chunks_payload = [
            {
                "chunkId": c.chunkId,
                "documentCode": c.documentCode,
                "version": c.version,
                "sourceId": c.sourceId,
                "sourcePath": c.sourcePath,
                "heading": c.heading,
                "sectionIndex": c.sectionIndex,
                "content": wrap_untrusted_data("KNOWLEDGE_CHUNK", c.content),
                "rawContent": c.content,
                "similarityScore": c.similarityScore,
            }
            for c in res.chunks
        ]
        return ToolObservation(
            toolName="search_knowledge",
            status="OK",
            summary=f"Retrieved {len(res.chunks)} verified chunks.",
            data={
                "chunks": chunks_payload,
                "requestedLimit": args.limit,
                "effectiveLimit": effective_limit,
                "returnedCount": len(res.chunks),
                "candidateCount": res.candidate_count,
                "rerankApplied": res.rerank_applied,
                "mmrApplied": res.mmr_applied,
                "degraded": res.degraded,
            },
            sourceRefs=refs,
        )

    async def _authorize_search(self, binding: AgentRunBinding) -> ToolObservation:
        observation, _, _ = await self._exec_backend_tool(
            "search_knowledge", "/internal/v1/agent/tools/knowledge-access", {}, binding,
        )
        return observation

    async def _exec_backend_tool(
        self,
        tool_name: str,
        path: str,
        arguments: dict[str, Any],
        binding: AgentRunBinding,
    ) -> tuple[ToolObservation, list[RetrievedChunkDto], ProposedActionDraft | None]:
        url = f"{self.backend_base_url}{path}"
        headers = {
            "X-Internal-Service-Key": self.internal_key,
            "Content-Type": "application/json",
            "X-Run-ID": str(binding.runId),
            "X-Correlation-ID": str(binding.correlationId),
        }
        payload = {
            "runId": str(binding.runId),
            "actorId": str(binding.actorId),
            "actorRole": binding.actorRole,
            "subjectId": str(binding.subjectId) if binding.subjectId else None,
            "arguments": arguments,
        }
        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                resp = await client.post(url, headers=headers, json=payload)
                if resp.status_code == 403:
                    return (
                        ToolObservation(
                            toolName=tool_name,
                            status="DENIED",
                            summary="Backend denied access for current actor/subject/scope.",
                        ),
                        [],
                        None,
                    )
                if resp.status_code == 404:
                    return (
                        ToolObservation(
                            toolName=tool_name,
                            status="NOT_FOUND",
                            summary="Requested domain resource not found.",
                        ),
                        [],
                        None,
                    )
                if resp.status_code != 200:
                    return (
                        ToolObservation(
                            toolName=tool_name,
                            status="ERROR",
                            summary=f"Backend tool returned HTTP {resp.status_code}.",
                        ),
                        [],
                        None,
                    )

                body = resp.json()
                status_val: Literal["OK", "DENIED", "NOT_FOUND", "UNKNOWN_DATA", "ERROR"] = body.get(
                    "status", "OK"
                )
                draft_raw = body.get("proposalDraft")
                draft = ProposedActionDraft.model_validate(draft_raw) if draft_raw else None
                obs = ToolObservation(
                    toolName=tool_name,
                    status=status_val,
                    summary=str(body.get("summary", "Tool executed.")),
                    data=body.get("data", {}) or {},
                    sourceRefs=body.get("sourceRefs", []) or [],
                )
                return obs, [], draft
        except (httpx.HTTPError, ValueError, OSError) as exc:
            logger.warning("Backend tool %s failed: %s", tool_name, type(exc).__name__)
            return (
                ToolObservation(
                    toolName=tool_name,
                    status="ERROR",
                    summary=f"Backend tool transport error: {type(exc).__name__}",
                ),
                [],
                None,
            )
