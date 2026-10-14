package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiTimeoutException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F01AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
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

/**
 * Spring HTTP Client adapter invoking the internal FastAPI AI Assistance Service.
 * Implements F01AiServiceClient, transmits F01InternalContextEnvelope with service authentication,
 * and maps HTTP/network outcomes to domain exceptions without holding DB transactions or blindly retrying.
 */
public class HttpF01AiServiceClient implements F01AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpF01AiServiceClient.class);
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Service-Key";
    private static final String QUERY_PATH = "/internal/v1/knowledge/query";

    private final RestClient restClient;

    public HttpF01AiServiceClient(AiServiceProperties properties) {
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

    public HttpF01AiServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public F01KnowledgeQueryResponse queryKnowledge(F01InternalContextEnvelope envelope) {
        try {
            log.info("Dispatching F01 inquiry to FastAPI AI service for runId: {}, correlationId: {}",
                    envelope.requestId(), envelope.correlationId());

            F01KnowledgeQueryResponse response = restClient.post()
                    .uri(QUERY_PATH)
                    .body(envelope)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        int code = resp.getStatusCode().value();
                        String statusText = resp.getStatusText();
                        log.warn("FastAPI AI service returned 4xx error: {} {}", code, statusText);
                        if (code == 401 || code == 403) {
                            throw new AiProviderException("FastAPI AI service authentication failed (" + code + ")");
                        }
                        if (code == 422) {
                            throw new AiProviderException("FastAPI rejected envelope schema validation (422 Unprocessable Entity)");
                        }
                        throw new AiProviderException("FastAPI AI service returned client error (" + code + " " + statusText + ")");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        int code = resp.getStatusCode().value();
                        String statusText = resp.getStatusText();
                        log.warn("FastAPI AI service returned 5xx error: {} {}", code, statusText);
                        if (code == 504) {
                            throw new AiTimeoutException("FastAPI AI service gateway timed out (504)");
                        }
                        throw new AiProviderException("FastAPI AI service provider error (" + code + " " + statusText + ")");
                    })
                    .body(F01KnowledgeQueryResponse.class);

            if (response == null) {
                throw new AiOutputValidationException("FastAPI AI service returned empty response body");
            }

            return response;

        } catch (ResourceAccessException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof SocketTimeoutException || ex.getMessage().toLowerCase().contains("timed out")) {
                log.warn("Connection/read timeout invoking FastAPI AI service for runId {}: {}",
                        envelope.requestId(), ex.getMessage());
                throw new AiTimeoutException("AI model invocation timed out: " + ex.getMessage());
            }
            log.warn("Network error invoking FastAPI AI service for runId {}: {}",
                    envelope.requestId(), ex.getMessage());
            throw new AiProviderException("Unable to connect to AI service: " + ex.getMessage());
        } catch (RestClientResponseException ex) {
            log.warn("HTTP error from FastAPI AI service for runId {}: status={}, body={}",
                    envelope.requestId(), ex.getStatusCode(), ex.getResponseBodyAsString());
            if (ex.getStatusCode().value() == 504) {
                throw new AiTimeoutException("FastAPI AI service gateway timed out (504)");
            }
            throw new AiProviderException("AI service returned error status: " + ex.getStatusCode());
        }
    }
}
