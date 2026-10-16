package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeChunkRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeDocumentRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeEmbeddingRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeProcessingRunRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeVersionRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeEmbeddingRecord;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeProcessingRun;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Worker component executing single knowledge indexing outbox runs across strict transaction boundaries:
 * Boundary 1: Atomic Claim commits immediately in a dedicated REQUIRES_NEW transaction.
 * Boundary 2: Execution runs with caller transaction suspended (PROPAGATION_NOT_SUPPORTED).
 *             Executes deterministic chunking and dense vector embedding generation without holding database locks.
 * Boundary 3: Completion or failure commits in a dedicated REQUIRES_NEW transaction guarded by claim fencing.
 *             Persists chunks, embeddings, and marks index readiness idempotently.
 */
@Component
public class KnowledgeIndexingOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexingOutboxWorker.class);

    private final KnowledgeProcessingRunRepository processingRunRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeEmbeddingRepository embeddingRepository;
    private final KnowledgeChunker chunker;
    private final EmbeddingModelPort embeddingModelPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate requiresNewTxTemplate;
    private final TransactionTemplate notSupportedTxTemplate;

    // Optional execution inspection hook for test verification (e.g. TransactionSynchronizationManager state)
    private volatile Consumer<KnowledgeProcessingRun> executionInspectionHook = null;

    public KnowledgeIndexingOutboxWorker(
            KnowledgeProcessingRunRepository processingRunRepository,
            KnowledgeVersionRepository versionRepository,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeChunkRepository chunkRepository,
            KnowledgeEmbeddingRepository embeddingRepository,
            KnowledgeChunker chunker,
            EmbeddingModelPort embeddingModelPort,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.processingRunRepository = processingRunRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingRepository = embeddingRepository;
        this.chunker = chunker;
        this.embeddingModelPort = embeddingModelPort;
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

            // GUARD 1: Archiving or non-ACTIVE status immediately revokes indexing eligibility
            if (version.status() != KnowledgeStatus.ACTIVE) {
                log.info("Canceling indexing for run {}: version {} has status {} (requires ACTIVE)",
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

            // GUARD 2: Technical readiness must be TECHNICAL_VERIFICATION_PASSED (fail-closed for HELD_AS_DRAFT)
            if (version.technicalReadiness() != TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED) {
                log.info("Canceling indexing for run {}: version {} has technical readiness {} (requires TECHNICAL_VERIFICATION_PASSED)",
                        run.id(), version.id(), version.technicalReadiness());
                markFailedInTx(
                        run,
                        "Ineligible for indexing: version technical readiness is " + version.technicalReadiness() +
                                (version.holdReason() != null ? " (Hold reason: " + version.holdReason() + ")" : ""),
                        false,
                        null,
                        now
                );
                return;
            }

            Optional<KnowledgeDocument> documentOpt = documentRepository.findById(version.knowledgeDocumentId());
            if (documentOpt.isEmpty()) {
                markFailedInTx(run, "Knowledge document not found for version: " + version.id(), false, null, now);
                return;
            }
            KnowledgeDocument document = documentOpt.get();

            // STEP 1: Deterministic chunking (Boundary 2, no DB locks held)
            List<KnowledgeChunk> chunks = chunker.chunk(version, document, now);

            // STEP 2: Dense vector embedding generation (Boundary 2, outside DB transaction)
            List<String> chunkContents = chunks.stream().map(KnowledgeChunk::content).toList();
            List<float[]> embeddings = embeddingModelPort.generateBatch(chunkContents);

            // Compatibility Gate: ensure dimension strictly matches 1536
            for (float[] vector : embeddings) {
                if (vector.length != embeddingModelPort.dimension()) {
                    throw new IllegalStateException("Embedding dimension mismatch: expected " +
                            embeddingModelPort.dimension() + " but was " + vector.length);
                }
            }

            // STEP 3: Build completion statistics with verified index readiness and model space
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("mode", "REAL_RETRIEVAL_INDEXING");
            stats.put("processor", "KnowledgeIndexingOutboxWorker");
            stats.put("workerId", effectiveWorkerId);
            stats.put("real_embeddings", true);
            stats.put("embedding_provider", embeddingModelPort.provider());
            stats.put("embedding_model", embeddingModelPort.modelCode());
            stats.put("embedding_space", embeddingModelPort.modelSpace());
            stats.put("embedding_model_version_id", embeddingModelPort.modelVersionId().toString());
            stats.put("embedding_version", embeddingModelPort.versionLabel());
            stats.put("dimension", embeddingModelPort.dimension());
            stats.put("indexed_chunks", chunks.size());
            stats.put("versionLabel", version.versionLabel());
            stats.put("index_ready", true);
            stats.put("completed_at", now.toString());

            String statsJson = objectMapper.writeValueAsString(stats);

            // Boundary 3: Atomic persistence of chunks, embeddings, and completion under claim fencing
            Boolean updated = requiresNewTxTemplate.execute(txStatus -> {
                boolean success = processingRunRepository.markSuccess(run.id(), run.claimToken(), statsJson, now);
                if (!success) {
                    txStatus.setRollbackOnly();
                    return false;
                }

                // Replace embeddings for this version (ensures idempotency on REINDEX while preserving cited chunk IDs)
                embeddingRepository.deleteByKnowledgeVersionId(version.id());

                if (!chunks.isEmpty()) {
                    chunkRepository.saveBatch(chunks);
                    chunkRepository.deleteUnreferencedChunksBeyondIndex(version.id(), chunks.size());

                    List<KnowledgeEmbeddingRecord> embeddingRecords = new ArrayList<>(chunks.size());
                    for (int i = 0; i < chunks.size(); i++) {
                        embeddingRecords.add(new KnowledgeEmbeddingRecord(
                                UUID.randomUUID(),
                                chunks.get(i).id(),
                                embeddingModelPort.modelVersionId(),
                                embeddings.get(i),
                                now
                        ));
                    }
                    embeddingRepository.saveBatch(embeddingRecords);
                }
                return true;
            });

            if (Boolean.TRUE.equals(updated)) {
                log.info("Completed real knowledge indexing outbox run {} for version {} (indexed_chunks={}, dimension={}, modelSpace={}, index_ready=true)",
                        run.id(), version.id(), chunks.size(), embeddingModelPort.dimension(), embeddingModelPort.modelSpace());
            } else {
                log.warn("Completion rejected by claim fencing for run {} (version {}): stale worker or lease expired; no chunks or embeddings committed",
                        run.id(), version.id());
            }
        } catch (Exception e) {
            log.error("Error processing knowledge indexing run {}: {}", run.id(), e.getMessage(), e);
            markFailedInTx(run, "Execution error: " + e.getMessage(), true, Duration.ofSeconds(30), now);
        }
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
