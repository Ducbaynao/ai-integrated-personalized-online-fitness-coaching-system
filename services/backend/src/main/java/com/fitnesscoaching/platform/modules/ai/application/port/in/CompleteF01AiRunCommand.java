package com.fitnesscoaching.platform.modules.ai.application.port.in;

import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;

import java.time.Instant;
import java.util.UUID;

public record CompleteF01AiRunCommand(
        UUID runId,
        AiRunStatus terminalStatus,
        String outputPayload,
        String errorCode,
        String errorMessage,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer latencyMs,
        Instant completedAt
) {
}
