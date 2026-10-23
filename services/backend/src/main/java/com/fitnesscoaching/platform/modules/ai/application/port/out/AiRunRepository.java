package com.fitnesscoaching.platform.modules.ai.application.port.out;

import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;

import java.time.Instant;
import java.util.List;
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

    /**
     * Finds stale/orphaned non-terminal run IDs (status QUEUED or RUNNING) whose active timestamp
     * is older than the specified cutoff, locking them with FOR UPDATE SKIP LOCKED.
     */
    List<UUID> findStaleRunIds(Instant cutoff, int batchSize);

    /**
     * Reconciles a stale/orphaned AI Run by transitioning it to FAILED with error code and message.
     * Enforces conditional status check WHERE status IN ('QUEUED', 'RUNNING') to prevent
     * overwriting valid terminal completions.
     * Returns true if transition succeeded, false if run was already completed or not in non-terminal state.
     */
    boolean reconcileStaleRun(UUID runId, String errorCode, String errorMessage, Instant completedAt);
}
