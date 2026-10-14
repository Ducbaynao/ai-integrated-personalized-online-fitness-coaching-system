package com.fitnesscoaching.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryApiRequest;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP wire contract integration test verifying that Spring Boot invokes the AI service over real HTTP
 * using HttpF01AiServiceClient with service-to-service header authentication,
 * runs strict runtime output validation, and persists terminal results and provenance.
 *
 * NOTE: This test utilizes an in-process HTTP stub server (com.sun.net.httpserver.HttpServer)
 * to deterministically simulate wire-level HTTP response codes (502, 504, 401, 422, malformed JSON, etc.).
 * It does NOT execute the actual Python/FastAPI uvicorn process.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("F01 HTTP Stub Wire Contract Integration Tests")
class AiKnowledgeQueryHttpEndToEndIntegrationTest {

    private static final UUID STUDENT_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SERVICE_KEY = "test-e2e-internal-secret-key-12345";

    private static HttpServer mockFastApiHttpServer;
    private static int mockFastApiPort;
    private static final AtomicReference<MockAiResponseScenario> currentScenario = new AtomicReference<>();
    private static final AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
    private static final AtomicReference<String> receivedRequestBody = new AtomicReference<>();

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_http_e2e_integration")
            .withUsername("fitness_app")
            .withPassword("testpass123");

    enum MockAiResponseScenario {
        SUCCESS_INFORMATION,
        SUCCESS_INSUFFICIENT_DATA,
        BLOCKED_GUARDRAIL,
        INVALID_OUTPUT_SCHEMA,
        MISMATCHED_RUN_ID,
        CORRUPTED_CITATION_SCOPE,
        HTTP_502_PROVIDER_ERROR,
        HTTP_504_GATEWAY_TIMEOUT
    }

