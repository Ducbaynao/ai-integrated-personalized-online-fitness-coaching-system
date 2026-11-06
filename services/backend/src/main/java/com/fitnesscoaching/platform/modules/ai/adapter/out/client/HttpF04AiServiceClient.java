package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiTimeoutException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F04AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Spring HTTP Client adapter invoking FastAPI AI service endpoint /internal/v1/progress/summarize.
 */
public class HttpF04AiServiceClient implements F04AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpF04AiServiceClient.class);
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Service-Key";
    private static final String SUMMARIZE_PATH = "/internal/v1/progress/summarize";

    private final RestClient restClient;

    public HttpF04AiServiceClient(AiServiceProperties properties) {
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

    public HttpF04AiServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public F04ProgressSummaryResponse summarizeProgress(F04InternalContextEnvelope envelope) {
        try {
            log.info("Dispatching F04 progress summarization to FastAPI AI service for runId: {}, correlationId: {}",
                    envelope.requestId(), envelope.correlationId());

            F04ProgressSummaryResponse response = restClient.post()
                    .uri(SUMMARIZE_PATH)
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
                    .body(F04ProgressSummaryResponse.class);

            if (response == null) {
                throw new AiOutputValidationException("FastAPI AI service returned empty response body");
            }

            return response;

        } catch (ResourceAccessException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof SocketTimeoutException || (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("timed out"))) {
                log.warn("Connection/read timeout invoking FastAPI AI service for runId {}: {}",
                        envelope.requestId(), ex.getMessage());
                throw new AiTimeoutException("AI model invocation timed out: " + ex.getMessage());
            }
            log.warn("Network error invoking FastAPI AI service for runId {}: {}",
                    envelope.requestId(), ex.getMessage());
            throw new AiProviderException("Network error invoking FastAPI AI service: " + ex.getMessage());
        }
    }
}
