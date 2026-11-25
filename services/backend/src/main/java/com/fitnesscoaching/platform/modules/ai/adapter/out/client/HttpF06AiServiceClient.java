package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F06AiServiceClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

public final class HttpF06AiServiceClient implements F06AiServiceClient {
    private final RestClient restClient;

    public HttpF06AiServiceClient(AiServiceProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        restClient = RestClient.builder().baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Service-Key", properties.internalServiceKey())
                .build();
    }

    @Override
    public String estimateTextJson(String text) {
        try {
            return restClient.post().uri("/internal/v1/food-text-estimates/generate")
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("text", text))
                    .retrieve().body(String.class);
        } catch (RestClientException failed) {
            throw new AiProviderException("F06 recognition provider unavailable");
        }
    }
}