    @BeforeAll
    static void startServers() throws IOException {
        postgres.start();

        mockFastApiHttpServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockFastApiPort = mockFastApiHttpServer.getAddress().getPort();

        mockFastApiHttpServer.createContext("/internal/v1/knowledge/query", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Key"));
                try (InputStream is = exchange.getRequestBody()) {
                    receivedRequestBody.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                }

                // Verify internal authentication key
                if (!SERVICE_KEY.equals(receivedAuthHeader.get())) {
                    byte[] resp = "{\"detail\":\"Forbidden\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(403, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                    return;
                }

                ObjectMapper mapper = new ObjectMapper();
                JsonNode reqJson = mapper.readTree(receivedRequestBody.get());
                String requestId = reqJson.get("requestId").asText();
                String correlationId = reqJson.get("correlationId").asText();
                String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

                MockAiResponseScenario scenario = currentScenario.get();
                if (scenario == null) {
                    scenario = MockAiResponseScenario.SUCCESS_INFORMATION;
                }

                int statusCode = 200;
                String body = "";

                switch (scenario) {
                    case SUCCESS_INFORMATION -> body = """
                            {
                              "runId": "%s",
                              "correlationId": "%s",
                              "runStatus": "SUCCEEDED",
                              "resultType": "INFORMATION",
                              "reasonCode": "NONE",
                              "answer": "Theo ISSN 2017, phân bổ protein đều qua 3-4 bữa ăn giúp tối đa hóa MPS.",
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
                              "limitations": ["Người trưởng thành khỏe mạnh"],
                              "missingData": [],
                              "asOf": "%s",
                              "usage": {"promptTokens": 120, "completionTokens": 60, "totalTokens": 180, "latencyMs": 95},
                              "generationMetadata": {
                                "schemaVersion": "1.0.0",
                                "promptVersion": "v1.0",
                                "ruleVersion": "f01-rule-v1",
                                "provider": "simulated",
                                "model": "simulated-f01-v1"
                              }
                            }
                            """.formatted(requestId, correlationId, now);

                    case SUCCESS_INSUFFICIENT_DATA -> body = """
                            {
                              "runId": "%s",
                              "correlationId": "%s",
                              "runStatus": "SUCCEEDED",
                              "resultType": "INSUFFICIENT_DATA",
                              "reasonCode": "NO_RELEVANT_EVIDENCE",
                              "limitations": ["Hệ thống chỉ giải thích theo tài liệu đã kiểm định."],
                              "missingData": ["Không tìm thấy bằng chứng khoa học cho bài tập này."],
                              "asOf": "%s",
                              "usage": {"promptTokens": 80, "completionTokens": 20, "totalTokens": 100, "latencyMs": 50}
                            }
                            """.formatted(requestId, correlationId, now);

                    case BLOCKED_GUARDRAIL -> body = """
                            {
                              "runId": "%s",
                              "correlationId": "%s",
                              "runStatus": "REJECTED_BY_VALIDATOR",
                              "resultType": "BLOCKED",
                              "reasonCode": "RULE_BLOCKED",
                              "limitations": ["Cấm WADA"],
                              "missingData": [],
                              "asOf": "%s",
                              "error": {"code": "POLICY_GUARDRAIL_TRIGGERED", "message": "Doping policy blocked"}
                            }
                            """.formatted(requestId, correlationId, now);

                    case INVALID_OUTPUT_SCHEMA -> body = """
                            {
                              "runId": "%s",
                              "correlationId": "%s",
                              "runStatus": "SUCCEEDED",
                              "resultType": "INFORMATION",
                              "reasonCode": "NONE",
                              "answer": "Thiếu citations nhưng dám trả INFORMATION",
                              "citations": [],
                              "asOf": "%s"
                            }
                            """.formatted(requestId, correlationId, now);

                    case MISMATCHED_RUN_ID -> body = """
                            {
                              "runId": "%s",
                              "correlationId": "%s",
                              "runStatus": "SUCCEEDED",
                              "resultType": "INFORMATION",
                              "reasonCode": "NONE",
                              "answer": "Mismatched runId returned",
                              "citations": [
                                {
                                  "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
                                  "version": "1.0.0",
                                  "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                                  "locator": {"sourcePath": "data/04.vi.md"}
                                }
                              ],
                              "asOf": "%s"
                            }
                            """.formatted(UUID.randomUUID(), correlationId, now);

                    case CORRUPTED_CITATION_SCOPE -> body = """
                            {
                              "runId": "%s",
                              "correlationId": "%s",
                              "runStatus": "SUCCEEDED",
                              "resultType": "INFORMATION",
                              "reasonCode": "NONE",
                              "answer": "Citation nằm ngoài allowedSourceIds",
                              "citations": [
                                {
                                  "documentId": "UNAUTHORIZED-SOURCE-VI-01",
                                  "version": "1.0.0",
                                  "sourceId": "UNAUTHORIZED-SOURCE",
                                  "locator": {"sourcePath": "data/01.vi.md"}
                                }
                              ],
                              "asOf": "%s"
                            }
                            """.formatted(requestId, correlationId, now);

                    case HTTP_502_PROVIDER_ERROR -> {
                        statusCode = 502;
                        body = "{\"detail\":\"AI upstream gateway failure\"}";
                    }

                    case HTTP_504_GATEWAY_TIMEOUT -> {
                        statusCode = 504;
                        body = "{\"detail\":\"Gateway timeout\"}";
                    }
                }

                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(statusCode, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        mockFastApiHttpServer.start();
    }

    @AfterAll
    static void stopServers() {
        if (mockFastApiHttpServer != null) {
            mockFastApiHttpServer.stop(0);
        }
        postgres.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // Force HTTP mode calling real local mock HTTP server
        registry.add("ai.service.mode", () -> "http");
        registry.add("ai.service.base-url", () -> "http://localhost:" + mockFastApiPort);
        registry.add("ai.service.internal-service-key", () -> SERVICE_KEY);
        registry.add("ai.service.connect-timeout-ms", () -> 2000);
        registry.add("ai.service.read-timeout-ms", () -> 4000);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private AiRunRepository aiRunRepository;

    @Autowired
    private AiProvenanceRepository provenanceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        receivedAuthHeader.set(null);
        receivedRequestBody.set(null);
        currentScenario.set(MockAiResponseScenario.SUCCESS_INFORMATION);

        jdbcTemplate.execute("DELETE FROM fitness.ai_runs");
        jdbcTemplate.execute("DELETE FROM fitness.user_roles");
        jdbcTemplate.execute("DELETE FROM fitness.user_roles");
        jdbcTemplate.execute("DELETE FROM fitness.student_profiles");
        jdbcTemplate.execute("DELETE FROM fitness.trainer_profiles");
        jdbcTemplate.execute("DELETE FROM fitness.users");

        seedUser(STUDENT_USER_ID, "student@fitplatform.local", "STUDENT");
    }

    private void seedUser(UUID userId, String email, String roleCode) {
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'hash', 'Test User', 'ACTIVE', clock_timestamp(), clock_timestamp())
                ON CONFLICT (id) DO NOTHING
                """, userId, email);

        if ("STUDENT".equals(roleCode)) {
            jdbcTemplate.update("""
                    INSERT INTO fitness.student_profiles (user_id, created_at, updated_at)
                    VALUES (?, clock_timestamp(), clock_timestamp())
                    ON CONFLICT (user_id) DO NOTHING
                    """, userId);
        }

        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp()
                FROM fitness.roles
                WHERE code = ?
                ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL
                """, userId, userId, roleCode);
    }

