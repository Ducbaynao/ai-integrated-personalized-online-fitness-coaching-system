package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiOrphanRecoveryProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@EnableConfigurationProperties(AiOrphanRecoveryProperties.class)
public class AiRunOrphanRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AiRunOrphanRecoveryService.class);

    private final AiRunRepository runRepository;
    private final AiOrphanRecoveryProperties properties;
    private final Clock clock;

    public AiRunOrphanRecoveryService(
            AiRunRepository runRepository,
            AiOrphanRecoveryProperties properties,
            Clock clock
    ) {
        this.runRepository = runRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Executes one batch of orphan run reconciliation inside a database transaction.
     * Uses row locking with FOR UPDATE SKIP LOCKED and conditional status updates
     * to safely reconcile runs abandoned by backend crashes without race conditions.
     */
    @Transactional
    public int executeRecoveryBatch() {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minusSeconds(properties.timeoutThresholdSeconds());

        List<UUID> staleRunIds = runRepository.findStaleRunIds(cutoff, properties.batchSize());
        if (staleRunIds.isEmpty()) {
            return 0;
        }

        log.info("Found {} candidate orphan AI run(s) older than cutoff {}", staleRunIds.size(), cutoff);
        int reconciledCount = 0;

        for (UUID runId : staleRunIds) {
            boolean reconciled = runRepository.reconcileStaleRun(
                    runId,
                    "TIMEOUT",
                    "Execution timed out due to system interruption or backend restart.",
                    now
            );
            if (reconciled) {
                reconciledCount++;
                log.info("Reconciled orphan AI Run {} to FAILED status", runId);
            } else {
                log.warn("AI Run {} was already transitioned or completed; skipped reconciliation", runId);
            }
        }

        return reconciledCount;
    }
}
