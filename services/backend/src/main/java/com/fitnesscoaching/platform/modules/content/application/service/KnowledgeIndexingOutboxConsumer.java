package com.fitnesscoaching.platform.modules.content.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Durable consumer for knowledge indexing outbox tasks stored in fitness.knowledge_processing_runs.
 * Delegates individual task processing to KnowledgeIndexingOutboxWorker across explicit transaction boundaries.
 *
 * NOTE: This is a boundary verification consumer for Phase 2 Milestone 2.
 * It executes dry-run verification and records boundary metadata (real_embeddings=false).
 * It does NOT call real AI embedding providers and does NOT establish production retrieval index readiness.
 */
@Service
public class KnowledgeIndexingOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexingOutboxConsumer.class);

    private final KnowledgeIndexingOutboxWorker worker;

    public KnowledgeIndexingOutboxConsumer(KnowledgeIndexingOutboxWorker worker) {
        this.worker = worker;
    }

    /**
     * Atomically claim and process a single eligible pending run using the specified worker identity.
     * Safe against concurrent workers via PostgreSQL SKIP LOCKED and lease expiration.
     */
    public boolean processNextRun(String workerId) {
        return worker.processSingleRun(workerId);
    }

    /**
     * Drain up to batchSize runs sequentially.
     * Each run executes across its own independent transaction boundaries (claim, execute, complete).
     * Failure of one run does not roll back prior runs or prevent subsequent runs.
     * Operates independently from any caller transaction context.
     */
    public int processPendingRuns(int batchSize) {
        int processedCount = 0;
        String workerId = "batch-worker-" + UUID.randomUUID();
        for (int i = 0; i < batchSize; i++) {
            boolean processed = worker.processSingleRun(workerId);
            if (!processed) {
                break;
            }
            processedCount++;
        }
        return processedCount;
    }

    /**
     * Delegate test seam hook for inspecting execution context during Boundary 2 non-transactional phase.
     */
    public void setExecutionInspectionHook(java.util.function.Consumer<com.fitnesscoaching.platform.modules.content.domain.KnowledgeProcessingRun> hook) {
        worker.setExecutionInspectionHook(hook);
    }
}
