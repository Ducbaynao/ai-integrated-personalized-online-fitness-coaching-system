package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;
import com.fitnesscoaching.platform.modules.ai.application.service.F01RuntimeOutputValidator;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("F01RuntimeOutputValidator Invariant Tests")
class F01RuntimeOutputValidatorTest {

    private F01RuntimeOutputValidator validator;
    private F01InternalContextEnvelope envelope;

    private static final UUID REQUEST_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CORRELATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @BeforeEach
    void setUp() {
        validator = new F01RuntimeOutputValidator();
        envelope = new F01InternalContextEnvelope(
                REQUEST_ID,
                "F01_GENERAL_KNOWLEDGE",
                "1.0.0",
                CORRELATION_ID,
                new F01InternalContextEnvelope.ActorContext(UUID.randomUUID(), "STUDENT"),
                "GENERAL_FITNESS_EXPLANATION",
                List.of("READ_INFORMATION"),
                "2026-10-10T12:00:00Z",
                "Phân bổ protein như thế nào?",
                "vi",
                null,
                new F01InternalContextEnvelope.KnowledgeScopeContext("ACTIVE", List.of("ISSN-PROTEIN-EXERCISE-2017"))
        );
    }

    private F01KnowledgeQueryResponse.CitationDto validCitation(String sourceId, String docId, String path) {
        return new F01KnowledgeQueryResponse.CitationDto(
                docId,
                "1.0.0",
                sourceId,
                new F01KnowledgeQueryResponse.LocatorDto(path, "Heading", List.of(1, 2), 0),
                "Quote"
        );
    }

