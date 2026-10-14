package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;

import java.time.Instant;
import java.util.UUID;

public record AiRunResponse(
        UUID runId,
        AiRunStatus runStatus,
        String requestType,
        UUID correlationId,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
    public static AiRunResponse fromDomain(AiRun run) {
        return new AiRunResponse(
                run.id(),
                run.status(),
                run.requestType(),
                run.correlationId(),
                run.queuedAt(),
                run.startedAt(),
                run.completedAt(),
                run.createdAt()
        );
    }
}
