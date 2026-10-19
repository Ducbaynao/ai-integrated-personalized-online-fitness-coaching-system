package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for knowledge embedding providers.
 * Supports provider selection ('simulated' or 'openai') and OpenAI API client parameters.
 */
@ConfigurationProperties(prefix = "ai.embedding")
public record EmbeddingProperties(
        String provider,
        OpenAiProperties openai
) {
    public EmbeddingProperties {
        if (provider == null || provider.isBlank()) {
            provider = "simulated";
        } else {
            provider = provider.trim().toLowerCase();
            if (!"simulated".equals(provider) && !"openai".equals(provider)) {
                throw new IllegalStateException("Unsupported ai.embedding.provider: '" + provider +
                        "'. Supported values are 'simulated' and 'openai'.");
            }
        }
        if (openai == null) {
            openai = new OpenAiProperties(null, null, null, null, null, null, null, null, null);
        }
    }

    public record OpenAiProperties(
            String apiKey,
            String baseUrl,
            String model,
            String modelSpace,
            Integer dimension,
            Integer connectTimeoutMs,
            Integer readTimeoutMs,
            Integer maxAttempts,
            Integer overallDeadlineMs
    ) {
        public OpenAiProperties {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://api.openai.com";
            } else {
                baseUrl = baseUrl.trim();
            }
            if (model == null || model.isBlank()) {
                model = "text-embedding-3-small";
            }
            if (modelSpace == null || modelSpace.isBlank()) {
                modelSpace = "openai/text-embedding-3-small/1536";
            }
            if (dimension == null || dimension <= 0) {
                dimension = 1536;
            }
            if (connectTimeoutMs == null || connectTimeoutMs <= 0) {
                connectTimeoutMs = 3000;
            }
            if (readTimeoutMs == null || readTimeoutMs <= 0) {
                readTimeoutMs = 6000;
            }
            if (maxAttempts == null || maxAttempts <= 0) {
                maxAttempts = 2; // 1 initial attempt + 1 retry
            }
            if (overallDeadlineMs == null || overallDeadlineMs <= 0) {
                overallDeadlineMs = 15000;
            }
        }
    }
}
