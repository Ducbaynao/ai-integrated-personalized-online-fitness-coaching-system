package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.orphan-recovery")
public record AiOrphanRecoveryProperties(
        Boolean enabled,
        int timeoutThresholdSeconds,
        long fixedDelayMs,
        long initialDelayMs,
        int batchSize
) {
    public AiOrphanRecoveryProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (timeoutThresholdSeconds <= 0) {
            timeoutThresholdSeconds = 120;
        }
        if (fixedDelayMs <= 0) {
            fixedDelayMs = 60000;
        }
        if (initialDelayMs <= 0) {
            initialDelayMs = 10000;
        }
        if (batchSize <= 0) {
            batchSize = 50;
        }
    }
}
