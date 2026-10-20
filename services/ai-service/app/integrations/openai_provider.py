import asyncio
from datetime import UTC, datetime
import json
import logging
import random
import re
import time
from typing import Any

import httpx

from app.core.config import settings
from app.integrations.provider_interface import F01KnowledgeProvider
from app.rag.retriever import KnowledgeRetriever, RetrievedChunkDto
from app.schemas.f01_schemas import (
    CitationDto,
    ErrorDto,
    F01InternalContextEnvelope,
    F01KnowledgeQueryResult,
    GenerationMetadataDto,
    LocatorDto,
    UsageDto,
)

logger = logging.getLogger(__name__)

OPENAI_RESPONSE_SCHEMA: dict[str, Any] = {
    "name": "f01_knowledge_generation",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "status": {
                "type": "string",
                "enum": ["ANSWERED", "INSUFFICIENT_EVIDENCE", "REFUSED"],
                "description": "Status of the generated answer.",
            },
            "answer": {
                "type": "string",
                "description": (
                    "Synthesized explanatory answer in Vietnamese grounded strictly in evidence. "
                    "Must be empty string if REFUSED or INSUFFICIENT_EVIDENCE."
                ),
            },
            "citations": {
                "type": "array",
                "description": "List of citations directly supporting claims in the answer.",
                "items": {
                    "type": "object",
                    "properties": {
                        "documentId": {
                            "type": "string",
                            "description": "Canonical document ID (e.g. ISSN-PROTEIN-EXERCISE-2017-VI-04).",
                        },
                        "version": {
                            "type": "string",
                            "description": "Document version string (e.g. 1.0.0).",
                        },
                        "sourceId": {
                            "type": "string",
                            "description": "Source package identifier (e.g. ISSN-PROTEIN-EXERCISE-2017).",
                        },
                        "locator": {
                            "type": "object",
                            "properties": {
                                "sourcePath": {
                                    "type": "string",
                                    "description": "Source path of the cited chunk.",
                                },
                                "heading": {
                                    "type": "string",
                                    "description": "Heading of the section or empty string.",
                                },
                                "sectionIndex": {
                                    "type": "integer",
                                    "description": "0-based section index.",
                                },
                            },
                            "required": ["sourcePath", "heading", "sectionIndex"],
                            "additionalProperties": False,
                        },
                        "quoteOrExcerpt": {
                            "type": "string",
                            "description": "Verbatim quote or excerpt from the chunk content supporting the claim.",
                        },
                    },
                    "required": ["documentId", "version", "sourceId", "locator", "quoteOrExcerpt"],
                    "additionalProperties": False,
                },
            },
            "limitations": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Applicability limitations (e.g. healthy adults, not medical advice).",
            },
            "missingData": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Missing facts or context not covered by evidence.",
            },
            "refusalReason": {
                "type": "string",
                "description": "Explanation if status is REFUSED, or empty string if not refused.",
            },
        },
        "required": ["status", "answer", "citations", "limitations", "missingData", "refusalReason"],
        "additionalProperties": False,
    },
}

DEFAULT_LIMITATIONS: list[str] = [
    "Khuyến nghị áp dụng cho người trưởng thành khỏe mạnh tham gia tập luyện thể chất.",
    "Không thay thế tư vấn y khoa hoặc điều trị bệnh lý chuyên sâu.",
]


def normalize_text_for_comparison(text: str) -> str:
    """Normalize text by lowercasing and stripping punctuation for robust substring verification."""
    cleaned = re.sub(r"[^\w\s]", " ", text.lower(), flags=re.UNICODE)
    return re.sub(r"\s+", " ", cleaned).strip()


