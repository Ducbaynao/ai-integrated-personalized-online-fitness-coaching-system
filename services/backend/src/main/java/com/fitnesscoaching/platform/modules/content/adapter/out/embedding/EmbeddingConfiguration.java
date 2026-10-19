package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration for knowledge embedding model adapters.
 * Selects between DeterministicEmbeddingGenerator (simulated) and OpenAiEmbeddingAdapter (OpenAI)
 * based on the configuration property `ai.embedding.provider`. Defaults to 'simulated' for CI and offline tests.
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @ConditionalOnProperty(name = "ai.embedding.provider", havingValue = "openai")
    public EmbeddingModelPort openAiEmbeddingAdapter(
            EmbeddingProperties properties,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        String apiKey = properties.openai().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI embedding API key is required when ai.embedding.provider=openai. " +
                    "Configure AI_EMBEDDING_API_KEY or OPENAI_API_KEY.");
        }
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new OpenAiEmbeddingAdapter(properties, builder);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.embedding.provider", havingValue = "simulated", matchIfMissing = true)
    public DeterministicEmbeddingGenerator simulatedEmbeddingGenerator() {
        return new DeterministicEmbeddingGenerator();
    }
}
