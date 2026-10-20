package com.fitnesscoaching.platform.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Governed scheduling configuration.
 * Enabled by default across production and testing.
 * Can be explicitly disabled via app.scheduling.enabled=false during maintenance,
 * retrieval baselines, or isolated evaluation runs.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