    @Test
    @DisplayName("1. Valid INFORMATION output with allowed scope passes")
    void validInformationPasses() {
        F01KnowledgeQueryResponse resp = new F01KnowledgeQueryResponse(
                REQUEST_ID,
                CORRELATION_ID,
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Câu trả lời giải thích dựa trên ISSN.",
                List.of(validCitation("ISSN-PROTEIN-EXERCISE-2017", "ISSN-PROTEIN-EXERCISE-2017-VI-01", "data/knowledge/curated/01.vi.md")),
                List.of("Healthy adults"),
                List.of(),
                "2026-10-10T12:00:00Z",
                new F01KnowledgeQueryResponse.UsageDto(100, 50, 150, 100),
                null,
                null
        );

        F01RuntimeOutputValidator.ValidationResult result = validator.validate(resp, envelope);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("2. Rejects INFORMATION with citation outside allowedSourceIds scope")
    void rejectsCitationOutsideScope() {
        // Citation source is WHO-PA-SB-2020, but envelope only allows ISSN-PROTEIN-EXERCISE-2017
        F01KnowledgeQueryResponse resp = new F01KnowledgeQueryResponse(
                REQUEST_ID,
                CORRELATION_ID,
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Câu trả lời giải thích.",
                List.of(validCitation("WHO-PA-SB-2020", "WHO-PA-SB-2020-VI-01", "data/knowledge/curated/who/01.vi.md")),
                List.of(),
                List.of(),
                "2026-10-10T12:00:00Z",
                null,
                null,
                null
        );

        F01RuntimeOutputValidator.ValidationResult result = validator.validate(resp, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("outside envelope allowedSourceIds scope");
    }

    @Test
    @DisplayName("3. Rejects citation with path traversal attack")
    void rejectsCitationWithPathTraversal() {
        F01KnowledgeQueryResponse resp = new F01KnowledgeQueryResponse(
                REQUEST_ID,
                CORRELATION_ID,
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Câu trả lời giải thích.",
                List.of(validCitation("ISSN-PROTEIN-EXERCISE-2017", "ISSN-PROTEIN-EXERCISE-2017-VI-01", "../../../etc/passwd")),
                List.of(),
                List.of(),
                "2026-10-10T12:00:00Z",
                null,
                null,
                null
        );

        F01RuntimeOutputValidator.ValidationResult result = validator.validate(resp, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("escapes allowed relative structure");
    }

    @Test
    @DisplayName("4. Rejects RFC 3339 leap second (second=60)")
    void rejectsLeapSecondAsOf() {
        F01KnowledgeQueryResponse resp = new F01KnowledgeQueryResponse(
                REQUEST_ID,
                CORRELATION_ID,
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Câu trả lời.",
                List.of(validCitation("ISSN-PROTEIN-EXERCISE-2017", "ISSN-PROTEIN-EXERCISE-2017-VI-01", "data/01.vi.md")),
                List.of(),
                List.of(),
                "2026-10-10T12:00:60Z", // Second 60
                null,
                null,
                null
        );

        F01RuntimeOutputValidator.ValidationResult result = validator.validate(resp, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("RFC 3339");
    }

    @Test
    @DisplayName("5. Rejects INFORMATION carrying error payload")
    void rejectsInformationWithErrorPayload() {
        F01KnowledgeQueryResponse resp = new F01KnowledgeQueryResponse(
                REQUEST_ID,
                CORRELATION_ID,
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Câu trả lời.",
                List.of(validCitation("ISSN-PROTEIN-EXERCISE-2017", "ISSN-PROTEIN-EXERCISE-2017-VI-01", "data/01.vi.md")),
                List.of(),
                List.of(),
                "2026-10-10T12:00:00Z",
                null,
                new F01KnowledgeQueryResponse.ErrorDto("ERROR_CODE", "Error msg"),
                null
        );

        F01RuntimeOutputValidator.ValidationResult result = validator.validate(resp, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("cannot contain error diagnostics");
    }

    @Test
    @DisplayName("6. Rejects BLOCKED carrying answer text")
    void rejectsBlockedWithAnswer() {
        F01KnowledgeQueryResponse resp = new F01KnowledgeQueryResponse(
                REQUEST_ID,
                CORRELATION_ID,
                "REJECTED_BY_VALIDATOR",
                "BLOCKED",
                "RULE_BLOCKED",
                "Không nên dùng chất này nhưng vẫn giải thích...",
                null,
                List.of("Cấm WADA"),
                List.of(),
                "2026-10-10T12:00:00Z",
                null,
                new F01KnowledgeQueryResponse.ErrorDto("POLICY_GUARDRAIL_TRIGGERED", "Blocked"),
                null
        );

        F01RuntimeOutputValidator.ValidationResult result = validator.validate(resp, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("must not contain an answer");
    }

    @Test
    @DisplayName("7. Accepts valid nested relative source paths while rejecting root-relative or drive-prefixed paths")
    void testSourcePathBoundaries() {
        // Valid relative paths containing slashes must pass
        for (String validPath : List.of(
                "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
                "docs/references/guide.md",
                "article.md"
        )) {
            F01KnowledgeQueryResponse resp = new F01KnowledgeQueryResponse(
                    REQUEST_ID,
                    CORRELATION_ID,
                    "SUCCEEDED",
                    "INFORMATION",
                    "NONE",
                    "Câu trả lời.",
                    List.of(validCitation("ISSN-PROTEIN-EXERCISE-2017", "ISSN-PROTEIN-EXERCISE-2017-VI-01", validPath)),
                    List.of(),
                    List.of(),
                    "2026-10-10T12:00:00Z",
                    null,
                    null,
                    null
            );
            F01RuntimeOutputValidator.ValidationResult result = validator.validate(resp, envelope);
            assertThat(result.isValid()).isTrue();
        }

        // Invalid paths (traversal, absolute with leading slash, drive letter, backslashes) must fail
        for (String invalidPath : List.of(
                "/absolute/data/01.md",
                "../secrets/key.pem",
                "data/../../outside.md",
                "C:/system32/cmd.exe",
                "data\\win\\path.md"
        )) {
            F01KnowledgeQueryResponse resp = new F01KnowledgeQueryResponse(
                    REQUEST_ID,
                    CORRELATION_ID,
                    "SUCCEEDED",
                    "INFORMATION",
                    "NONE",
                    "Câu trả lời.",
                    List.of(validCitation("ISSN-PROTEIN-EXERCISE-2017", "ISSN-PROTEIN-EXERCISE-2017-VI-01", invalidPath)),
                    List.of(),
                    List.of(),
                    "2026-10-10T12:00:00Z",
                    null,
                    null,
                    null
            );
            F01RuntimeOutputValidator.ValidationResult result = validator.validate(resp, envelope);
            assertThat(result.isValid()).isFalse();
            assertThat(result.failureReason()).contains("escapes allowed relative structure");
        }
    }

    @Test
    @DisplayName("8. Rejects INSUFFICIENT_DATA when both limitations and missingData are missing/empty")
    void rejectsInsufficientDataWithoutLimitationsOrMissingData() {
        F01KnowledgeQueryResponse resp = new F01KnowledgeQueryResponse(
                REQUEST_ID,
                CORRELATION_ID,
                "SUCCEEDED",
                "INSUFFICIENT_DATA",
                "MISSING_REQUIRED_CONTEXT",
                null,
                null,
                List.of(), // empty limitations
                List.of(), // empty missingData
                "2026-10-10T12:00:00Z",
                null,
                null,
                null
        );

        F01RuntimeOutputValidator.ValidationResult result = validator.validate(resp, envelope);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("INSUFFICIENT_DATA requires limitations or missingData");
    }
}
