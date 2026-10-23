package com.fitnesscoaching.platform.modules.ai.adapter.in.scheduled;

import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiOrphanRecoveryProperties;
import com.fitnesscoaching.platform.modules.ai.application.service.AiRunOrphanRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiRunOrphanRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiRunOrphanRecoveryScheduler.class);

    private final AiRunOrphanRecoveryService recoveryService;
    private final AiOrphanRecoveryProperties properties;

    public AiRunOrphanRecoveryScheduler(
            AiRunOrphanRecoveryService recoveryService,
            AiOrphanRecoveryProperties properties
    ) {
        this.recoveryService = recoveryService;
        this.properties = properties;
    }

    /**
     * Periodic task invoking the transactional orphan run recovery service.
     * Invoking the injected service bean ensures execution passes through
     * the Spring transactional AOP proxy (eliminating self-invocation bypass).
     */
    @Scheduled(
            fixedDelayString = "${ai.orphan-recovery.fixed-delay-ms:60000}",
            initialDelayString = "${ai.orphan-recovery.initial-delay-ms:10000}"
    )
    public int runScheduledRecovery() {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            log.trace("AI orphan run recovery is disabled via configuration");
            return 0;
        }
        return recoveryService.executeRecoveryBatch();
    }
}
