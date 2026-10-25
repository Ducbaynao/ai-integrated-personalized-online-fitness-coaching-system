package com.fitnesscoaching.platform.modules.ai.application.port.in;

import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AdminAiRunQueryUseCase {

    record AdminAiRunSummaryDto(
            UUID id,
            String requestType,
            String status,
            String purpose,
            UUID correlationId,
            Instant queuedAt,
            Instant startedAt,
            Instant completedAt,
            Integer latencyMs,
            String errorCode,
            String errorMessage,
            Integer inputTokenCount,
            Integer outputTokenCount,
            Integer totalTokenCount,
            boolean evaluationOnly,
            Instant createdAt
    ) {}

    record AdminAiValidationDto(
            UUID id,
            String validatorCode,
            boolean passed,
            String severity,
            String details,
            Instant createdAt
    ) {}

    record AdminAiRunChunkDto(
            UUID chunkId,
            int rank,
            BigDecimal similarityScore
    ) {}

    record AdminAiRunDetailDto(
            UUID id,
            String requestType,
            String status,
            String purpose,
            UUID correlationId,
            Instant queuedAt,
            Instant startedAt,
            Instant completedAt,
            Integer latencyMs,
            String errorCode,
            String errorMessage,
            Integer inputTokenCount,
            Integer outputTokenCount,
            Integer totalTokenCount,
            boolean evaluationOnly,
            Instant createdAt,
            List<AdminAiValidationDto> validations,
            List<AdminAiRunChunkDto> citations
    ) {}

    record AdminAiRunPageDto(
            List<AdminAiRunSummaryDto> items,
            long total,
            int page,
            int size,
            int totalPages
    ) {}

    AdminAiRunPageDto listRuns(UUID actorId, int page, int size, AiRunStatus status, String requestType);

    AdminAiRunDetailDto getRunDetail(UUID actorId, UUID runId);
}
