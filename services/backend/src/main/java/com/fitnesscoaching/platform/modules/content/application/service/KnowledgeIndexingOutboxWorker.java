package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeProcessingRunRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeVersionRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeProcessingRun;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Worker component executing single knowledge indexing outbox runs across strict transaction boundaries:
 * Boundary 1: Atomic Claim commits immediately in a dedicated REQUIRES_NEW transaction.
 * Boundary 2: Execution runs with caller transaction suspended (PROPAGATION_NOT_SUPPORTED).
 *             The worker does not participate in the caller transaction during execution;
 *             suspending the transaction does not automatically release any existing locks or connections held by the caller.
 * Boundary 3: Completion or failure commits in a dedicated REQUIRES_NEW transaction guarded by claim fencing.
 */
@Component
public class KnowledgeIndexingOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexingOutboxWorker.class);

    private final KnowledgeProcessingRunRepository processingRunRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate requiresNewTxTemplate;
    private final TransactionTemplate notSupportedTxTemplate;

    // Optional execution inspection hook for test verification (e.g. TransactionSynchronizationManager state)
    private volatile Consumer<KnowledgeProcessingRun> executionInspectionHook = null;

    public KnowledgeIndexingOutboxWorker(
            KnowledgeProcessingRunRepository processingRunRepository,
            KnowledgeVersionRepository versionRepository,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.processingRunRepository = processingRunRepository;
        this.versionRepository = versionRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;

        this.requiresNewTxTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        this.notSupportedTxTemplate = new TransactionTemplate(transactionManager);
        this.notSupportedTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /**
     * Set a hook to inspect execution state (e.g. transaction status) during Boundary 2 execution.
     */
    public void setExecutionInspectionHook(Consumer<KnowledgeProcessingRun> hook) {
        this.executionInspectionHook = hook;
    }

    /**
     * Claims and processes a single eligible run.
     *
     * @param workerId worker identity string
     * @return true if a run was claimed and processed, false if no runs were eligible
     */
    public boolean processSingleRun(String workerId) {
        String effectiveWorkerId = (workerId != null && !workerId.isBlank()) ? workerId : "worker-" + UUID.randomUUID();

        // Boundary 1: Claim transaction commits immediately so other workers see RUNNING status
        Optional<KnowledgeProcessingRun> claimedOpt = requiresNewTxTemplate.execute(status ->
                processingRunRepository.claimNextEligibleRun(effectiveWorkerId, Duration.ofMinutes(5))
        );

        if (claimedOpt == null || claimedOpt.isEmpty()) {
            return false;
        }

        KnowledgeProcessingRun run = claimedOpt.get();
        Instant now = Instant.now(clock);

        // Boundary 2: Execution runs with caller transaction suspended (PROPAGATION_NOT_SUPPORTED).
        // The worker does not participate in the caller transaction during execution;
        // suspending the transaction does not automatically release any existing locks or connections held by the caller.
        notSupportedTxTemplate.execute(status -> {
            executeRun(run, effectiveWorkerId, now);
            return null;
        });

        return true;
    }

    private void executeRun(KnowledgeProcessingRun run, String effectiveWorkerId, Instant now) {
        if (executionInspectionHook != null) {
            executionInspectionHook.accept(run);
        }

        try {
            Optional<KnowledgeVersion> versionOpt = versionRepository.findById(run.knowledgeVersionId());
            if (versionOpt.isEmpty()) {
                markFailedInTx(run, "Knowledge version not found: " + run.knowledgeVersionId(), false, null, now);
                return;
            }

            KnowledgeVersion version = versionOpt.get();

            // GUARD: Archiving or non-ACTIVE status immediately revokes indexing eligibility
            if (version.status() != KnowledgeStatus.ACTIVE) {
                log.info("Canceling indexing for run {}: version {} has status {} (not ACTIVE)",
                        run.id(), version.id(), version.status());
                markFailedInTx(
                        run,
                        "Ineligible for indexing: version status is " + version.status() + " (requires ACTIVE)",
                        false,
                        null,
                        now
                );
                return;
            }

            // Boundary execution: record explicit dry-run verification metadata (NOT production index readiness)
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("mode", "BOUNDARY_VERIFICATION_ONLY");
            stats.put("processor", "KnowledgeIndexingOutboxConsumer");
            stats.put("workerId", effectiveWorkerId);
            stats.put("real_embeddings", false);
            stats.put("indexed_chunks", 0);
            stats.put("versionLabel", version.versionLabel());
            stats.put("note", "Boundary verification dry-run only; production AI retrieval runtime not deployed");

            String statsJson = objectMapper.writeValueAsString(stats);

            // Boundary 3: Completion transaction commits separately with claim fencing
            boolean updated = markSuccessInTx(run, statsJson, now);
            if (updated) {
                log.info("Completed boundary verification outbox run {} for version {} (mode=BOUNDARY_VERIFICATION_ONLY, real_embeddings=false)",
                        run.id(), version.id());
            } else {
                log.warn("Completion rejected by claim fencing for run {} (version {}): stale worker or lease expired; run not marked completed",
                        run.id(), version.id());
            }
        } catch (Exception e) {
            log.error("Error processing knowledge indexing run {}: {}", run.id(), e.getMessage(), e);
            // Retryable failure with fixed 30-second delay (attempt_count < max_attempts)
            markFailedInTx(run, "Execution error: " + e.getMessage(), true, Duration.ofSeconds(30), now);
        }
    }

    private boolean markSuccessInTx(KnowledgeProcessingRun run, String statsJson, Instant completedAt) {
        Boolean updated = requiresNewTxTemplate.execute(status ->
                processingRunRepository.markSuccess(run.id(), run.claimToken(), statsJson, completedAt)
        );
        return Boolean.TRUE.equals(updated);
    }

    private boolean markFailedInTx(KnowledgeProcessingRun run, String errorMessage, boolean retryable, Duration retryDelay, Instant now) {
        Boolean updated = requiresNewTxTemplate.execute(status -> {
            boolean result = processingRunRepository.markFailed(run.id(), run.claimToken(), errorMessage, retryable, retryDelay, now);
            if (!result) {
                log.warn("Fencing rejected markFailed for run {}: stale worker or lease expired", run.id());
            }
            return result;
        });
        return Boolean.TRUE.equals(updated);
    }
}
