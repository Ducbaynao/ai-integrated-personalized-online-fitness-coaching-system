package com.fitnesscoaching.platform.modules.content.adapter.in.scheduled;

import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeIndexingOutboxConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic outbox poller draining queued knowledge indexing runs into
 * {@link KnowledgeIndexingOutboxConsumer} using existing PostgreSQL SKIP LOCKED
 * claim fencing and retry semantics.
 */
@Component
public class KnowledgeIndexingOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexingOutboxScheduler.class);

    private final KnowledgeIndexingOutboxConsumer outboxConsumer;
    private final boolean enabled;
    private final int batchSize;

    public KnowledgeIndexingOutboxScheduler(
            KnowledgeIndexingOutboxConsumer outboxConsumer,
            @Value("${ai.knowledge.outbox-worker.enabled:true}") boolean enabled,
            @Value("${ai.knowledge.outbox-worker.batch-size:10}") int batchSize
    ) {
        this.outboxConsumer = outboxConsumer;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(
            fixedDelayString = "${ai.knowledge.outbox-worker.fixed-delay-ms:5000}",
            initialDelayString = "${ai.knowledge.outbox-worker.initial-delay-ms:5000}"
    )
    public int runScheduledIndexing() {
        if (!enabled) {
            log.trace("Knowledge indexing outbox worker is disabled via configuration");
            return 0;
        }
        return outboxConsumer.processPendingRuns(batchSize);
    }
}