    private String bearer(UUID userId, String... roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(userId.toString())
                .claim("email", "test@fitplatform.local")
                .claim("roles", List.of(roles))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    @DisplayName("1. Real HTTP Call: SUCCESS_INFORMATION produces 200 OK, transmits auth header, and persists AI Run terminal state")
    void httpEndToEndInformationSuccess() throws Exception {
        currentScenario.set(MockAiResponseScenario.SUCCESS_INFORMATION);

        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Làm sao để phân bổ protein tối ưu cho phát triển cơ bắp?",
                "vi",
                null,
                UUID.randomUUID()
        );

        String responseContent = mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INFORMATION")))
                .andExpect(jsonPath("$.reasonCode", is("NONE")))
                .andExpect(jsonPath("$.answer", notNullValue()))
                .andExpect(jsonPath("$.citations[0].documentId", is("ISSN-PROTEIN-EXERCISE-2017-VI-04")))
                .andReturn().getResponse().getContentAsString();

        // 1. Verify that real HTTP request was transmitted to mock FastAPI with auth header
        assertThat(receivedAuthHeader.get()).isEqualTo(SERVICE_KEY);
        assertThat(receivedRequestBody.get()).contains("F01_GENERAL_KNOWLEDGE");

        // 2. Verify AI Run in database reached terminal SUCCEEDED
        JsonNode respNode = objectMapper.readTree(responseContent);
        UUID runId = UUID.fromString(respNode.get("runId").asText());

        Optional<AiRun> runOpt = aiRunRepository.findById(runId);
        assertThat(runOpt).isPresent();
        AiRun run = runOpt.get();
        assertThat(run.status()).isEqualTo(AiRunStatus.SUCCEEDED);
        assertThat(run.outputPayload()).contains("ISSN-PROTEIN-EXERCISE-2017-VI-04");
        assertThat(run.completedAt()).isNotNull();
    }

