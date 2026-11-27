from __future__ import annotations

import asyncio
import time
from collections.abc import Callable

import httpx

from app.agent.model_adapter import AgentModelAdapter, SimulatedAgentModelAdapter
from app.agent.prompts import AGENT_PROMPT_VERSION
from app.agent.schemas import (
    AgentStepTrace,
    AgentTurnRequest,
    AgentTurnResponse,
    ProposedActionDraft,
    ToolObservation,
    format_chunk_citation_key,
)
from app.agent.tools import ToolGateway
from app.schemas.f01_schemas import CitationDto, LocatorDto


class BoundedAgentRunner:
    """Executes a bounded, cancellable, non-authoritative agent loop."""

    def __init__(
        self,
        model_adapter: AgentModelAdapter | None = None,
        tool_gateway: ToolGateway | None = None,
    ):
        self.model_adapter = model_adapter or SimulatedAgentModelAdapter()
        self.tool_gateway = tool_gateway or ToolGateway()

    async def run(
        self,
        request: AgentTurnRequest,
        is_cancelled: Callable[[], bool] | None = None,
    ) -> AgentTurnResponse:
        steps: list[AgentStepTrace] = []
        try:
            async with asyncio.timeout(request.deadlineSeconds):
                return await self._run(request, is_cancelled, steps)
        except TimeoutError:
            return self._timeout_response(request, steps)
        except (httpx.HTTPError, RuntimeError, ValueError, KeyError, IndexError, TypeError):
            return self._timeout_response(request, steps).model_copy(update={
                "reasonCode": "PROVIDER_ERROR", "stopReason": "PROVIDER_ERROR",
            })

    async def _run(
        self,
        request: AgentTurnRequest,
        is_cancelled: Callable[[], bool] | None,
        steps: list[AgentStepTrace],
    ) -> AgentTurnResponse:
        start = time.monotonic()
        observations: list[ToolObservation] = []
        seen_calls: set[str] = set()
        tool_calls_used = 0
        search_calls_used = 0
        proposal_draft: ProposedActionDraft | None = None
        chunk_index: dict[str, dict] = {}

        for turn in range(1, request.maxModelTurns + 1):
            if is_cancelled and is_cancelled():
                return AgentTurnResponse(
                    runId=request.binding.runId,
                    correlationId=request.binding.correlationId,
                    runStatus="CANCELLED",
                    resultType="BLOCKED",
                    reasonCode="CANCELLED",
                    stopReason="CANCELLED",
                    promptVersion=AGENT_PROMPT_VERSION,
                    model=self.model_adapter.model_name,
                    provider=self.model_adapter.provider_name,
                    steps=steps,
                )

            if (time.monotonic() - start) >= request.deadlineSeconds:
                return self._timeout_response(request, steps)

            t0 = time.monotonic()
            decision = await self.model_adapter.decide_next(
                request, observations, turn,
                remaining_seconds=request.deadlineSeconds - (time.monotonic() - start),
            )
            decision_ms = int((time.monotonic() - t0) * 1000)

            if decision.kind == "CLARIFY":
                steps.append(
                    AgentStepTrace(
                        step=len(steps) + 1,
                        action="CLARIFICATION",
                        status="OK",
                        durationMs=decision_ms,
                    )
                )
                return AgentTurnResponse(
                    runId=request.binding.runId,
                    correlationId=request.binding.correlationId,
                    runStatus="SUCCEEDED",
                    resultType="CLARIFICATION_REQUIRED",
                    reasonCode="CLARIFICATION_REQUIRED",
                    clarificationQuestion=decision.clarification_question,
                    stopReason="CLARIFICATION_REQUIRED",
                    promptVersion=AGENT_PROMPT_VERSION,
                    model=self.model_adapter.model_name,
                    provider=self.model_adapter.provider_name,
                    steps=steps,
                )

            if decision.kind == "FINAL":
                steps.append(
                    AgentStepTrace(
                        step=len(steps) + 1,
                        action="FINAL_ANSWER",
                        status=decision.result_type,
                        durationMs=decision_ms,
                    )
                )
                citations = self._build_verified_citations(decision.cited_chunk_keys, chunk_index)
                if len(citations) != len(decision.cited_chunk_keys) or (
                    decision.result_type == "INFORMATION" and chunk_index and not citations
                ):
                    return self._timeout_response(request, steps).model_copy(update={
                        "runStatus": "REJECTED_BY_VALIDATOR", "resultType": "BLOCKED",
                        "reasonCode": "UNVERIFIED_CITATION", "stopReason": "UNVERIFIED_CITATION",
                    })
                if proposal_draft is not None:
                    result_type = "PROPOSAL_DRAFT"
                else:
                    result_type = decision.result_type

                run_status = "REJECTED_BY_VALIDATOR" if result_type == "BLOCKED" else "SUCCEEDED"
                return AgentTurnResponse(
                    runId=request.binding.runId,
                    correlationId=request.binding.correlationId,
                    runStatus=run_status,
                    resultType=result_type,
                    reasonCode=decision.reason_code,
                    answer=decision.answer,
                    citations=citations,
                    evidenceChunkIds=list(dict.fromkeys(c["chunkId"] for c in chunk_index.values())),
                    limitations=decision.limitations,
                    missingData=decision.missing_data,
                    proposalDraft=proposal_draft,
                    stopReason="FINAL_ANSWER",
                    promptVersion=AGENT_PROMPT_VERSION,
                    model=self.model_adapter.model_name,
                    provider=self.model_adapter.provider_name,
                    steps=steps,
                )

            # TOOL_CALL handling
            tc = decision.tool_call
            if tc is None:
                break

            steps.append(
                AgentStepTrace(
                    step=len(steps) + 1,
                    action="MODEL_DECISION",
                    toolName=tc.toolName,
                    status="REQUESTED",
                    durationMs=decision_ms,
                )
            )

            if tool_calls_used >= request.maxToolCalls:
                steps.append(
                    AgentStepTrace(
                        step=len(steps) + 1,
                        action="STOPPED",
                        toolName=tc.toolName,
                        status="TOOL_BUDGET_EXCEEDED",
                    )
                )
                return self._budget_exhausted_response(request, steps, "TOOL_BUDGET_EXCEEDED")

            if tc.toolName == "search_knowledge" and search_calls_used >= request.maxSearchCalls:
                obs = ToolObservation(
                    toolName=tc.toolName,
                    status="DENIED",
                    summary="Search budget exceeded for this turn.",
                )
                observations.append(obs)
                steps.append(
                    AgentStepTrace(
                        step=len(steps) + 1,
                        action="TOOL_CALL",
                        toolName=tc.toolName,
                        status="SEARCH_BUDGET_EXCEEDED",
                    )
                )
                continue

            call_key = self.tool_gateway.canonical_call_key(tc.toolName, tc.arguments)
            if call_key in seen_calls:
                obs = ToolObservation(
                    toolName=tc.toolName,
                    status="REJECTED_REPEAT",
                    summary="Repeated identical tool call rejected; use existing observations or conclude.",
                )
                observations.append(obs)
                steps.append(
                    AgentStepTrace(
                        step=len(steps) + 1,
                        action="TOOL_CALL",
                        toolName=tc.toolName,
                        status="REJECTED_REPEAT",
                    )
                )
                continue

            seen_calls.add(call_key)
            tool_calls_used += 1
            if tc.toolName == "search_knowledge":
                search_calls_used += 1

            t_tool = time.monotonic()
            obs, _, maybe_draft = await self.tool_gateway.execute(tc.toolName, tc.arguments, request.binding)
            tool_ms = int((time.monotonic() - t_tool) * 1000)
            observations.append(obs)
            if maybe_draft is not None:
                proposal_draft = maybe_draft

            if tc.toolName == "search_knowledge" and obs.status == "OK":
                for c in obs.data.get("chunks", []):
                    key = format_chunk_citation_key(c["documentCode"], c["version"], int(c["sectionIndex"]))
                    chunk_index[key] = c

            steps.append(
                AgentStepTrace(
                    step=len(steps) + 1,
                    action="TOOL_CALL",
                    toolName=tc.toolName,
                    status=obs.status,
                    durationMs=tool_ms,
                    sourceRefs=obs.sourceRefs,
                )
            )

        return self._budget_exhausted_response(request, steps, "MAX_MODEL_TURNS_EXCEEDED")

    @staticmethod
    def _build_verified_citations(
        cited_keys: list[str],
        chunk_index: dict[str, dict],
    ) -> list[CitationDto]:
        citations: list[CitationDto] = []
        for key in cited_keys:
            c = chunk_index.get(key)
            if not c:
                continue
            excerpt = str(c.get("rawContent", ""))[:200]
            citations.append(
                CitationDto(
                    documentId=c["documentCode"],
                    version=c["version"],
                    sourceId=c["sourceId"],
                    locator=LocatorDto(
                        sourcePath=c["sourcePath"],
                        heading=c.get("heading") or "",
                        sectionIndex=int(c.get("sectionIndex", 0)),
                    ),
                    quoteOrExcerpt=excerpt,
                )
            )
        return citations

    def _timeout_response(
        self, request: AgentTurnRequest, steps: list[AgentStepTrace]
    ) -> AgentTurnResponse:
        return AgentTurnResponse(
            runId=request.binding.runId,
            correlationId=request.binding.correlationId,
            runStatus="FAILED",
            resultType="INSUFFICIENT_DATA",
            reasonCode="TIMEOUT",
            stopReason="DEADLINE_EXCEEDED",
            promptVersion=AGENT_PROMPT_VERSION,
            model=self.model_adapter.model_name,
            provider=self.model_adapter.provider_name,
            steps=steps,
        )

    def _budget_exhausted_response(
        self, request: AgentTurnRequest, steps: list[AgentStepTrace], stop_reason: str
    ) -> AgentTurnResponse:
        return AgentTurnResponse(
            runId=request.binding.runId,
            correlationId=request.binding.correlationId,
            runStatus="SUCCEEDED",
            resultType="INSUFFICIENT_DATA",
            reasonCode="BUDGET_EXHAUSTED",
            missingData=["Đã đạt giới hạn bước thực thi cho lượt này; vui lòng thu hẹp câu hỏi."],
            stopReason=stop_reason,
            promptVersion=AGENT_PROMPT_VERSION,
            model=self.model_adapter.model_name,
            provider=self.model_adapter.provider_name,
            steps=steps,
        )
