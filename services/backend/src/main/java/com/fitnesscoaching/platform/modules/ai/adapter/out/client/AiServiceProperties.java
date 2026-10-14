package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.service")
public record AiServiceProperties(
        Boolean enabled,
        String mode,
        String baseUrl,
        String internalServiceKey,
        int connectTimeoutMs,
        int readTimeoutMs
) {
    public AiServiceProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (mode == null || mode.isBlank()) {
            mode = "simulated";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8000";
        }
        if (internalServiceKey == null) {
            internalServiceKey = "";
        }
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 5000;
        }
        if (readTimeoutMs <= 0) {
            readTimeoutMs = 10000;
        }
    }
}
