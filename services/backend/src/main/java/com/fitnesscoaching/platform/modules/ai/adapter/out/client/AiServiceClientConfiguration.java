package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F01AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F02AiServiceClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiServiceClientConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ai.service.mode", havingValue = "http")
    public F01AiServiceClient httpAiServiceClient(AiServiceProperties properties) {
        return new HttpF01AiServiceClient(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.service.mode", havingValue = "simulated", matchIfMissing = true)
    public F01AiServiceClient simulatedAiServiceClient(
            org.springframework.beans.factory.ObjectProvider<com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase> retrievalUseCaseProvider,
            org.springframework.beans.factory.ObjectProvider<com.fitnesscoaching.platform.modules.content.application.service.F01RunEvidenceTracker> evidenceTrackerProvider
    ) {
        return new SimulatedF01AiServiceClient(retrievalUseCaseProvider.getIfAvailable(), evidenceTrackerProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "ai.service.mode", havingValue = "http")
    public F02AiServiceClient httpF02AiServiceClient(AiServiceProperties properties) {
        return new HttpF02AiServiceClient(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.service.mode", havingValue = "simulated", matchIfMissing = true)
    public F02AiServiceClient simulatedF02AiServiceClient(ObjectMapper objectMapper) {
        return new SimulatedF02AiServiceClient(objectMapper);
    }
}
