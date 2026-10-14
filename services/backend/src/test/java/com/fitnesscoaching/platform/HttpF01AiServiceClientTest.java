package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiTimeoutException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.HttpF01AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("HttpF01AiServiceClient Unit Tests")
class HttpF01AiServiceClientTest {

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private HttpF01AiServiceClient client;

    private static final String BASE_URL = "http://localhost:8000";
    private static final String SECRET_KEY = "test-internal-key-12345";
    private static final UUID REQUEST_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CORRELATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("X-Internal-Service-Key", SECRET_KEY);
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new HttpF01AiServiceClient(restClientBuilder.build());
    }

    private F01InternalContextEnvelope createSampleEnvelope() {
        return new F01InternalContextEnvelope(
                REQUEST_ID,
                "F01_GENERAL_KNOWLEDGE",
                "1.0.0",
                CORRELATION_ID,
                new F01InternalContextEnvelope.ActorContext(UUID.randomUUID(), "STUDENT"),
                "GENERAL_FITNESS_EXPLANATION",
                List.of("READ_INFORMATION"),
                "2026-10-10T12:00:00Z",
                "Làm sao để hấp thu protein hiệu quả nhất?",
                "vi",
                null,
                new F01InternalContextEnvelope.KnowledgeScopeContext("ACTIVE", List.of("ISSN-PROTEIN-EXERCISE-2017"))
        );
    }

    @Test
    @DisplayName("1. Successfully sends envelope with auth header and deserializes valid response")
    void sendsEnvelopeWithAuthHeaderSuccessfully() {
        String responseJson = """
                {
                  "runId": "44444444-4444-4444-8444-444444444444",
                  "correlationId": "22222222-2222-4222-8222-222222222222",
                  "runStatus": "SUCCEEDED",
                  "resultType": "INFORMATION",
                  "reasonCode": "NONE",
                  "answer": "Nên chia đều lượng protein qua các bữa ăn.",
                  "citations": [
                    {
                      "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
                      "version": "1.0.0",
                      "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                      "locator": {
                        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md"
                      }
                    }
                  ],
                  "limitations": ["Healthy adults only"],
                  "missingData": [],
                  "asOf": "2026-10-10T12:00:05Z",
                  "usage": {
                    "promptTokens": 100,
                    "completionTokens": 50,
                    "totalTokens": 150,
                    "latencyMs": 80
                  }
                }
                """;

        mockServer.expect(requestTo("http://localhost:8000/internal/v1/knowledge/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Key", SECRET_KEY))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        F01KnowledgeQueryResponse response = client.queryKnowledge(createSampleEnvelope());

        assertThat(response).isNotNull();
        assertThat(response.runId()).isEqualTo(REQUEST_ID);
        assertThat(response.runStatus()).isEqualTo("SUCCEEDED");
        assertThat(response.resultType()).isEqualTo("INFORMATION");
        assertThat(response.answer()).isEqualTo("Nên chia đều lượng protein qua các bữa ăn.");
        assertThat(response.citations()).hasSize(1);
        mockServer.verify();
    }

    @Test
    @DisplayName("2. Maps HTTP 401/403 to AiProviderException (Auth failure)")
    void mapsAuthFailureToAiProviderException() {
        mockServer.expect(requestTo("http://localhost:8000/internal/v1/knowledge/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"detail\":\"Missing internal key\"}"));

        assertThatThrownBy(() -> client.queryKnowledge(createSampleEnvelope()))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("FastAPI AI service authentication failed (401)");

        mockServer.verify();
    }

    @Test
    @DisplayName("3. Maps HTTP 422 to AiProviderException (Internal request envelope rejected by FastAPI)")
    void maps422ToAiProviderException() {
        mockServer.expect(requestTo("http://localhost:8000/internal/v1/knowledge/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).body("{\"detail\":\"Field required\"}"));

        assertThatThrownBy(() -> client.queryKnowledge(createSampleEnvelope()))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("FastAPI rejected envelope schema validation (422");

        mockServer.verify();
    }

    @Test
    @DisplayName("4. Maps HTTP 504 to AiTimeoutException")
    void maps504ToAiTimeoutException() {
        mockServer.expect(requestTo("http://localhost:8000/internal/v1/knowledge/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        assertThatThrownBy(() -> client.queryKnowledge(createSampleEnvelope()))
                .isInstanceOf(AiTimeoutException.class)
                .hasMessageContaining("FastAPI AI service gateway timed out (504)");

        mockServer.verify();
    }

    @Test
    @DisplayName("5. Maps HTTP 502/500 to AiProviderException")
    void maps502ToAiProviderException() {
        mockServer.expect(requestTo("http://localhost:8000/internal/v1/knowledge/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.queryKnowledge(createSampleEnvelope()))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("FastAPI AI service provider error (502");

        mockServer.verify();
    }
}
