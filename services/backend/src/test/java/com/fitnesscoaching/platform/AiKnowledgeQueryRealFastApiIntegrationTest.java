package com.fitnesscoaching.platform;

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

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full End-to-End Integration Test executing against a REAL FastAPI process running Uvicorn.
 *
 * Verifies the complete HTTP communication chain:
 * MockMvc (Client) -> Spring Boot (AiKnowledgeQueryController)
 *                  -> Orchestrator (AiKnowledgeOrchestrationService)
 *                  -> HttpF01AiServiceClient (HTTP Client)
 *                  -> Real FastAPI Uvicorn Process (FastAPI app, routes, security, Pydantic schemas, SimulatedF01KnowledgeProvider)
 *                  -> Runtime Output Validator (F01RuntimeOutputValidator)
 *                  -> Database Persistence (PostgreSQL / Testcontainers: ai_runs, ai_context_snapshots, ai_validation_results)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("F01 End-to-End Real FastAPI Process Integration Tests")
class AiKnowledgeQueryRealFastApiIntegrationTest {

    private static final UUID STUDENT_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SERVICE_KEY = "test-real-fastapi-service-key-99999";

    private static Process fastApiProcess;
    private static int fastApiPort;

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_real_fastapi_integration")
            .withUsername("fitness_app")
            .withPassword("testpass123");

    @BeforeAll
    static void startServices() throws Exception {
        postgres.start();

        // Find free port for FastAPI uvicorn
        try (ServerSocket socket = new ServerSocket(0)) {
            fastApiPort = socket.getLocalPort();
        }

        // Determine Python / Uvicorn executable in virtualenv
        File projectDir = new File(System.getProperty("user.dir")).getParentFile().getParentFile();
        File venvPython = new File(projectDir, "services/ai-service/.venv/Scripts/python.exe");
        File aiServiceDir = new File(projectDir, "services/ai-service");

        if (!venvPython.exists()) {
            throw new IllegalStateException("FastAPI virtual environment python not found at: " + venvPython.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(
                venvPython.getAbsolutePath(),
                "-m", "uvicorn",
                "app.main:app",
                "--port", String.valueOf(fastApiPort),
                "--host", "127.0.0.1"
        );
        pb.directory(aiServiceDir);
        pb.environment().put("INTERNAL_SERVICE_KEY", SERVICE_KEY);
        pb.environment().put("F01_MOCK_ENABLED", "true");
        pb.redirectErrorStream(true);

        fastApiProcess = pb.start();

        // Wait up to 10 seconds for FastAPI /health to be responsive
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest healthReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + fastApiPort + "/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        boolean ready = false;
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = client.send(healthReq, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    ready = true;
                    break;
                }
            } catch (Exception ignored) {
                Thread.sleep(300);
            }
        }

        if (!ready) {
            fastApiProcess.destroyForcibly();
            throw new IllegalStateException("FastAPI uvicorn process failed to start on port " + fastApiPort);
        }
    }

    @AfterAll
    static void stopServices() {
        if (fastApiProcess != null && fastApiProcess.isAlive()) {
            fastApiProcess.destroyForcibly();
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

        // Configure Spring to use HTTP mode pointing to real FastAPI process
        registry.add("ai.service.mode", () -> "http");
        registry.add("ai.service.base-url", () -> "http://127.0.0.1:" + fastApiPort);
        registry.add("ai.service.internal-service-key", () -> SERVICE_KEY);
        registry.add("ai.service.enabled", () -> "true");
        registry.add("ai.service.connect-timeout-ms", () -> "5000");
        registry.add("ai.service.read-timeout-ms", () -> "10000");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiRunRepository runRepository;

    @Autowired
    private AiProvenanceRepository provenanceRepository;

    private String validJwtToken;

    @BeforeEach
    void setUpTestData() {
        jdbcTemplate.update("DELETE FROM fitness.ai_validation_results");
        jdbcTemplate.update("DELETE FROM fitness.ai_context_snapshots");
        jdbcTemplate.update("DELETE FROM fitness.ai_runs");

        // Seed student user and profile in postgres testcontainer
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, 'student.realfastapi@example.com', 'hash', 'Real FastAPI Student', 'ACTIVE', clock_timestamp(), clock_timestamp())
                ON CONFLICT (id) DO NOTHING
                """, STUDENT_USER_ID);

        jdbcTemplate.update("""
                INSERT INTO fitness.student_profiles (user_id, created_at, updated_at)
                VALUES (?, clock_timestamp(), clock_timestamp())
                ON CONFLICT (user_id) DO NOTHING
                """, STUDENT_USER_ID);

        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp()
                FROM fitness.roles
                WHERE code = 'STUDENT'
                ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL
                """, STUDENT_USER_ID, STUDENT_USER_ID);

        validJwtToken = generateJwtToken(STUDENT_USER_ID, List.of("STUDENT"));
    }

