package com.fitnesscoaching.platform.modules.ai.application.port.out;

import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AiRunRepository {

    AiRun create(AiRun run);

    Optional<AiRun> findById(UUID id);

    /**
     * Finds run by ID and locks row for update in caller transaction.
     */
    Optional<AiRun> findByIdForUpdate(UUID id);

    /**
     * Atomically transitions run status from expected currentStatus to newStatus,
     * protecting against invalid or duplicate terminal state overwrites.
     * Returns true if transition succeeded, false if current status did not match or run is already terminal.
     */
    boolean transitionStatus(
            UUID runId,
            AiRunStatus expectedStatus,
            AiRunStatus newStatus,
            Instant startedAt,
            Instant completedAt
    );

    /**
     * Records terminal completion of an AI Run (SUCCEEDED, FAILED, or REJECTED_BY_VALIDATOR)
     * along with sanitized error details, output payload, usage and completion time.
     * Enforces that terminal runs cannot be overwritten.
     */
    boolean completeRun(
            UUID runId,
            AiRunStatus terminalStatus,
            String outputPayload,
            String errorCode,
            String errorMessage,
            Integer inputTokens,
            Integer outputTokens,
            Integer latencyMs,
            Instant completedAt
    );
}
