package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiTimeoutException;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F05AiServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;

public class HttpF05AiServiceClient implements F05AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpF05AiServiceClient.class);
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Service-Key";
    private static final String GENERATE_PATH = "/internal/v1/workout-reschedules/generate";

    private final RestClient restClient;

    public HttpF05AiServiceClient(AiServiceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(INTERNAL_KEY_HEADER, properties.internalServiceKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public HttpF05AiServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public String generateRescheduleJson(F05ServiceRequest request) {
        try {
            log.info("Invoking FastAPI AI service for F05 workout reschedule. StudentId: {}, OccurrenceId: {}",
                    request.studentId(), request.occurrenceId());

            return restClient.post()
                    .uri(GENERATE_PATH)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        int code = resp.getStatusCode().value();
                        log.warn("FastAPI AI service returned 4xx: {}", code);
                        throw new AiProviderException("FastAPI AI service client error (" + code + ")");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        int code = resp.getStatusCode().value();
                        log.error("FastAPI AI service returned 5xx: {}", code);
                        throw new AiProviderException("FastAPI AI service unavailable or error (" + code + ")");
                    })
                    .body(String.class);
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                log.error("Timeout communicating with FastAPI AI service: {}", e.getMessage());
                throw new AiTimeoutException("AI service call timed out while generating workout reschedule.");
            }
            log.error("Network error communicating with FastAPI AI service: {}", e.getMessage());
            throw new AiProviderException("Network error communicating with AI service: " + e.getMessage());
        } catch (RestClientResponseException e) {
            log.error("HTTP error response from FastAPI AI service: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new AiProviderException("HTTP error from AI service: " + e.getStatusCode());
        } catch (Exception e) {
            if (e instanceof AiProviderException || e instanceof AiTimeoutException) {
                throw e;
            }
            log.error("Unexpected error invoking FastAPI AI service: {}", e.getMessage(), e);
            throw new AiProviderException("Unexpected error during AI workout reschedule generation: " + e.getMessage());
        }
    }
}