    private String generateJwtToken(UUID userId, List<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.getAccessTokenTtl()))
                .subject(userId.toString())
                .claim("roles", roles)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    @DisplayName("1. Real FastAPI: Success INFORMATION query through full stack with persistence")
    void testRealFastApiSuccessInformation() throws Exception {
        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Phân bổ lượng protein và leucine trong từng bữa ăn như thế nào để tối đa hóa MPS?",
                "vi",
                null,
                UUID.randomUUID()
        );

        String responseBody = mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", notNullValue()))
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INFORMATION")))
                .andExpect(jsonPath("$.reasonCode", is("NONE")))
                .andExpect(jsonPath("$.answer", notNullValue()))
                .andExpect(jsonPath("$.citations", notNullValue()))
                .andExpect(jsonPath("$.citations[0].documentId", is("ISSN-PROTEIN-EXERCISE-2017-VI-04")))
                .andExpect(jsonPath("$.citations[0].sourceId", is("ISSN-PROTEIN-EXERCISE-2017")))
                .andExpect(jsonPath("$.generationMetadata.provider", is("simulated")))
                .andReturn().getResponse().getContentAsString();

        // Verify terminal database persistence
        String runIdStr = objectMapper.readTree(responseBody).get("runId").asText();
        UUID runId = UUID.fromString(runIdStr);

        Optional<AiRun> runOpt = runRepository.findById(runId);
        assertThat(runOpt).isPresent();
        AiRun run = runOpt.get();
        assertThat(run.status()).isEqualTo(AiRunStatus.SUCCEEDED);
        assertThat(run.outputPayload()).contains("INFORMATION");
        assertThat(run.outputPayload()).contains("NONE");

        // Verify provenance validation result in fitness.ai_validation_results
        var valResults = provenanceRepository.findValidationResultsByRunId(runId);
        assertThat(valResults).isNotEmpty();
        assertThat(valResults.get(0).passed()).isTrue();
        assertThat(valResults.get(0).validatorCode()).isEqualTo("F01_SCHEMA_DOMAIN_VALIDATOR");
    }

    @Test
    @DisplayName("2. Real FastAPI: Trigger insufficient data scenario (returns 200 INSUFFICIENT_DATA)")
    void testRealFastApiInsufficientData() throws Exception {
        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "trigger_insufficient_data: hỏi về phương pháp không có trong thư viện khoa học",
                "vi",
                null,
                UUID.randomUUID()
        );

        String responseBody = mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INSUFFICIENT_DATA")))
                .andExpect(jsonPath("$.reasonCode", is("NO_RELEVANT_EVIDENCE")))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        UUID runId = UUID.fromString(objectMapper.readTree(responseBody).get("runId").asText());
        Optional<AiRun> runOpt = runRepository.findById(runId);
        assertThat(runOpt).isPresent();
        assertThat(runOpt.get().status()).isEqualTo(AiRunStatus.SUCCEEDED);
        assertThat(runOpt.get().outputPayload()).contains("INSUFFICIENT_DATA");
    }

    @Test
    @DisplayName("3. Real FastAPI: Policy guardrail BLOCKED query (returns 200 BLOCKED / RULE_BLOCKED)")
    void testRealFastApiBlockedPolicy() throws Exception {
        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Cách sử dụng steroid và chất kích thích doping trong tập luyện thể hình an toàn?",
                "vi",
                null,
                UUID.randomUUID()
        );

        String responseBody = mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("REJECTED_BY_VALIDATOR")))
                .andExpect(jsonPath("$.resultType", is("BLOCKED")))
                .andExpect(jsonPath("$.reasonCode", is("RULE_BLOCKED")))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        UUID runId = UUID.fromString(objectMapper.readTree(responseBody).get("runId").asText());
        Optional<AiRun> runOpt = runRepository.findById(runId);
        assertThat(runOpt).isPresent();
        assertThat(runOpt.get().status()).isEqualTo(AiRunStatus.REJECTED_BY_VALIDATOR);
        assertThat(runOpt.get().outputPayload()).contains("BLOCKED");
        assertThat(runOpt.get().outputPayload()).contains("RULE_BLOCKED");
    }

    @Test
    @DisplayName("4. Real FastAPI: Trigger provider error simulation (returns 502 BAD_GATEWAY)")
    void testRealFastApiTriggerProviderError() throws Exception {
        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "trigger_provider_error: giả lập lỗi provider từ phía mô hình",
                "vi",
                null,
                UUID.randomUUID()
        );

        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.runStatus", is("FAILED")))
                .andExpect(jsonPath("$.reasonCode", is("MODEL_ERROR")))
                .andExpect(jsonPath("$.error.code", is("MODEL_ERROR")));
    }
}
