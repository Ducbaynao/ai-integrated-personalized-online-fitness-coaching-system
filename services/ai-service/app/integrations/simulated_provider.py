import asyncio
from datetime import UTC, datetime

from fastapi import HTTPException, status

from app.core.config import settings
from app.integrations.provider_interface import F01KnowledgeProvider
from app.rag.retriever import KnowledgeRetriever
from app.schemas.f01_schemas import (
    CitationDto,
    ErrorDto,
    F01InternalContextEnvelope,
    F01KnowledgeQueryResult,
    GenerationMetadataDto,
    LocatorDto,
    UsageDto,
)


class SimulatedF01KnowledgeProvider(F01KnowledgeProvider):
    """Deterministic simulated provider for F01 General Fitness Knowledge inquiries.

    Generates contract-compliant responses backed by real knowledge retrieval from the backend.
    """

    def __init__(self, retriever: KnowledgeRetriever | None = None):
        self.retriever = retriever or KnowledgeRetriever()

    async def query(self, envelope: F01InternalContextEnvelope) -> F01KnowledgeQueryResult:
        if not settings.f01_mock_enabled:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Mock provider is disabled in production mode.",
            )

        question = envelope.question.lower()
        now_str = datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")

        # 1. Trigger timeout simulation
        if "trigger_timeout" in question:
            await asyncio.sleep(0.01)
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="FAILED",
                reasonCode="TIMEOUT",
                error=ErrorDto(code="TIMEOUT", message="AI model invocation exceeded deadline."),
                asOf=now_str,
            )

        # 2. Trigger provider error simulation (502/MODEL_ERROR)
        if "trigger_provider_error" in question:
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="FAILED",
                reasonCode="MODEL_ERROR",
                error=ErrorDto(
                    code="MODEL_ERROR",
                    message="Upstream AI provider encountered an internal service failure.",
                ),
                asOf=now_str,
            )

        # 3. Trigger validation failure simulation
        if "trigger_validation_failure" in question:
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="REJECTED_BY_VALIDATOR",
                reasonCode="VALIDATION_FAILED",
                error=ErrorDto(
                    code="VALIDATION_FAILED",
                    message="Generated output violates citation grounding invariants.",
                ),
                asOf=now_str,
            )

        # 4. Policy blocked (RULE_BLOCKED -> REJECTED_BY_VALIDATOR + BLOCKED)
        if any(w in question for w in ["steroid", "doping", "trigger_blocked"]):
            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="REJECTED_BY_VALIDATOR",
                resultType="BLOCKED",
                reasonCode="RULE_BLOCKED",
                limitations=[
                    "Chính sách an toàn hệ thống cấm hướng dẫn sử dụng các chất kích thích nằm trong danh mục cấm WADA hoặc tư vấn điều trị y khoa."
                ],
                missingData=[],
                asOf=now_str,
                error=ErrorDto(
                    code="POLICY_GUARDRAIL_TRIGGERED",
                    message="Câu hỏi chạm ngưỡng chính sách an toàn dược lý / doping.",
                ),
                usage=UsageDto(promptTokens=120, completionTokens=30, totalTokens=150, latencyMs=85),
                generationMetadata=GenerationMetadataDto(
                    schemaVersion="1.0.0",
                    promptVersion=settings.f01_prompt_version,
                    ruleVersion=settings.f01_rule_version,
                    provider=settings.f01_provider,
                    model="simulated-guardrail-v1",
                ),
            )

        # 5. Insufficient data trigger or empty allowed sources
        if not envelope.knowledgeScope.allowedSourceIds or "trigger_insufficient_data" in question:
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
                usage=UsageDto(promptTokens=250, completionTokens=50, totalTokens=300, latencyMs=120),
                generationMetadata=GenerationMetadataDto(
                    schemaVersion="1.0.0",
                    promptVersion=settings.f01_prompt_version,
                    ruleVersion=settings.f01_rule_version,
                    provider=settings.f01_provider,
                    model=settings.f01_model,
                ),
            )

        # 6. Real Retrieval from Backend
        retrieval_res = await self.retriever.retrieve(
            query=envelope.question,
            allowed_source_ids=envelope.knowledgeScope.allowedSourceIds,
            limit=3,
            correlation_id=str(envelope.correlationId) if envelope.correlationId else None,
        )

        if retrieval_res.server_responded:
            # Backend responded to the retrieval query
            if not retrieval_res.chunks:
                # No active chunks found in the corpus for the question -> INSUFFICIENT_DATA
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
                    usage=UsageDto(promptTokens=280, completionTokens=45, totalTokens=325, latencyMs=130),
                    generationMetadata=GenerationMetadataDto(
                        schemaVersion="1.0.0",
                        promptVersion=settings.f01_prompt_version,
                        ruleVersion=settings.f01_rule_version,
                        provider=settings.f01_provider,
                        model=settings.f01_model,
                    ),
                )

            # Chunks were retrieved: construct genuine citations from retrieved chunks
            citations: list[CitationDto] = []
            for chunk in retrieval_res.chunks[:2]:
                citations.append(
                    CitationDto(
                        documentId=chunk.documentCode,
                        version=chunk.version,
                        sourceId=chunk.sourceId,
                        locator=LocatorDto(
                            sourcePath=chunk.sourcePath,
                            heading=chunk.heading if chunk.heading else None,
                            sectionIndex=chunk.sectionIndex,
                        ),
                        quoteOrExcerpt=chunk.snippet[:200] if chunk.snippet else None,
                    )
                )

            top_chunk = retrieval_res.chunks[0]
            answer = (
                f"Thông tin giải thích dựa trên tài liệu {top_chunk.documentCode} (phiên bản {top_chunk.version}): "
                f"{top_chunk.snippet} "
                "Tập luyện thể chất đúng kỹ thuật và phân bổ dinh dưỡng hợp lý theo khuyến nghị giúp tối ưu hóa sức khỏe và hiệu suất."
            )

            return F01KnowledgeQueryResult(
                runId=envelope.requestId,
                correlationId=envelope.correlationId,
                runStatus="SUCCEEDED",
                resultType="INFORMATION",
                reasonCode="NONE",
                answer=answer,
                citations=citations,
                limitations=[
                    "Khuyến nghị áp dụng cho người trưởng thành khỏe mạnh tham gia tập luyện thể chất.",
                    "Không thay thế tư vấn y khoa hoặc điều trị bệnh lý chuyên sâu.",
                ],
                missingData=[],
                asOf=now_str,
                usage=UsageDto(
                    promptTokens=450,
                    completionTokens=140,
                    totalTokens=590,
                    latencyMs=220,
                ),
                generationMetadata=GenerationMetadataDto(
                    schemaVersion="1.0.0",
                    promptVersion=settings.f01_prompt_version,
                    ruleVersion=settings.f01_rule_version,
                    provider=settings.f01_provider,
                    model=settings.f01_model,
                ),
            )

        if settings.backend_internal_retrieval_enabled and not retrieval_res.server_responded:
            # When backend internal retrieval is explicitly enabled, failure to reach backend or
            # timeout MUST NOT silently fall back to a fixture citation.
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

        # 7. Fallback for standalone test double when backend is unreachable (e.g. isolated pytest)
        citation = CitationDto(
            documentId="ISSN-PROTEIN-EXERCISE-2017-VI-04",
            version="1.0.0",
            sourceId="ISSN-PROTEIN-EXERCISE-2017",
            locator=LocatorDto(
                sourcePath="data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                heading="Protein, leucine và EAA trong từng lần ăn",
                pageRange=[1, 3],
                sectionIndex=0,
            ),
            quoteOrExcerpt="Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa MPS.",
        )

        return F01KnowledgeQueryResult(
            runId=envelope.requestId,
            correlationId=envelope.correlationId,
            runStatus="SUCCEEDED",
            resultType="INFORMATION",
            reasonCode="NONE",
            answer=(
                "Thông tin giải thích nguyên tắc vận động và bổ sung dinh dưỡng dựa trên tài liệu "
                "ISSN 2017 và WHO 2020. Để tối ưu hóa tổng hợp protein cơ bắp (MPS), nên phân bổ lượng "
                "protein đều qua các bữa ăn trong ngày kèm theo tập luyện kháng lực đều đặn."
            ),
            citations=[citation],
            limitations=[
                "Khuyến nghị áp dụng cho người trưởng thành khỏe mạnh tham gia tập luyện kháng lực.",
                "Không thay thế tư vấn y khoa hoặc dinh dưỡng lâm sàng cho người có bệnh lý thận.",
            ],
            missingData=[],
            asOf=now_str,
            usage=UsageDto(
                promptTokens=520,
                completionTokens=160,
                totalTokens=680,
                latencyMs=250,
            ),
            generationMetadata=GenerationMetadataDto(
                schemaVersion="1.0.0",
                promptVersion=settings.f01_prompt_version,
                ruleVersion=settings.f01_rule_version,
                provider=settings.f01_provider,
                model=settings.f01_model,
            ),
        )