class OpenAiKnowledgeProvider(F01KnowledgeProvider):
    """Real OpenAI generation provider for F01 General Fitness Knowledge inquiries.

    Complies with DEC-A13-02:
    - Uses OpenAI Chat Completions API with gpt-4o-mini snapshot.
    - Mandates store: False for data privacy.
    - Strict Structured Outputs with server-side validation against retrieved evidence chunks.
    - Strict citation grounding check to prevent model hallucinations.
    - Finite per-attempt timeouts, exponential backoff with jitter on 429/5xx, and overall deadline.
    """

    def __init__(
        self,
        retriever: KnowledgeRetriever | None = None,
        http_client: httpx.AsyncClient | None = None,
    ):
        self.retriever = retriever or KnowledgeRetriever()
        self.http_client = http_client

    async def query(self, envelope: F01InternalContextEnvelope) -> F01KnowledgeQueryResult:
        start_mono = time.monotonic()
        now_str = datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")

        # 1. Authority / Scope check: must have allowed source IDs
        if not envelope.knowledgeScope.allowedSourceIds:
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="SUCCEEDED",
                resultType="INSUFFICIENT_DATA",
                reasonCode="NO_RELEVANT_EVIDENCE",
                limitations=[
                    "Hệ thống chỉ giải thích dựa trên các nguồn tri thức đã được kiểm duyệt (ISSN, WHO, NIDDK, NIH ODS)."
                ],
                missingData=[
                    "Yêu cầu không chỉ định nguồn tri thức được phép truy xuất trong knowledgeScope."
                ],
                asOf=now_str,
                usage=UsageDto(promptTokens=0, completionTokens=0, totalTokens=0, latencyMs=1),
                generationMetadata=GenerationMetadataDto(
                    schemaVersion="1.0.0",
                    promptVersion=settings.f01_prompt_version,
                    ruleVersion=settings.f01_rule_version,
                    provider="openai",
                    model=settings.openai_model,
                ),
            )

        # 2. Retrieve evidence chunks from Spring Boot backend
        retrieval_res = await self.retriever.retrieve(
            query=envelope.question,
            allowed_source_ids=envelope.knowledgeScope.allowedSourceIds,
            limit=5,
            run_id=str(envelope.requestId) if envelope.requestId else None,
            correlation_id=str(envelope.correlationId) if envelope.correlationId else None,
        )

        if not retrieval_res.server_responded:
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="FAILED",
                reasonCode="MODEL_ERROR",
                error=ErrorDto(
                    code="UPSTREAM_RETRIEVAL_FAILED",
                    message="Upstream knowledge retrieval failed or backend unreachable when retrieval is enabled.",
                ),
                asOf=now_str,
            )

        if not retrieval_res.chunks:
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="SUCCEEDED",
                resultType="INSUFFICIENT_DATA",
                reasonCode="NO_RELEVANT_EVIDENCE",
                limitations=[
                    "Hệ thống chỉ giải thích dựa trên các nguồn tri thức đã được kiểm duyệt (ISSN, WHO, NIDDK, NIH ODS)."
                ],
                missingData=[
                    "Không tìm thấy tài liệu khoa học nào trong kho tri thức ACTIVE đề cập đến câu hỏi này."
                ],
                asOf=now_str,
                usage=UsageDto(
                    promptTokens=0,
                    completionTokens=0,
                    totalTokens=0,
                    latencyMs=int((time.monotonic() - start_mono) * 1000),
                ),
                generationMetadata=GenerationMetadataDto(
                    schemaVersion="1.0.0",
                    promptVersion=settings.f01_prompt_version,
                    ruleVersion=settings.f01_rule_version,
                    provider="openai",
                    model=settings.openai_model,
                ),
            )

        # 3. Check OpenAI API Key configuration
        api_key = settings.openai_api_key.strip()
        if not api_key:
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="FAILED",
                reasonCode="PROVIDER_ERROR",
                error=ErrorDto(
                    code="PROVIDER_CONFIGURATION_ERROR",
                    message="OpenAI API key is not configured in environment (OPENAI_API_KEY).",
                ),
                asOf=now_str,
            )

        # 4. Construct prompts and payload
        system_prompt = self._build_system_prompt()
        user_prompt = self._build_user_prompt(envelope, retrieval_res.chunks)

        payload = {
            "model": settings.openai_model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "response_format": {
                "type": "json_schema",
                "json_schema": OPENAI_RESPONSE_SCHEMA,
            },
            "store": settings.openai_store,
        }

        # 5. Execute OpenAI Chat Completions call with overall deadline & per-attempt retry
        try:
            async with asyncio.timeout(settings.openai_overall_timeout_seconds):
                call_result = await self._call_openai_with_retry(
                    payload=payload,
                    api_key=api_key,
                    start_mono=start_mono,
                    run_id=envelope.requestId,
                    correlation_id=envelope.correlationId,
                )
        except TimeoutError:
            latency_ms = int((time.monotonic() - start_mono) * 1000)
            logger.warning(
                "OpenAI generation overall timeout exceeded (%fs) for run %s",
                settings.openai_overall_timeout_seconds,
                envelope.requestId,
            )
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="FAILED",
                reasonCode="TIMEOUT",
                error=ErrorDto(
                    code="TIMEOUT",
                    message=f"AI model invocation exceeded overall deadline ({settings.openai_overall_timeout_seconds}s).",
                ),
                asOf=now_str,
                usage=UsageDto(promptTokens=0, completionTokens=0, totalTokens=0, latencyMs=latency_ms),
            )

        # If the caller returned an early error result (e.g. 429 quota, permanent provider failure)
        if isinstance(call_result, F01KnowledgeQueryResult):
            return call_result

        # 6. Parse and validate model response
        data, latency_ms = call_result
        return self._process_model_response(
            envelope=envelope,
            data=data,
            evidence_chunks=retrieval_res.chunks,
            latency_ms=latency_ms,
            now_str=now_str,
        )

    def _build_system_prompt(self) -> str:
        return (
            "Bạn là trợ lý giải thích kiến thức thể hình và khoa học vận động chính xác, khách quan cho học viên và huấn luyện viên.\n"
            "MỤC ĐÍCH: Cung cấp thông tin khoa học (READ_INFORMATION) dựa hoàn toàn trên các đoạn trích dẫn (Evidence Chunks) được cung cấp.\n"
            "CÁC NGUYÊN TẮC BẮT BUỘC:\n"
            "1. Chỉ trả lời dựa trên thông tin có trong các Evidence Chunks được cung cấp. Tuyệt đối không suy đoán, không thêm thắt kiến thức ngoài tài liệu.\n"
            "2. Trả lời bằng tiếng Việt chuẩn xác, mạch lạc.\n"
            "3. Trích dẫn (citations): Mỗi luận điểm chính phải được gắn trích dẫn chính xác với documentId, version, sourceId, locator (sourcePath, heading, sectionIndex) và trích dẫn nguyên văn (quoteOrExcerpt) từ đúng đoạn trích đó.\n"
            "4. Giới hạn an toàn & Y khoa:\n"
            "   - Tuyệt đối KHÔNG hướng dẫn sử dụng, liều dùng hoặc khuyến khích các chất kích thích, anabolic steroid, hoặc chất cấm trong danh mục WADA.\n"
            "   - Tuyệt đối KHÔNG kê đơn thuốc, không chẩn đoán hoặc điều trị bệnh lý y khoa chuyên sâu.\n"
            "   - Nếu câu hỏi yêu cầu nội dung vi phạm các điều trên, đặt status là 'REFUSED' và nêu rõ lý do tại refusalReason.\n"
            "5. Nếu các Evidence Chunks không đủ thông tin để trả lời câu hỏi một cách khoa học, đặt status là 'INSUFFICIENT_EVIDENCE' và liệt kê các thông tin còn thiếu tại missingData.\n"
            "6. Xuất kết quả tuân thủ nghiêm ngặt theo JSON schema đã định nghĩa."
        )

    def _build_user_prompt(
        self,
        envelope: F01InternalContextEnvelope,
        chunks: list[RetrievedChunkDto],
    ) -> str:
        parts: list[str] = [f"CÂU HỎI CỦA NGƯỜI DÙNG: {envelope.question}"]

        if envelope.exerciseContext:
            ex = envelope.exerciseContext
            parts.append(
                f"BỐI CẢNH BÀI TẬP: {ex.exerciseName} "
                f"(Chuyển động: {ex.movementPattern or 'N/A'}, Dụng cụ: {ex.equipmentRequired or 'N/A'})"
            )

        parts.append("\nTÀI LIỆU TRÍCH DẪN KHẢ DỤNG (EVIDENCE CHUNKS):")
        for i, chunk in enumerate(chunks, 1):
            content_text = chunk.content.strip() if chunk.content else chunk.snippet.strip()
            parts.append(
                f"\n--- CHUNK #{i} ---\n"
                f"documentId: {chunk.documentCode}\n"
                f"version: {chunk.version}\n"
                f"sourceId: {chunk.sourceId}\n"
                f"sourcePath: {chunk.sourcePath}\n"
                f"heading: {chunk.heading or ''}\n"
                f"sectionIndex: {chunk.sectionIndex}\n"
                f"content:\n{content_text}"
            )

        return "\n".join(parts)

    async def _call_openai_with_retry(
        self,
        payload: dict[str, Any],
        api_key: str,
        start_mono: float,
        run_id: Any,
        correlation_id: Any = None,
    ) -> tuple[dict[str, Any], int] | F01KnowledgeQueryResult:
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }
        url = settings.openai_api_url
        timeout = httpx.Timeout(
            connect=settings.openai_connect_timeout_seconds,
            read=settings.openai_read_timeout_seconds,
            write=10.0,
            pool=10.0,
        )

        max_attempts = settings.openai_max_retries + 1
        last_error_code = "MODEL_ERROR"
        last_error_message = "Failed to communicate with OpenAI provider."
        now_str = datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")

        # Use injected client if provided, else create managed client
        should_close_client = False
        client = self.http_client
        if client is None:
            client = httpx.AsyncClient(timeout=timeout)
            should_close_client = True

        try:
            for attempt in range(max_attempts):
                try:
                    resp = await client.post(url, headers=headers, json=payload, timeout=timeout)
                    latency_ms = int((time.monotonic() - start_mono) * 1000)

                    if resp.status_code == 200:
                        return resp.json(), latency_ms

                    # HTTP 429: Rate Limit or Quota Exceeded
                    if resp.status_code == 429:
                        try:
                            err_body = resp.json().get("error", {})
                            err_code = err_body.get("code")
                            err_type = err_body.get("type")
                        except Exception:
                            err_code, err_type = None, None

                        # Non-retryable quota exhaustion
                        if err_code == "insufficient_quota" or err_type == "insufficient_quota":
                            logger.error("OpenAI hard spend limit or quota exceeded (429 insufficient_quota).")
                            return F01KnowledgeQueryResult(
                                runId=run_id,
                                correlationId=correlation_id,
                                runStatus="FAILED",
                                reasonCode="PROVIDER_ERROR",
                                error=ErrorDto(
                                    code="QUOTA_EXCEEDED",
                                    message="OpenAI API quota or hard spend limit exceeded.",
                                ),
                                asOf=now_str,
                                usage=UsageDto(promptTokens=0, completionTokens=0, totalTokens=0, latencyMs=latency_ms),
                            )

                        # Retryable rate limit
                        if attempt < settings.openai_max_retries:
                            retry_after_str = resp.headers.get("Retry-After")
                            try:
                                retry_after = float(retry_after_str) if retry_after_str else 1.0
                            except (ValueError, TypeError):
                                retry_after = 1.0
                            sleep_duration = min(retry_after + random.uniform(0.1, 0.4), 4.0)
                            logger.warning(
                                "OpenAI 429 rate limit hit on attempt %d/%d; retrying after %.2fs",
                                attempt + 1,
                                max_attempts,
                                sleep_duration,
                            )
                            await asyncio.sleep(sleep_duration)
                            continue

                        return F01KnowledgeQueryResult(
                            runId=run_id,
                            correlationId=correlation_id,
                            runStatus="FAILED",
                            reasonCode="PROVIDER_ERROR",
                            error=ErrorDto(
                                code="RATE_LIMIT_EXCEEDED",
                                message="OpenAI rate limit exceeded after maximum retries.",
                            ),
                            asOf=now_str,
                            usage=UsageDto(promptTokens=0, completionTokens=0, totalTokens=0, latencyMs=latency_ms),
                        )

                    # HTTP 5xx: Server Errors (Retryable)
                    if resp.status_code in (500, 502, 503, 504):
                        if attempt < settings.openai_max_retries:
                            sleep_duration = 1.0 * (attempt + 1) + random.uniform(0.1, 0.5)
                            logger.warning(
                                "OpenAI status %d on attempt %d/%d; retrying after %.2fs",
                                resp.status_code,
                                attempt + 1,
                                max_attempts,
                                sleep_duration,
                            )
                            await asyncio.sleep(sleep_duration)
                            continue

                        return F01KnowledgeQueryResult(
                            runId=run_id,
                            correlationId=correlation_id,
                            runStatus="FAILED",
                            reasonCode="PROVIDER_ERROR",
                            error=ErrorDto(
                                code="PROVIDER_ERROR",
                                message=f"Upstream provider returned HTTP {resp.status_code}.",
                            ),
                            asOf=now_str,
                            usage=UsageDto(promptTokens=0, completionTokens=0, totalTokens=0, latencyMs=latency_ms),
                        )

                    # Other HTTP Errors (400, 401, 403, etc. - non-retryable)
                    logger.error("OpenAI non-retryable error HTTP %d: %s", resp.status_code, resp.text[:300])
                    return F01KnowledgeQueryResult(
                        runId=run_id,
                        correlationId=correlation_id,
                        runStatus="FAILED",
                        reasonCode="PROVIDER_ERROR",
                        error=ErrorDto(
                            code="PROVIDER_ERROR",
                            message=f"OpenAI API error HTTP {resp.status_code}: {resp.text[:200]}",
                        ),
                        asOf=now_str,
                        usage=UsageDto(promptTokens=0, completionTokens=0, totalTokens=0, latencyMs=latency_ms),
                    )

                except (httpx.ConnectTimeout, httpx.ReadTimeout, httpx.TimeoutException) as ex:
                    latency_ms = int((time.monotonic() - start_mono) * 1000)
                    if attempt < settings.openai_max_retries:
                        sleep_duration = 1.0 + random.uniform(0.1, 0.4)
                        logger.warning(
                            "OpenAI timeout (%s) on attempt %d/%d; retrying after %.2fs",
                            type(ex).__name__,
                            attempt + 1,
                            max_attempts,
                            sleep_duration,
                        )
                        await asyncio.sleep(sleep_duration)
                        continue

                    return F01KnowledgeQueryResult(
                        runId=run_id,
                        correlationId=correlation_id,
                        runStatus="FAILED",
                        reasonCode="TIMEOUT",
                        error=ErrorDto(
                            code="TIMEOUT",
                            message=f"AI model invocation exceeded attempt timeout: {ex}",
                        ),
                        asOf=now_str,
                        usage=UsageDto(promptTokens=0, completionTokens=0, totalTokens=0, latencyMs=latency_ms),
                    )

                except httpx.RequestError as ex:
                    latency_ms = int((time.monotonic() - start_mono) * 1000)
                    if attempt < settings.openai_max_retries:
                        sleep_duration = 1.0 + random.uniform(0.1, 0.4)
                        logger.warning(
                            "OpenAI request error (%s) on attempt %d/%d; retrying after %.2fs",
                            ex,
                            attempt + 1,
                            max_attempts,
                            sleep_duration,
                        )
                        await asyncio.sleep(sleep_duration)
                        continue

                    return F01KnowledgeQueryResult(
                        runId=run_id,
                        correlationId=correlation_id,
                        runStatus="FAILED",
                        reasonCode="PROVIDER_ERROR",
                        error=ErrorDto(
                            code="PROVIDER_ERROR",
                            message=f"Network error communicating with OpenAI: {ex}",
                        ),
                        asOf=now_str,
                        usage=UsageDto(promptTokens=0, completionTokens=0, totalTokens=0, latencyMs=latency_ms),
                    )

            # Exhausted loop
            latency_ms = int((time.monotonic() - start_mono) * 1000)
            return F01KnowledgeQueryResult(
                runId=run_id,
                correlationId=correlation_id,
                runStatus="FAILED",
                reasonCode="PROVIDER_ERROR",
                error=ErrorDto(code=last_error_code, message=last_error_message),
                asOf=now_str,
                usage=UsageDto(promptTokens=0, completionTokens=0, totalTokens=0, latencyMs=latency_ms),
            )

        finally:
            if should_close_client:
                await client.aclose()

    def _process_model_response(
        self,
        envelope: F01InternalContextEnvelope,
        data: dict[str, Any],
        evidence_chunks: list[RetrievedChunkDto],
        latency_ms: int,
        now_str: str,
    ) -> F01KnowledgeQueryResult:
        # Extract usage metrics
        usage_raw = data.get("usage", {})
        prompt_tokens = usage_raw.get("prompt_tokens", 0)
        completion_tokens = usage_raw.get("completion_tokens", 0)
        total_tokens = usage_raw.get("total_tokens", prompt_tokens + completion_tokens)
        usage = UsageDto(
            promptTokens=prompt_tokens,
            completionTokens=completion_tokens,
            totalTokens=total_tokens,
            latencyMs=latency_ms,
        )

        metadata = GenerationMetadataDto(
            schemaVersion="1.0.0",
            promptVersion=settings.f01_prompt_version,
            ruleVersion=settings.f01_rule_version,
            provider="openai",
            model=settings.openai_model,
        )

        choices = data.get("choices", [])
        if not choices:
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="FAILED",
                reasonCode="MODEL_ERROR",
                error=ErrorDto(code="EMPTY_CHOICES", message="OpenAI returned no completion choices."),
                asOf=now_str,
                usage=usage,
                generationMetadata=metadata,
            )

        choice = choices[0]
        finish_reason = choice.get("finish_reason")

        # Upstream content filter refusal
        if finish_reason == "content_filter":
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="REJECTED_BY_VALIDATOR",
                resultType="BLOCKED",
                reasonCode="RULE_BLOCKED",
                limitations=["Yêu cầu bị từ chối bởi bộ lọc an toàn của nhà cung cấp mô hình."],
                missingData=[],
                error=ErrorDto(
                    code="PROVIDER_REFUSAL",
                    message="Safety filter refusal triggered by upstream provider.",
                ),
                asOf=now_str,
                usage=usage,
                generationMetadata=metadata,
            )

        # Truncated output due to max tokens
        if finish_reason == "length":
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="FAILED",
                reasonCode="PROCESSING_ERROR",
                error=ErrorDto(
                    code="INCOMPLETE_OUTPUT",
                    message="Model generation was truncated due to token limit.",
                ),
                asOf=now_str,
                usage=usage,
                generationMetadata=metadata,
            )

        # Parse JSON content from message
        message_content = choice.get("message", {}).get("content", "")
        if not message_content:
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="FAILED",
                reasonCode="MODEL_ERROR",
                error=ErrorDto(code="EMPTY_CONTENT", message="OpenAI returned empty message content."),
                asOf=now_str,
                usage=usage,
                generationMetadata=metadata,
            )

        try:
            model_output = json.loads(message_content)
        except json.JSONDecodeError as ex:
            logger.warning("Failed to parse structured model output as JSON: %s", ex)
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="REJECTED_BY_VALIDATOR",
                reasonCode="VALIDATION_FAILED",
                error=ErrorDto(
                    code="VALIDATION_FAILED",
                    message=f"Model output violates JSON schema: {ex}",
                ),
                asOf=now_str,
                usage=usage,
                generationMetadata=metadata,
            )

        status_val = model_output.get("status")

        # Branch 1: Model Refusal (Safety / Policy Guardrail)
        if status_val == "REFUSED":
            refusal_reason = model_output.get("refusalReason") or "Nội dung câu hỏi chạm ngưỡng chính sách an toàn thể chất hoặc y khoa."
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="REJECTED_BY_VALIDATOR",
                resultType="BLOCKED",
                reasonCode="RULE_BLOCKED",
                limitations=[refusal_reason],
                missingData=[],
                error=ErrorDto(
                    code="POLICY_GUARDRAIL_TRIGGERED",
                    message=refusal_reason,
                ),
                asOf=now_str,
                usage=usage,
                generationMetadata=metadata,
            )

        # Branch 2: Insufficient Evidence
        if status_val == "INSUFFICIENT_EVIDENCE":
            missing = model_output.get("missingData") or [
                "Tài liệu tri thức hiện tại chưa đủ thông tin chuyên sâu để giải thích câu hỏi này."
            ]
            limitations = model_output.get("limitations") or DEFAULT_LIMITATIONS
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="SUCCEEDED",
                resultType="INSUFFICIENT_DATA",
                reasonCode="NO_RELEVANT_EVIDENCE",
                limitations=limitations,
                missingData=missing,
                asOf=now_str,
                usage=usage,
                generationMetadata=metadata,
            )

        # Branch 3: Answered -> INFORMATION with Strict Grounding Validation
        if status_val == "ANSWERED":
            answer_text = model_output.get("answer", "").strip()
            raw_citations = model_output.get("citations", [])

            if not answer_text:
                return F01KnowledgeQueryResult(
                    runId=envelope.requestId,
                    correlationId=envelope.correlationId,
                    runStatus="REJECTED_BY_VALIDATOR",
                    reasonCode="VALIDATION_FAILED",
                    error=ErrorDto(
                        code="VALIDATION_FAILED",
                        message="Model marked status as ANSWERED but provided empty answer text.",
                    ),
                    asOf=now_str,
                    usage=usage,
                    generationMetadata=metadata,
                )

            if not raw_citations:
                # F01 requires citations for INFORMATION
                return F01KnowledgeQueryResult(
                    runId=envelope.requestId,
                    correlationId=envelope.correlationId,
                    runStatus="REJECTED_BY_VALIDATOR",
                    reasonCode="VALIDATION_FAILED",
                    error=ErrorDto(
                        code="UNVERIFIED_CITATION",
                        message="Answered output must contain at least one valid grounded citation.",
                    ),
                    asOf=now_str,
                    usage=usage,
                    generationMetadata=metadata,
                )

            validated_citations: list[CitationDto] = []
            for cit in raw_citations:
                doc_id = cit.get("documentId", "")
                ver = cit.get("version", "")
                src_id = cit.get("sourceId", "")
                locator_raw = cit.get("locator", {})
                source_path = locator_raw.get("sourcePath", "")
                sec_idx = locator_raw.get("sectionIndex", 0)
                heading = locator_raw.get("heading")
                quote = cit.get("quoteOrExcerpt")

                # Locate matching chunk in evidence
                matched_chunk: RetrievedChunkDto | None = None
                for c in evidence_chunks:
                    if (
                        c.documentCode == doc_id
                        and c.version == ver
                        and c.sourceId == src_id
                        and c.sourcePath == source_path
                        and c.sectionIndex == sec_idx
                    ):
                        matched_chunk = c
                        break

                if matched_chunk is None:
                    logger.warning(
                        "Model citation hallucinated: doc=%s, ver=%s, src=%s, path=%s, sec=%d",
                        doc_id,
                        ver,
                        src_id,
                        source_path,
                        sec_idx,
                    )
                    return F01KnowledgeQueryResult(
                        runId=envelope.requestId,
                        correlationId=envelope.correlationId,
                        runStatus="REJECTED_BY_VALIDATOR",
                        reasonCode="VALIDATION_FAILED",
                        error=ErrorDto(
                            code="UNVERIFIED_CITATION",
                            message=(
                                f"Model generated citation ({doc_id} v{ver}) not present in retrieved evidence chunks."
                            ),
                        ),
                        asOf=now_str,
                        usage=usage,
                        generationMetadata=metadata,
                    )

                # Verify quote/excerpt against chunk content if quote is provided
                if quote and quote.strip():
                    clean_quote = quote.strip()
                    if clean_quote.endswith("..."):
                        clean_quote = clean_quote[:-3].strip()

                    norm_quote = normalize_text_for_comparison(clean_quote)
                    chunk_text = f"{matched_chunk.content or ''} {matched_chunk.snippet or ''}"
                    norm_content = normalize_text_for_comparison(chunk_text)

                    if norm_quote and norm_quote not in norm_content:
                        logger.warning(
                            "Citation quote not found in chunk content: quote='%s', chunk='%s'",
                            clean_quote[:80],
                            chunk_text[:80],
                        )
                        return F01KnowledgeQueryResult(
                            runId=envelope.requestId,
                            correlationId=envelope.correlationId,
                            runStatus="REJECTED_BY_VALIDATOR",
                            reasonCode="VALIDATION_FAILED",
                            error=ErrorDto(
                                code="UNVERIFIED_CITATION",
                                message="Citation quoteOrExcerpt is not supported by the retrieved chunk content.",
                            ),
                            asOf=now_str,
                            usage=usage,
                            generationMetadata=metadata,
                        )

                try:
                    citation_dto = CitationDto(
                        documentId=doc_id,
                        version=ver,
                        sourceId=src_id,
                        locator=LocatorDto(
                            sourcePath=source_path,
                            heading=heading if heading else None,
                            sectionIndex=sec_idx,
                        ),
                        quoteOrExcerpt=quote if quote and quote.strip() else None,
                    )
                    validated_citations.append(citation_dto)
                except Exception as ex:
                    logger.warning("Citation failed schema validation: %s", ex)
                    return F01KnowledgeQueryResult(
                        runId=envelope.requestId,
                        correlationId=envelope.correlationId,
                        runStatus="REJECTED_BY_VALIDATOR",
                        reasonCode="VALIDATION_FAILED",
                        error=ErrorDto(
                            code="VALIDATION_FAILED",
                            message=f"Citation schema invalid: {ex}",
                        ),
                        asOf=now_str,
                        usage=usage,
                        generationMetadata=metadata,
                    )

            limitations = model_output.get("limitations") or DEFAULT_LIMITATIONS
            missing = model_output.get("missingData") or []

            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="SUCCEEDED",
                resultType="INFORMATION",
                reasonCode="NONE",
                answer=answer_text,
                citations=validated_citations,
                limitations=limitations,
                missingData=missing,
                asOf=now_str,
                usage=usage,
                generationMetadata=metadata,
            )

        # Unknown or unexpected status
        return F01KnowledgeQueryResult(
            runId=envelope.requestId,
            correlationId=envelope.correlationId,
            runStatus="REJECTED_BY_VALIDATOR",
            reasonCode="VALIDATION_FAILED",
            error=ErrorDto(
                code="VALIDATION_FAILED",
                message=f"Model output contains unexpected status: {status_val}",
            ),
            asOf=now_str,
            usage=usage,
            generationMetadata=metadata,
        )



