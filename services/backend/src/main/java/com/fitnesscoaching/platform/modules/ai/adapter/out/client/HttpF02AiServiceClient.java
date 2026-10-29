package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiTimeoutException;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F02AiServiceClient;
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

public class HttpF02AiServiceClient implements F02AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpF02AiServiceClient.class);
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Service-Key";
    private static final String GENERATE_PATH = "/internal/v1/workout-proposals/generate";

    private final RestClient restClient;

    public HttpF02AiServiceClient(AiServiceProperties properties) {
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

    public HttpF02AiServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public String generateProposalJson(F02ServiceRequest request) {
        try {
            log.info("Invoking FastAPI AI service for F02 workout proposal. StudentId: {}, Mode: {}",
                    request.studentId(), request.mode());

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
                        throw new AiProviderException("FastAPI AI service server error (" + code + ")");
                    })
                    .body(String.class);

        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                log.error("Timeout invoking FastAPI AI service for F02 proposal", e);
                throw new AiTimeoutException("AI service request timed out for F02 proposal");
            }
            log.error("Connection failure communicating with FastAPI AI service", e);
            throw new AiProviderException("Unable to connect to AI Assistance Service: " + e.getMessage());
        } catch (RestClientResponseException e) {
            log.error("FastAPI AI service returned error: {} {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new AiProviderException("FastAPI AI service returned error " + e.getStatusCode());
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            log.error("Unexpected error invoking FastAPI AI service for F02", e);
            throw new AiProviderException("Unexpected failure in AI service communication: " + e.getMessage());
        }
    }
}
