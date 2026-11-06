package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.HttpF04AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test verifying real HTTP communication between Spring Boot and
 * a running FastAPI process for F04 progress summarization.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("F04 Multi-Tier Real FastAPI HTTP Integration Tests")
class AiProgressSummaryRealFastApiIntegrationTest {

    private static final String SERVICE_KEY = "test-real-fastapi-service-key-f04";
    private static Process fastApiProcess;
    private static int fastApiPort;

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_f04_real_fastapi_integration")
            .withUsername("fitness_app")
            .withPassword("testpass123");

    @BeforeAll
    static void startServices() throws Exception {
        postgres.start();

        try (ServerSocket socket = new ServerSocket(0)) {
            fastApiPort = socket.getLocalPort();
        }

        File projectDir = new File(System.getProperty("user.dir")).getParentFile().getParentFile();
        File aiServiceDir = new File(projectDir, "services/ai-service");
        String pythonPath = System.getProperty("os.name").startsWith("Windows")
                ? ".venv/Scripts/python.exe" : ".venv/bin/python";
        File venvPython = new File(aiServiceDir, pythonPath);

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
        pb.redirectErrorStream(true);

        fastApiProcess = pb.start();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest healthReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + fastApiPort + "/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        boolean ready = false;
        long deadline = System.currentTimeMillis() + 15000;
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

        // Point to real running FastAPI HTTP server
        registry.add("ai.service.mode", () -> "http");
        registry.add("ai.service.base-url", () -> "http://127.0.0.1:" + fastApiPort);
        registry.add("ai.service.internal-service-key", () -> SERVICE_KEY);
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
    private JdbcTemplate jdbc;

    @Autowired
    private AiRunRepository aiRunRepository;

    @Autowired
    private AiProvenanceRepository provenanceRepository;

    private String generateToken(UUID userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.getAccessTokenTtl()))
                .subject(userId.toString())
                .claim("role", role)
                .claim("roles", List.of(role))
                .claim("token_type", "ACCESS")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private UUID createActiveStudent() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?,'hash','Student','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')",
                id, id + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='STUDENT'",
                id, id);
        jdbc.update("""
                INSERT INTO fitness.student_profiles(user_id, training_experience_level, available_days_per_week, preferred_session_minutes)
                VALUES (?, 'BEGINNER', 3, 60)
                """, id);
        return id;
    }

    @Test
    @DisplayName("End-to-End: Valid summary request invokes real FastAPI via HTTP, validates output, and persists AI Run")
    void validSummaryRequest_invokesFastApiHttp_andCompletesInformationRun() throws Exception {
        UUID studentId = createActiveStudent();
        String token = generateToken(studentId, "STUDENT");

        F04ProgressSummaryApiRequest request = new F04ProgressSummaryApiRequest(
                studentId,
                LocalDate.now().minusDays(14),
                LocalDate.now(),
                "Asia/Ho_Chi_Minh",
                null,
                UUID.randomUUID()
        );

        int initialWorkoutExecCount = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs", Integer.class);
        int initialMeasurementsCount = jdbc.queryForObject("SELECT count(*) FROM fitness.measurements", Integer.class);

        String responseJson = mockMvc.perform(post("/api/v1/ai/progress/summary")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INSUFFICIENT_DATA")))
                .andExpect(jsonPath("$.reasonCode", is("INSUFFICIENT_DATA")))
                .andExpect(jsonPath("$.runId", notNullValue()))
                .andExpect(jsonPath("$.summary", notNullValue()))
                .andExpect(jsonPath("$.sourceWatermark", notNullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var tree = objectMapper.readTree(responseJson);
        UUID runId = UUID.fromString(tree.get("runId").asText());

        // Verify AI Run state in PostgreSQL
        AiRun run = aiRunRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AiRunStatus.SUCCEEDED);
        assertThat(run.purpose()).isEqualTo("BUSINESS_ASSISTANCE");
        assertThat(run.outputPayload()).isNotBlank();

        // Verify Context Snapshot was saved
        assertThat(provenanceRepository.findSnapshotByRunId(runId)).isPresent();

        // Test Readback endpoint
        mockMvc.perform(get("/api/v1/ai/progress/summary/" + runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(runId.toString())))
                .andExpect(jsonPath("$.isStale", is(false)))
                .andExpect(jsonPath("$.needsRefresh", is(false)));

        // Domain invariant: INFORMATION flow must NOT mutate any domain tables
        int finalWorkoutExecCount = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs", Integer.class);
        int finalMeasurementsCount = jdbc.queryForObject("SELECT count(*) FROM fitness.measurements", Integer.class);
        assertThat(finalWorkoutExecCount).isEqualTo(initialWorkoutExecCount);
        assertThat(finalMeasurementsCount).isEqualTo(initialMeasurementsCount);
    }

    @Test
    @DisplayName("FastAPI authentication: invalid internal service key fails with 403")
    void fastApiInvalidServiceKey_failsAuthentication() {
        AiServiceProperties badProps = new AiServiceProperties(
                true, "http", "http://127.0.0.1:" + fastApiPort, "wrong-key", 3000, 5000
        );
        HttpF04AiServiceClient client = new HttpF04AiServiceClient(badProps);

        F04InternalContextEnvelope envelope = new F04InternalContextEnvelope(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "STUDENT",
                new F04ProgressContextDto(
                        new DateWindowDto(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14), "Asia/Ho_Chi_Minh"),
                        Instant.now(),
                        new WorkoutSectionDto("NO_DATA", null, null, null, null),
                        new MeasurementSectionDto("NO_DATA", null, null, List.of(), null),
                        new ContinuitySignalDto("RECENT_ACTIVITY_OBSERVED", 1, LocalDate.of(2026, 10, 13), List.of(), null),
                        new HistoricalLimitsSummaryDto(false, false, false, null),
                        "ENGINE:wm-test"
                )
        );

        assertThatThrownBy(() -> client.summarizeProgress(envelope))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("FastAPI AI service authentication failed");
    }

    @Test
    @DisplayName("Readback security: stranger actor is denied access (403)")
    void readback_strangerActor_isDenied() throws Exception {
        UUID studentId = createActiveStudent();
        String studentToken = generateToken(studentId, "STUDENT");

        F04ProgressSummaryApiRequest request = new F04ProgressSummaryApiRequest(
                studentId,
                LocalDate.now().minusDays(7),
                LocalDate.now(),
                "Asia/Ho_Chi_Minh",
                null,
                UUID.randomUUID()
        );

        String responseJson = mockMvc.perform(post("/api/v1/ai/progress/summary")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var tree = objectMapper.readTree(responseJson);
        UUID runId = UUID.fromString(tree.get("runId").asText());

        // Stranger tries to read the summary
        UUID strangerId = createActiveStudent();
        String strangerToken = generateToken(strangerId, "STUDENT");

        mockMvc.perform(get("/api/v1/ai/progress/summary/" + runId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }
}
