package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiTimeoutException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F01AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;

/**
 * Simulated test double for the internal FastAPI AI Assistance Service.
 * Implements F01 contract behavior without invoking real external providers or embeddings.
 */
public class SimulatedF01AiServiceClient implements F01AiServiceClient {

    private Function<F01InternalContextEnvelope, F01KnowledgeQueryResponse> customBehavior;

    public void setCustomBehavior(Function<F01InternalContextEnvelope, F01KnowledgeQueryResponse> customBehavior) {
        this.customBehavior = customBehavior;
    }

    public void reset() {
        this.customBehavior = null;
    }

    @Override
    public F01KnowledgeQueryResponse queryKnowledge(F01InternalContextEnvelope envelope) {
        if (customBehavior != null) {
            return customBehavior.apply(envelope);
        }

        String question = envelope.question().toLowerCase();
        String asOf = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

        // 1. Simulated timeout
        if (question.contains("trigger_timeout")) {
            throw new AiTimeoutException("AI model invocation exceeded deadline of 10000ms.");
        }

        // 2. Simulated provider error (502)
        if (question.contains("trigger_provider_error")) {
            throw new AiProviderException("Upstream AI model provider returned HTTP 502.");
        }

        // 3. Simulated model output validation failure
        if (question.contains("trigger_validation_failure")) {
            throw new AiOutputValidationException("Model output failed structural validation (corrupted tokens).");
        }

        // 4. Policy blocked (RULE_BLOCKED) -> REJECTED_BY_VALIDATOR + BLOCKED
        if (question.contains("steroid") || question.contains("doping") || question.contains("trigger_blocked")) {
            return new F01KnowledgeQueryResponse(
                    envelope.requestId(),
                    envelope.correlationId(),
                    "REJECTED_BY_VALIDATOR",
                    "BLOCKED",
                    "RULE_BLOCKED",
                    null,
                    null,
                    List.of("Chính sách an toàn hệ thống cấm hướng dẫn sử dụng các chất kích thích nằm trong danh mục cấm WADA hoặc tư vấn điều trị y khoa."),
                    List.of(),
                    asOf,
                    new F01KnowledgeQueryResponse.UsageDto(100, 20, 120, 150),
                    new F01KnowledgeQueryResponse.ErrorDto("POLICY_GUARDRAIL_TRIGGERED", "Câu hỏi chạm ngưỡng chính sách an toàn dược lý / doping."),
                    new F01KnowledgeQueryResponse.GenerationMetadataDto("1.0.0", "v1.0", "guardrail-v1", "simulated", "simulated-guardrail-v1", null, null)
            );
        }

        // 5. Insufficient data -> SUCCEEDED + INSUFFICIENT_DATA
        if (envelope.knowledgeScope() == null
                || envelope.knowledgeScope().allowedSourceIds() == null
                || envelope.knowledgeScope().allowedSourceIds().isEmpty()
                || question.contains("trigger_insufficient_data")) {
            return new F01KnowledgeQueryResponse(
                    envelope.requestId(),
                    envelope.correlationId(),
                    "SUCCEEDED",
                    "INSUFFICIENT_DATA",
                    "NO_RELEVANT_EVIDENCE",
                    null,
                    null,
                    List.of("Hệ thống chỉ giải thích dựa trên các nguồn tri thức đã được kiểm duyệt (ISSN, WHO, NIDDK, NIH ODS)."),
                    List.of("Không tìm thấy tài liệu khoa học nào trong kho tri thức ACTIVE đề cập đến câu hỏi này."),
                    asOf,
                    new F01KnowledgeQueryResponse.UsageDto(250, 50, 300, 320),
                    null,
                    new F01KnowledgeQueryResponse.GenerationMetadataDto("1.0.0", "v1.0", "rule-v1", "simulated", "simulated-f01-v1", null, null)
            );
        }

        // 6. Normal INFORMATION result
        F01KnowledgeQueryResponse.LocatorDto locator = new F01KnowledgeQueryResponse.LocatorDto(
                "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                "Protein, leucine và EAA trong từng lần ăn",
                List.of(1, 3),
                0
        );

        F01KnowledgeQueryResponse.CitationDto citation = new F01KnowledgeQueryResponse.CitationDto(
                "ISSN-PROTEIN-EXERCISE-2017-VI-04",
                "1.0.0",
                "ISSN-PROTEIN-EXERCISE-2017",
                locator,
                "Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa để tối đa hóa MPS."
        );

        return new F01KnowledgeQueryResponse(
                envelope.requestId(),
                envelope.correlationId(),
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Thông tin giải thích nguyên tắc vận động và bổ sung dinh dưỡng dựa trên tài liệu ISSN 2017 và WHO 2020.",
                List.of(citation),
                List.of(
                        "Khuyến nghị áp dụng cho người trưởng thành khỏe mạnh tham gia tập luyện kháng lực.",
                        "Không thay thế tư vấn y khoa hoặc dinh dưỡng lâm sàng cho người có bệnh lý thận."
                ),
                List.of(),
                asOf,
                new F01KnowledgeQueryResponse.UsageDto(520, 160, 680, 850),
                null,
                new F01KnowledgeQueryResponse.GenerationMetadataDto("1.0.0", "v1.0", "rule-v1", "simulated", "simulated-f01-v1", null, null)
        );
    }
}
