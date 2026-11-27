package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiAgentCrossLayerHttpE2EIntegrationTest {

    private static final UUID STUDENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID STUDENT_2_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID TRAINER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String INTERNAL_KEY = "test-e2e-internal-key-32chars!!";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("agent_e2e")
            .withUsername("fitness_app")
            .withPassword("testpass123");

    private static Process fastApiProcess;
    private static final int fastApiPort = freePort();
    static { postgres.start(); }

    private static int freePort() {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) { throw new IllegalStateException(ex); }
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (fastApiProcess != null) {
            fastApiProcess.destroy();
            if (!fastApiProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) fastApiProcess.destroyForcibly();
        }
    }

    private void startFastApi() throws Exception {
        if (fastApiProcess != null) return;
        var service = java.nio.file.Path.of("../ai-service").toAbsolutePath().normalize();
        String python = System.getProperty("os.name").toLowerCase().contains("win")
                ? ".venv/Scripts/python.exe" : ".venv/bin/python";
        var pb = new ProcessBuilder(service.resolve(python).toString(), "-m", "uvicorn",
                "agent_http_fixture:app", "--app-dir", "tests", "--host", "127.0.0.1", "--port", String.valueOf(fastApiPort));
        pb.directory(service.toFile());
        pb.environment().put("AGENT_HTTP_E2E", "true");
        pb.environment().put("INTERNAL_SERVICE_KEY", INTERNAL_KEY);
        pb.environment().put("BACKEND_BASE_URL", "http://127.0.0.1:" + localPort);
        pb.environment().put("OPENAI_API_KEY", "offline-test-key");
        pb.environment().put("RAG_RERANK_API_KEY", "offline-test-key");
        pb.environment().put("E2E_MODEL_URL", "http://127.0.0.1:" + fastApiPort + "/test/model");
        pb.environment().put("E2E_RERANK_URL", "http://127.0.0.1:" + fastApiPort + "/test/rerank");
        pb.redirectErrorStream(true).redirectOutput(java.nio.file.Path.of("target/agent-fastapi-http.log").toFile());
        fastApiProcess = pb.start();
        for (int i = 0; i < 100; i++) {
            try { if (controlRequest("/health", null).statusCode() == 200) return; }
            catch (IOException ignored) { }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Real FastAPI failed to start; see target/agent-fastapi-http.log");
    }

    private HttpResponse<String> controlRequest(String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + fastApiPort + path))
                .timeout(java.time.Duration.ofSeconds(2));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        var response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Fixture control HTTP response").isEqualTo(200);
        return response;
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
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO fitness, public;");
        registry.add("ai.service.mode", () -> "http");
        registry.add("ai.service.base-url", () -> "http://127.0.0.1:" + fastApiPort);
        registry.add("ai.service.internal-service-key", () -> INTERNAL_KEY);
        registry.add("ai.service.read-timeout-ms", () -> "5000");
    }

    @LocalServerPort private int localPort;
    @Autowired private com.fitnesscoaching.platform.modules.content.application.service.F01RunEvidenceTracker evidenceTracker;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.fitnesscoaching.platform.modules.content.adapter.out.embedding.DeterministicEmbeddingGenerator embeddingGenerator;
    @Autowired private CoachingSharingUseCase sharingUseCase;
    @MockitoSpyBean private ProgressEngineQuery progressEngineQuery;
    @MockitoSpyBean private WorkoutPlanQueryUseCase workoutPlanQueryUseCase;

    @BeforeEach
    void setUp() throws Exception {
        startFastApi();
        controlRequest("/test/control", "{\"waiting\":false,\"release\":true}");
        reset(progressEngineQuery, workoutPlanQueryUseCase);
        jdbcTemplate.execute("DELETE FROM fitness.ai_conversation_turns");
        jdbcTemplate.execute("DELETE FROM fitness.ai_conversations");
        jdbcTemplate.execute("TRUNCATE TABLE fitness.knowledge_documents, fitness.knowledge_processing_runs CASCADE");

        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, 'e2e@fit.local', 'hash', 'Student E2E', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                """, STUDENT_ID);
        jdbcTemplate.update("""
                INSERT INTO fitness.student_profiles (user_id, created_at, updated_at)
                VALUES (?, clock_timestamp(), clock_timestamp())
                ON CONFLICT (user_id) DO NOTHING
                """, STUDENT_ID);
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'STUDENT'
                ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL
                """, STUDENT_ID, STUDENT_ID);

        // Seed active verified WHO chunk so citation eligibility check passes
        UUID docId = UUID.randomUUID();
        UUID verId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_documents (id, document_code, source_id, title, document_type, created_by)
                VALUES (?, 'WHO-PA-SB-2020-VI-01', 'WHO-PA-SB-2020', 'WHO PA', 'GUIDELINE', ?)
                """, docId, STUDENT_ID);
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_versions (id, knowledge_document_id, version_number, version_label, status, technical_readiness, content_hash, content_path, created_by)
                VALUES (?, ?, 1, '1.0.0', 'ACTIVE', 'TECHNICAL_VERIFICATION_PASSED', 'hash-e2e', 'derivatives/who.md', ?)
                """, verId, docId, STUDENT_ID);
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (id, knowledge_version_id, run_type, operation_key, model_version_id, status, statistics)
                VALUES (?, ?, 'EMBED', ?, ?, 'SUCCEEDED', ?::jsonb)
                """, UUID.randomUUID(), verId, "op-" + UUID.randomUUID(), embeddingGenerator.modelVersionId(),
                "{\"index_ready\": true, \"embedding_model_version_id\": \"" + embeddingGenerator.modelVersionId() + "\"}");
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_chunks (id, knowledge_version_id, chunk_index, heading_path, content)
                VALUES (?, ?, 0, 'Adults', 'Adults should do 150-300 minutes of moderate aerobic activity.')
                """, chunkId, verId);
        float[] vec = embeddingGenerator.generateEmbedding("WHO physical activity");
        String vecStr = com.fitnesscoaching.platform.modules.content.adapter.out.persistence.JdbcKnowledgeEmbeddingRepository.vectorToString(vec);
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_embeddings (id, knowledge_chunk_id, embedding_model_version_id, embedding)
                VALUES (?, ?, ?, ?::vector)
                """, UUID.randomUUID(), chunkId, embeddingGenerator.modelVersionId(), vecStr);
    }

    private String bearer(UUID userId, String... roles) {
        Instant now = Instant.now();
        List<String> roleList = roles.length == 0 ? List.of("STUDENT") : List.of(roles);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(userId.toString())
                .claim("email", userId + "@fit.local")
                .claim("roles", roleList)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String createConversation() throws Exception {
        var result = mockMvc.perform(post("/api/v1/ai/conversations")
                .header("Authorization", bearer(STUDENT_ID)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Real FastAPI E2E\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("conversationId").asText();
    }

    private MvcResult turn(String conversation, String message, UUID turnId) throws Exception {
        return mockMvc.perform(post("/api/v1/ai/conversations/" + conversation + "/turns")
                .header("Authorization", bearer(STUDENT_ID)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("message", message, "turnId", turnId))))
                .andExpect(status().isOk()).andReturn();
    }

    @Test
    @DisplayName("Real FastAPI + bounded loop + HTTP model/reranker fixtures + Spring retrieval and persistence")
    void realAgentMultiSearchAndClarification() throws Exception {
        String conversation = createConversation();
        var clarify = turn(conversation, "CLARIFY", UUID.randomUUID());
        assertThat(objectMapper.readTree(clarify.getResponse().getContentAsString()).get("resultType").asText())
                .isEqualTo("CLARIFICATION_REQUIRED");
        var result = objectMapper.readTree(turn(conversation, "FOLLOW_UP WHO", UUID.randomUUID())
                .getResponse().getContentAsString());
        assertThat(result.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.get("citations").size()).isEqualTo(1);
        assertThat(result.get("citations").get(0).get("quoteOrExcerpt").asText()).contains("150-300 minutes");
        String steps = jdbcTemplate.queryForObject("SELECT steps_json::text FROM fitness.ai_conversation_turns WHERE id = ?::uuid",
                String.class, result.get("turnId").asText());
        assertThat(steps.split("search_knowledge", -1).length - 1).isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM fitness.ai_runs WHERE id = ?::uuid",
                String.class, result.get("aiRunId").asText())).isEqualTo("SUCCEEDED");
        assertThat(evidenceTracker.getRunEvidence(UUID.fromString(result.get("aiRunId").asText()))).isEmpty();
        // Real tool gateway callback, not a response stub.
        var domain = objectMapper.readTree(turn(conversation, "DOMAIN plan", UUID.randomUUID())
                .getResponse().getContentAsString());
        assertThat(domain.get("resultType").asText()).isEqualTo("INSUFFICIENT_DATA");
        String domainSteps = jdbcTemplate.queryForObject("SELECT steps_json::text FROM fitness.ai_conversation_turns WHERE id = ?::uuid",
                String.class, domain.get("turnId").asText());
        assertThat(domainSteps).contains("get_current_plan").doesNotContain("DENIED");
    }

    @Test
    void cancellationDuringRealModelCallPreventsLateCompletionAndFurtherTools() throws Exception {
        String conversation = createConversation();
        UUID turnId = UUID.randomUUID();
        controlRequest("/test/control", "{\"waiting\":false,\"release\":false}");
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> turn(conversation, "SLOW WHO", turnId));
            try {
                awaitModelWaiting();
                mockMvc.perform(post("/api/v1/ai/conversations/" + conversation + "/turns/" + turnId + "/cancel")
                        .header("Authorization", bearer(STUDENT_ID)))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CANCELLED")));
            } finally { controlRequest("/test/control", "{\"release\":true}"); }
            var response = objectMapper.readTree(pending.get(12, java.util.concurrent.TimeUnit.SECONDS)
                    .getResponse().getContentAsString());
            assertThat(response.get("status").asText()).isEqualTo("CANCELLED");
            assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM fitness.ai_runs WHERE id = ?::uuid",
                    String.class, response.get("aiRunId").asText())).isEqualTo("CANCELLED");
            assertThat(jdbcTemplate.queryForObject("SELECT assistant_message FROM fitness.ai_conversation_turns WHERE id = ?",
                    String.class, turnId)).isNull();
        }
    }

    @Test
    void revokedAccountDuringModelCallCannotReceiveLateAnswer() throws Exception {
        String conversation = createConversation();
        controlRequest("/test/control", "{\"waiting\":false,\"release\":false}");
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> turn(conversation, "SLOW WHO", UUID.randomUUID()));
            try {
                awaitModelWaiting();
                jdbcTemplate.update("UPDATE fitness.users SET status = 'SUSPENDED' WHERE id = ?", STUDENT_ID);
            } finally { controlRequest("/test/control", "{\"release\":true}"); }
            var result = objectMapper.readTree(pending.get(12, java.util.concurrent.TimeUnit.SECONDS)
                    .getResponse().getContentAsString());
            assertThat(result.get("status").asText()).isEqualTo("FAILED");
            assertThat(result.get("citations").size()).isZero();
            assertThat(result.get("assistantMessage").isNull()).isTrue();
        }
    }

    @Test
    void springTimeoutPersistsTerminalFailure() throws Exception {
        String conversation = createConversation();
        controlRequest("/test/control", "{\"waiting\":false,\"release\":false}");
        try {
            var result = objectMapper.readTree(turn(conversation, "SLOW WHO", UUID.randomUUID())
                    .getResponse().getContentAsString());
            assertThat(result.get("status").asText()).isEqualTo("FAILED");
            assertThat(result.get("reasonCode").asText()).isEqualTo("TIMEOUT");
        } finally { controlRequest("/test/control", "{\"release\":true}"); }
    }

    private void awaitModelWaiting() throws Exception {
        for (int i = 0; i < 100; i++) {
            if (objectMapper.readTree(controlRequest("/test/control", null).body()).get("waiting").asBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Real agent did not reach the mock model barrier");
    }

    private void seedStudentAndTrainer(UUID studentId, UUID trainerId, boolean grantAuthority) {
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, ?, 'hash', 'Student E2E', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                """, studentId, studentId + "@fit.local");
        jdbcTemplate.update("INSERT INTO fitness.student_profiles (user_id, created_at, updated_at) VALUES (?, clock_timestamp(), clock_timestamp()) ON CONFLICT (user_id) DO NOTHING", studentId);
        jdbcTemplate.update("INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at) SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'STUDENT' ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL", studentId, studentId);

        if (trainerId != null) {
            jdbcTemplate.update("""
                    INSERT INTO fitness.users (id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at)
                    VALUES (?, ?, 'hash', 'Trainer E2E', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                    ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                    """, trainerId, trainerId + "@fit.local");
            jdbcTemplate.update("""
                    INSERT INTO fitness.trainer_profiles (user_id, verification_status, is_accepting_students, created_at, updated_at)
                    VALUES (?, 'VERIFIED', true, clock_timestamp(), clock_timestamp())
                    ON CONFLICT (user_id) DO UPDATE SET verification_status = 'VERIFIED', is_accepting_students = true
                    """, trainerId);
            jdbcTemplate.update("INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at) SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'TRAINER' ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL", trainerId, trainerId);

            if (grantAuthority) {
                UUID relId = UUID.randomUUID();
                jdbcTemplate.update("""
                        INSERT INTO fitness.coaching_relationships
                        (id, student_id, trainer_id, status, requested_by, requested_at, accepted_at, started_at)
                        VALUES (?, ?, ?, 'ACTIVE', ?, clock_timestamp() - interval '2 days', clock_timestamp() - interval '2 days', clock_timestamp() - interval '2 days')
                        """, relId, studentId, trainerId, studentId);
                jdbcTemplate.update("""
                        INSERT INTO fitness.coaching_periods
                        (id, student_id, mode, coaching_relationship_id, trainer_id, started_at, created_by)
                        VALUES (?, ?, 'HUMAN_COACH', ?, ?, clock_timestamp() - interval '2 days', ?)
                        """, UUID.randomUUID(), studentId, relId, trainerId, trainerId);
                jdbcTemplate.update("""
                        INSERT INTO fitness.data_sharing_permissions
                        (id, relationship_id, student_id, trainer_id, data_scope, decision, access_level, valid_from, granted_by)
                        VALUES (?, ?, ?, ?, 'WORKOUT_PLAN', 'ALLOW', 'MANAGE'::fitness.data_access_level, clock_timestamp() - interval '1 day', ?)
                        """, UUID.randomUUID(), relId, studentId, trainerId, studentId);
                jdbcTemplate.update("""
                        INSERT INTO fitness.data_sharing_permissions
                        (id, relationship_id, student_id, trainer_id, data_scope, decision, access_level, valid_from, granted_by)
                        VALUES (?, ?, ?, ?, 'WORKOUT_HISTORY', 'ALLOW', 'VIEW'::fitness.data_access_level, clock_timestamp() - interval '1 day', ?)
                        """, UUID.randomUUID(), relId, studentId, trainerId, studentId);
            }
        }
    }

    private void seedActivePlanForStudent(UUID studentId, UUID trainerId) {
        UUID planId = UUID.randomUUID();
        UUID verId = UUID.randomUUID();
        UUID periodId = jdbcTemplate.queryForList(
                "SELECT id FROM fitness.coaching_periods WHERE student_id = ? AND ended_at IS NULL LIMIT 1",
                UUID.class, studentId).stream().findFirst().orElse(null);
        String source = trainerId != null ? "TRAINER" : "STUDENT";
        String ownerType = trainerId != null ? "TRAINER" : "STUDENT";
        UUID ownerId = trainerId != null ? trainerId : studentId;
        jdbcTemplate.update("""
                INSERT INTO fitness.workout_plans(id, student_id, coaching_period_id, name, source, status, created_by, decision_owner_type, decision_owner_id)
                VALUES (?, ?, ?, 'Hypertrophy Foundation', ?::fitness.plan_source, 'DRAFT', ?, ?::fitness.workout_plan_decision_owner, ?)
                """, planId, studentId, periodId, source, ownerId, ownerType, ownerId);
        jdbcTemplate.update("""
                INSERT INTO fitness.workout_plan_versions(id, workout_plan_id, version_number, change_level, created_by)
                VALUES (?, ?, 1, 'INITIAL', ?)
                """, verId, planId, studentId);
        jdbcTemplate.update("""
                INSERT INTO fitness.workout_plan_sessions(id, workout_plan_version_id, week_number, day_number, sequence_number, name, supervision_requirement)
                VALUES (?, ?, 1, 1, 1, 'Upper Body', 'SELF_ALLOWED'::fitness.supervision_requirement)
                """, UUID.randomUUID(), verId);
        jdbcTemplate.update("""
                UPDATE fitness.workout_plan_versions
                SET effective_from = clock_timestamp(), locked_at = clock_timestamp(), locked_by = ?, lock_reason = 'ACTIVATED'
                WHERE id = ?
                """, studentId, verId);
        jdbcTemplate.update("""
                UPDATE fitness.workout_plans
                SET status = 'ACTIVE', version = version + 1, assigned_by = ?, assigned_at = clock_timestamp()
                WHERE id = ?
                """, studentId, planId);
    }

    @Test
    @DisplayName("Cross-layer HTTP E2E: Student and authorized Trainer read all 3 domain tools without business mutations")
    void studentAndTrainerReadAllThreeDomainToolsWithoutMutations() throws Exception {
        UUID s1 = UUID.randomUUID();
        UUID t1 = UUID.randomUUID();
        seedStudentAndTrainer(s1, t1, true);
        seedActivePlanForStudent(s1, t1);

        int plansBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fitness.workout_plans", Integer.class);
        int recsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fitness.ai_recommendations", Integer.class);

        // 1. Student reads own plan, progress, and availability through full Conversation -> FastAPI -> Gateway -> Domain path
        var sConvRes = mockMvc.perform(post("/api/v1/ai/conversations")
                .header("Authorization", bearer(s1, "STUDENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Student Domain E2E\"}"))
                .andExpect(status().isOk()).andReturn();
        String studentConv = objectMapper.readTree(sConvRes.getResponse().getContentAsString()).get("conversationId").asText();
        var sTurnRes = mockMvc.perform(post("/api/v1/ai/conversations/" + studentConv + "/turns")
                .header("Authorization", bearer(s1, "STUDENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"DOMAIN_ALL student\"}"))
                .andExpect(status().isOk()).andReturn();
        var studentRes = objectMapper.readTree(sTurnRes.getResponse().getContentAsString());
        assertThat(studentRes.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(studentRes.get("resultType").asText()).isEqualTo("INFORMATION");
        assertThat(studentRes.get("assistantMessage").asText()).contains("Hypertrophy Foundation");
        String studentSteps = jdbcTemplate.queryForObject("SELECT steps_json::text FROM fitness.ai_conversation_turns WHERE id = ?::uuid",
                String.class, studentRes.get("turnId").asText());
        assertThat(studentSteps).contains("get_current_plan", "get_progress_summary", "get_availability").doesNotContain("DENIED");

        // 2. Authorized Trainer reads Student's plan, progress, and availability
        var trainerConvRes = mockMvc.perform(post("/api/v1/ai/conversations")
                .header("Authorization", bearer(t1, "TRAINER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorRole\":\"TRAINER\",\"subjectId\":\"" + s1 + "\",\"title\":\"Trainer E2E\"}"))
                .andExpect(status().isOk()).andReturn();
        String trainerConv = objectMapper.readTree(trainerConvRes.getResponse().getContentAsString()).get("conversationId").asText();
        var trainerTurnRes = mockMvc.perform(post("/api/v1/ai/conversations/" + trainerConv + "/turns")
                .header("Authorization", bearer(t1, "TRAINER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"DOMAIN_ALL trainer\"}"))
                .andExpect(status().isOk()).andReturn();
        var trainerJson = objectMapper.readTree(trainerTurnRes.getResponse().getContentAsString());
        assertThat(trainerJson.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(trainerJson.get("resultType").asText()).isEqualTo("INFORMATION");
        assertThat(trainerJson.get("assistantMessage").asText()).contains("Hypertrophy Foundation");

        // Verify zero business mutations
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fitness.workout_plans", Integer.class)).isEqualTo(plansBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fitness.ai_recommendations", Integer.class)).isEqualTo(recsBefore);
    }

    @Test
    @DisplayName("Cross-layer HTTP E2E: Cross-user, forged subject, and unauthorized Trainer blocked before domain reads")
    void crossUserForgedSubjectAndUnauthorizedTrainerBlocked() throws Exception {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID t1 = UUID.randomUUID();
        seedStudentAndTrainer(s1, t1, false);
        seedStudentAndTrainer(s2, null, false);
        seedActivePlanForStudent(s1, null);

        var sConvRes = mockMvc.perform(post("/api/v1/ai/conversations")
                .header("Authorization", bearer(s1, "STUDENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Owned Conv\"}"))
                .andExpect(status().isOk()).andReturn();
        String studentConv = objectMapper.readTree(sConvRes.getResponse().getContentAsString()).get("conversationId").asText();

        reset(progressEngineQuery, workoutPlanQueryUseCase);

        // Cross-user conversation read -> 403
        mockMvc.perform(get("/api/v1/ai/conversations/" + studentConv)
                .header("Authorization", bearer(s2, "STUDENT")))
                .andExpect(status().isForbidden());

        // Forged subjectId by s2 -> 403 (cannot scope to s1)
        mockMvc.perform(post("/api/v1/ai/conversations")
                .header("Authorization", bearer(s2, "STUDENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorRole\":\"TRAINER\",\"subjectId\":\"" + s1 + "\"}"))
                .andExpect(status().isForbidden());

        // Trainer without coaching authority/consent -> 403
        mockMvc.perform(post("/api/v1/ai/conversations")
                .header("Authorization", bearer(t1, "TRAINER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorRole\":\"TRAINER\",\"subjectId\":\"" + s1 + "\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(progressEngineQuery, workoutPlanQueryUseCase);
    }

    @Test
    @DisplayName("Cross-layer HTTP E2E: Mid-turn consent revocation denies next domain tool before data read and leaks nothing")
    void midTurnTrainerRevocationDeniesSubsequentToolBeforeDomainRead() throws Exception {
        UUID s1 = UUID.randomUUID();
        UUID t1 = UUID.randomUUID();
        seedStudentAndTrainer(s1, t1, true);
        seedActivePlanForStudent(s1, t1);

        String trainerToken = bearer(t1, "TRAINER");
        var trainerConvRes = mockMvc.perform(post("/api/v1/ai/conversations")
                .header("Authorization", trainerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorRole\":\"TRAINER\",\"subjectId\":\"" + s1 + "\",\"title\":\"Revoke E2E\"}"))
                .andExpect(status().isOk()).andReturn();
        String trainerConv = objectMapper.readTree(trainerConvRes.getResponse().getContentAsString()).get("conversationId").asText();

        controlRequest("/test/control", "{\"waiting\":false,\"release\":false}");
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> mockMvc.perform(post("/api/v1/ai/conversations/" + trainerConv + "/turns")
                    .header("Authorization", trainerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"DOMAIN_REVOKE_MID_TURN\"}")).andReturn());
            try {
                awaitModelWaiting();
                // Revoke all permissions & end relationship for this (s1, t1) pair before get_progress_summary runs
                UUID relId = jdbcTemplate.queryForObject(
                        "SELECT id FROM fitness.coaching_relationships WHERE student_id = ? AND trainer_id = ?",
                        UUID.class, s1, t1);
                var perms = jdbcTemplate.queryForList(
                        "SELECT id, version FROM fitness.data_sharing_permissions WHERE relationship_id = ?", relId);
                for (var p : perms) {
                    sharingUseCase.revoke(s1, relId, (UUID) p.get("id"), ((Number) p.get("version")).longValue(), "Mid-turn E2E revoke", UUID.randomUUID());
                }
                jdbcTemplate.update("UPDATE fitness.coaching_relationships SET status = 'ENDED', ended_at = clock_timestamp(), version = version + 1 WHERE id = ?", relId);
                reset(progressEngineQuery);
            } finally {
                controlRequest("/test/control", "{\"release\":true}");
            }
            var turnResult = pending.get(12, java.util.concurrent.TimeUnit.SECONDS);
            var json = objectMapper.readTree(turnResult.getResponse().getContentAsString());
            // Outer turn fails closed because consent/authority was revoked before completion
            assertThat(json.get("status").asText()).isEqualTo("FAILED");
            assertThat(json.get("assistantMessage").isNull()).isTrue();
            // Sensitive progress service MUST NEVER have been invoked after revocation
            verify(progressEngineQuery, never()).query(any(), any(), any(), any(), any(), any());
            // Model observation for get_progress_summary MUST be DENIED with empty data
            var lastObs = objectMapper.readTree(controlRequest("/test/control", null).body()).get("last_observations");
            boolean sawDeniedProgress = false;
            for (var obs : lastObs) {
                if ("get_progress_summary".equals(obs.get("toolName").asText())) {
                    assertThat(obs.get("status").asText()).isEqualTo("DENIED");
                    assertThat(obs.get("data").size()).isZero();
                    sawDeniedProgress = true;
                }
            }
            assertThat(sawDeniedProgress).isTrue();
        }
    }

    @Test
    @DisplayName("Cross-layer HTTP E2E: Missing domain data returns INSUFFICIENT_DATA without fabricating zero")
    void missingDomainDataReturnsInsufficientDataWithoutZeroFabrication() throws Exception {
        String studentConv = createConversation();
        var res = objectMapper.readTree(turn(studentConv, "DOMAIN_ALL empty", UUID.randomUUID())
                .getResponse().getContentAsString());
        assertThat(res.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(res.get("resultType").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(res.get("assistantMessage").isNull() || res.get("assistantMessage").asText().isBlank()).isTrue();
    }
}