    @Test
    @DisplayName("2. Real HTTP Call: SUCCESS_INSUFFICIENT_DATA produces 200 OK without answer or citations")
    void httpEndToEndInsufficientData() throws Exception {
        currentScenario.set(MockAiResponseScenario.SUCCESS_INSUFFICIENT_DATA);

        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Phương pháp tập luyện này có hiệu quả không?",
                "vi",
                null,
                UUID.randomUUID()
        );

        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INSUFFICIENT_DATA")))
                .andExpect(jsonPath("$.reasonCode", is("NO_RELEVANT_EVIDENCE")))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.citations").doesNotExist());
    }

    @Test
    @DisplayName("3. Real HTTP Call: BLOCKED_GUARDRAIL produces 200 OK with BLOCKED resultType")
    void httpEndToEndBlockedGuardrail() throws Exception {
        currentScenario.set(MockAiResponseScenario.BLOCKED_GUARDRAIL);

        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Cách sử dụng steroid để tăng cơ nhanh?",
                "vi",
                null,
                UUID.randomUUID()
        );

        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("REJECTED_BY_VALIDATOR")))
                .andExpect(jsonPath("$.resultType", is("BLOCKED")))
                .andExpect(jsonPath("$.reasonCode", is("RULE_BLOCKED")))
                .andExpect(jsonPath("$.answer").doesNotExist());
    }

    @Test
    @DisplayName("4. Real HTTP Call: INVALID_OUTPUT_SCHEMA rejected by validator -> 502 Bad Gateway and REJECTED_BY_VALIDATOR run status")
    void httpEndToEndInvalidOutputSchemaRejection() throws Exception {
        currentScenario.set(MockAiResponseScenario.INVALID_OUTPUT_SCHEMA);

        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Câu hỏi trả về output thiếu citations",
                "vi",
                null,
                UUID.randomUUID()
        );

        String responseContent = mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.runStatus", is("REJECTED_BY_VALIDATOR")))
                .andExpect(jsonPath("$.reasonCode", is("VALIDATION_FAILED")))
                .andReturn().getResponse().getContentAsString();

        JsonNode respNode = objectMapper.readTree(responseContent);
        UUID runId = UUID.fromString(respNode.get("runId").asText());

        Optional<AiRun> runOpt = aiRunRepository.findById(runId);
        assertThat(runOpt).isPresent();
        assertThat(runOpt.get().status()).isEqualTo(AiRunStatus.REJECTED_BY_VALIDATOR);
    }

    @Test
    @DisplayName("5. Real HTTP Call: CORRUPTED_CITATION_SCOPE (citation outside envelope scope) rejected by validator -> 502 Bad Gateway")
    void httpEndToEndCitationScopeViolationRejection() throws Exception {
        currentScenario.set(MockAiResponseScenario.CORRUPTED_CITATION_SCOPE);

        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Câu hỏi trả về citation nguồn lạ",
                "vi",
                null,
                UUID.randomUUID()
        );

        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.runStatus", is("REJECTED_BY_VALIDATOR")))
                .andExpect(jsonPath("$.reasonCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("6. Real HTTP Call: MISMATCHED_RUN_ID rejected -> 500 Internal Server Error, run marked FAILED")
    void httpEndToEndMismatchedRunId() throws Exception {
        currentScenario.set(MockAiResponseScenario.MISMATCHED_RUN_ID);

        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Câu hỏi trả về runId bị mismatch",
                "vi",
                null,
                UUID.randomUUID()
        );

        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.runStatus", is("FAILED")))
                .andExpect(jsonPath("$.reasonCode", is("PROCESSING_ERROR")));
    }

    @Test
    @DisplayName("7. Real HTTP Call: Upstream HTTP 502 -> 502 Bad Gateway, run marked FAILED with MODEL_ERROR")
    void httpEndToEndProvider502() throws Exception {
        currentScenario.set(MockAiResponseScenario.HTTP_502_PROVIDER_ERROR);

        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Câu hỏi gặp lỗi upstream 502",
                "vi",
                null,
                UUID.randomUUID()
        );

        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.runStatus", is("FAILED")))
                .andExpect(jsonPath("$.reasonCode", is("MODEL_ERROR")));
    }

    @Test
    @DisplayName("8. Real HTTP Call: Upstream HTTP 504 -> 504 Gateway Timeout, run marked FAILED with TIMEOUT")
    void httpEndToEndProvider504Timeout() throws Exception {
        currentScenario.set(MockAiResponseScenario.HTTP_504_GATEWAY_TIMEOUT);

        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Câu hỏi gặp lỗi upstream 504",
                "vi",
                null,
                UUID.randomUUID()
        );

        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.runStatus", is("FAILED")))
                .andExpect(jsonPath("$.reasonCode", is("TIMEOUT")));
    }
}
