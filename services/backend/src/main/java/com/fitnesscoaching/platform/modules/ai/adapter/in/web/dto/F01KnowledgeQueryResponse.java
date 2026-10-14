package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Structured INFORMATION result for general fitness knowledge inquiries (F01).
 * Conforms to contracts/ai-schemas/f01-knowledge-query-result.schema.json
 * and contracts/openapi/openapi.yaml#/components/schemas/F01KnowledgeQueryResponse.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record F01KnowledgeQueryResponse(
        UUID runId,
        UUID correlationId,
        String runStatus,
        String resultType,
        String reasonCode,
        String answer,
        List<CitationDto> citations,
        List<String> limitations,
        List<String> missingData,
        String asOf,
        UsageDto usage,
        ErrorDto error,
        GenerationMetadataDto generationMetadata
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CitationDto(
            String documentId,
            String version,
            String sourceId,
            LocatorDto locator,
            String quoteOrExcerpt
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LocatorDto(
            String sourcePath,
            String heading,
            List<Integer> pageRange,
            Integer sectionIndex
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageDto(
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Integer latencyMs
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDto(
            String code,
            String message
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GenerationMetadataDto(
            String schemaVersion,
            String promptVersion,
            String ruleVersion,
            String provider,
            String model,
            String embeddingModelSpace,
            String indexVersion
    ) {
    }
}
