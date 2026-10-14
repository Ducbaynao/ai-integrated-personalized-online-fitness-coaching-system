package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import com.fitnesscoaching.platform.modules.ai.application.port.out.F01AiServiceClient;
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
    public F01AiServiceClient simulatedAiServiceClient() {
        return new SimulatedF01AiServiceClient();
    }
}
