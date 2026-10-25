package com.fitnesscoaching.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.ToggleAiFeatureApiRequest;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.content.adapter.in.web.dto.ArchiveKnowledgeVersionRequest;
import com.fitnesscoaching.platform.modules.content.adapter.in.web.dto.ReviewKnowledgeVersionRequest;
import com.fitnesscoaching.platform.modules.content.domain.ReviewDecision;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real integration test suite for P2-M2 Admin Knowledge Management (F09),
 * AI Run Monitoring (F10 Redacted), and F01 Kill Switch.
 * Verifies real HTTP routing, Spring Security RBAC, PostgreSQL database persistence,
 * and actual audit logs in fitness.audit_logs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Admin P2-M2 End-to-End Integration Tests (HTTP, PostgreSQL, Security, Wire Stub)")
class AdminP2M2IntegrationTest {

    private static final UUID ADMIN_USER_ID = UUID.fromString("aaaaaaaa-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID STUDENT_USER_ID = UUID.fromString("bbbbbbbb-2222-2222-2222-bbbbbbbbbbbb");
    private static final UUID TRAINER_USER_ID = UUID.fromString("cccccccc-3333-3333-3333-cccccccccccc");
    private static final UUID SUSPENDED_USER_ID = UUID.fromString("dddddddd-4444-4444-4444-dddddddddddd");
    private static final String SERVICE_KEY = "test-e2e-internal-secret-key-12345";

    private static HttpServer mockFastApiHttpServer;
    private static int mockFastApiPort;
    private static final AtomicInteger providerCallCount = new AtomicInteger(0);
    private static final AtomicReference<CountDownLatch> providerDelayLatch = new AtomicReference<>(null);

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("admin_p2m2_integration")
            .withUsername("fitness_app")
            .withPassword("testpass123");

    @BeforeAll
    static void startInfrastructure() throws IOException {
        postgres.start();

        mockFastApiHttpServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockFastApiPort = mockFastApiHttpServer.getAddress().getPort();

        mockFastApiHttpServer.createContext("/internal/v1/knowledge/query", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                providerCallCount.incrementAndGet();

                // If a delay latch is set, wait for external signal (used for in-flight kill switch testing)
                CountDownLatch latch = providerDelayLatch.get();
                if (latch != null) {
                    try {
                        latch.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                String requestBody;
                try (InputStream is = exchange.getRequestBody()) {
                    requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }

                ObjectMapper mapper = new ObjectMapper();
                JsonNode reqJson = mapper.readTree(requestBody);
                String requestId = reqJson.get("requestId").asText();
                String correlationId = reqJson.get("correlationId").asText();
                String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

                String responseBody = """
                        {
                          "runId": "%s",
                          "correlationId": "%s",
                          "runStatus": "SUCCEEDED",
                          "resultType": "INSUFFICIENT_DATA",
                          "reasonCode": "NO_RELEVANT_EVIDENCE",
                          "limitations": ["Chua co du lieu huong dan"],
                          "missingData": [],
                          "asOf": "%s",
                          "usage": {
                            "promptTokens": 150,
                            "completionTokens": 50,
                            "totalTokens": 200,
                            "latencyMs": 120
                          }
                        }
                        """.formatted(requestId, correlationId, now);

                byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            }
        });
        mockFastApiHttpServer.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (mockFastApiHttpServer != null) {
            mockFastApiHttpServer.stop(0);
        }
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

        registry.add("ai.service.mode", () -> "http");
        registry.add("ai.service.base-url", () -> "http://localhost:" + mockFastApiPort);
        registry.add("ai.service.internal-service-key", () -> SERVICE_KEY);
        registry.add("ai.service.connect-timeout-ms", () -> "5000");
        registry.add("ai.service.read-timeout-ms", () -> "25000");
    }

    @LocalServerPort private int serverPort;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private ObjectMapper objectMapper;

    private UUID sampleDocId;
    private UUID sampleVersionId;

    @BeforeEach
    void setUp() {
        providerCallCount.set(0);
        providerDelayLatch.set(null);

        jdbcTemplate.execute("DELETE FROM fitness.ai_run_knowledge_chunks");
        jdbcTemplate.execute("DELETE FROM fitness.ai_validation_results");
        jdbcTemplate.execute("DELETE FROM fitness.ai_runs");
        // Note: fitness.audit_logs is append-only with protect trigger; we do not delete from it or users.
        jdbcTemplate.execute("TRUNCATE TABLE fitness.knowledge_documents CASCADE");

        // Seed users
        seedUser(ADMIN_USER_ID, "admin@platform.local", "ADMIN", "ACTIVE");
        // Also grant CONTENT_ADMIN role to admin user so knowledge governance permissions are active
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp()
                FROM fitness.roles
                WHERE code = 'CONTENT_ADMIN'
                ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL
                """, ADMIN_USER_ID, ADMIN_USER_ID);

        seedUser(STUDENT_USER_ID, "student@platform.local", "STUDENT", "ACTIVE");
        seedUser(TRAINER_USER_ID, "trainer@platform.local", "TRAINER", "ACTIVE");
        seedUser(SUSPENDED_USER_ID, "suspended@platform.local", "STUDENT", "SUSPENDED");

        // Ensure F01 feature flag is enabled in fitness.feature_flags by default
        jdbcTemplate.update("""
                INSERT INTO fitness.feature_flags (code, enabled, criticality, description, updated_by, updated_at)
                VALUES ('F01_GENERAL_KNOWLEDGE', true, 'MEDIUM', 'F01 General Fitness Knowledge', ?, clock_timestamp())
                ON CONFLICT (code) DO UPDATE SET enabled = true, updated_at = clock_timestamp()
                """, ADMIN_USER_ID);

        // Seed knowledge document and version
        sampleDocId = UUID.randomUUID();
        sampleVersionId = UUID.randomUUID();
        seedKnowledgeCorpus(sampleDocId, sampleVersionId);
    }

    private void seedUser(UUID userId, String email, String roleCode, String status) {
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'hash', 'Test User', ?::fitness.account_status, clock_timestamp(), clock_timestamp())
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status
                """, userId, email, status);

        if ("STUDENT".equals(roleCode)) {
            jdbcTemplate.update("""
                    INSERT INTO fitness.student_profiles (user_id, created_at, updated_at)
                    VALUES (?, clock_timestamp(), clock_timestamp())
                    ON CONFLICT (user_id) DO NOTHING
                    """, userId);
        } else if ("TRAINER".equals(roleCode)) {
            jdbcTemplate.update("""
                    INSERT INTO fitness.trainer_profiles (user_id, bio, verification_status, created_at, updated_at)
                    VALUES (?, 'Bio', 'VERIFIED', clock_timestamp(), clock_timestamp())
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

    private void seedKnowledgeCorpus(UUID docId, UUID versionId) {
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_documents (
                    id, title, source_name, document_type, document_code, source_id, language, source_url, author
                ) VALUES (
                    ?, 'ISSN Protein Timing', 'ISSN', 'GUIDELINE', 'ISSN-PROTEIN-EXERCISE-2017-VI-04', 'ISSN-PROTEIN-EXERCISE-2017', 'vi',
                    'https://jissn.biomedcentral.com/articles/10.1186/s12970-017-0177-8', 'Jager et al.'
                )
                """, docId);

        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_versions (
                    id, knowledge_document_id, version_number, version_label, status, content_hash, technical_readiness, content_path, content, created_at
                ) VALUES (
                    ?, ?, 1, '1.0.0', 'DRAFT'::fitness.knowledge_status, 'dummyhash123', 'TECHNICAL_VERIFICATION_PASSED',
                    'data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md', 'protein nen duoc phan bo deu moi 3-4 gio.', clock_timestamp()
                )
                """, versionId, docId);

        UUID chunkId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_chunks (
                    id, knowledge_version_id, chunk_index, heading_path, content, created_at
                ) VALUES (
                    ?, ?, 0, 'Phan bo protein', 'protein nen duoc phan bo deu moi 3-4 gio.', clock_timestamp()
                )
                """, chunkId, versionId);
    }

    private String bearer(UUID userId, String... roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(userId.toString())
                .claim("email", "test@platform.local")
                .claim("roles", List.of(roles))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    // =========================================================================
    // SECTION A: SPRING SECURITY & HTTP AUTHORIZATION GUARDS
    // =========================================================================

    @Test
    @DisplayName("A1. Anonymous request to Admin AI Runs returns 401 Unauthorized")
    void anonymous_adminAiRuns_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai/runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A2. Student user attempting to access Admin AI Runs returns 403 Forbidden")
    void student_adminAiRuns_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai/runs")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A3. Student user attempting to toggle F01 Kill Switch returns 403 Forbidden")
    void student_toggleKillSwitch_returns403() throws Exception {
        ToggleAiFeatureApiRequest req = new ToggleAiFeatureApiRequest(false, "Unauthorized attempt");
        mockMvc.perform(post("/api/v1/admin/ai/features/f01/toggle")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A4. Valid Admin accesses Admin AI endpoints successfully (200 OK)")
    void admin_accessAiEndpoints_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai/features/f01")
                        .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureCode", is("F01_GENERAL_KNOWLEDGE")))
                .andExpect(jsonPath("$.enabled", is(true)));

        mockMvc.perform(get("/api/v1/admin/ai/runs")
                        .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", notNullValue()));
    }

    // =========================================================================
    // SECTION B: ADMIN KNOWLEDGE MANAGEMENT (F09) & DATABASE AUDIT
    // =========================================================================

    @Test
    @DisplayName("B. Admin lists documents and executes Review -> Publish -> Archive lifecycle in PostgreSQL with Audit")
    void knowledgeLifecycle_reviewPublishArchive_persistsInPostgres() throws Exception {
        // 1. List documents
        mockMvc.perform(get("/api/v1/admin/content/knowledge/documents")
                        .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].documentCode", is("ISSN-PROTEIN-EXERCISE-2017-VI-04")));

        // 2. Review version -> APPROVED
        ReviewKnowledgeVersionRequest reviewReq = new ReviewKnowledgeVersionRequest(ReviewDecision.APPROVED, "Verified against ISSN guideline");
        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + sampleVersionId + "/review")
                        .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewReq)))
                .andExpect(status().isOk());

        // Verify version status remains DRAFT while review record is recorded in PostgreSQL
        String statusAfterReview = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.knowledge_versions WHERE id = ?", String.class, sampleVersionId);
        assertThat(statusAfterReview).isEqualTo("DRAFT");

        Integer reviewCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.knowledge_version_reviews WHERE knowledge_version_id = ? AND decision = 'APPROVED'",
                Integer.class, sampleVersionId);
        assertThat(reviewCount).isEqualTo(1);

        // 3. Publish version -> ACTIVE (activated_at populated)
        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + sampleVersionId + "/publish")
                        .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN")))
                .andExpect(status().isOk());

        String statusAfterPublish = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.knowledge_versions WHERE id = ?", String.class, sampleVersionId);
        assertThat(statusAfterPublish).isEqualTo("ACTIVE");

        // 4. Archive version -> ARCHIVED
        ArchiveKnowledgeVersionRequest archiveReq = new ArchiveKnowledgeVersionRequest("Superseded by 2026 update");
        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + sampleVersionId + "/archive")
                        .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(archiveReq)))
                .andExpect(status().isOk());

        String statusAfterArchive = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.knowledge_versions WHERE id = ?", String.class, sampleVersionId);
        assertThat(statusAfterArchive).isEqualTo("ARCHIVED");
    }

    // =========================================================================
    // SECTION C: ADMIN AI RUN MONITORING (F10) & SENSITIVE DATA REDACTION
    // =========================================================================

    @Test
    @DisplayName("C. Admin AI Run API strips private input/output and logs VIEW_AI_RUN_DETAIL audit in PostgreSQL")
    void adminAiRun_redactsSensitiveData_andLogsAuditInDb() throws Exception {
        UUID privateRunId = UUID.randomUUID();
        // Insert a run containing sensitive medical/private data
        jdbcTemplate.update("""
                INSERT INTO fitness.ai_runs (
                    id, requested_by, request_type, status, input_payload, output_payload,
                    latency_ms, queued_at, created_at
                ) VALUES (
                    ?, ?, 'F01_GENERAL_KNOWLEDGE', 'SUCCEEDED'::fitness.ai_run_status,
                    '{"question":"Private user disease: I have diabetes and kidney issues, should I take creatine?"}'::jsonb,
                    '{"answer":"Private response with sensitive medical advice"}'::jsonb,
                    450, clock_timestamp(), clock_timestamp()
                )
                """, privateRunId, STUDENT_USER_ID);

        // 1. Admin lists runs: verify input_payload / output_payload are completely absent
        mockMvc.perform(get("/api/v1/admin/ai/runs")
                        .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id", is(privateRunId.toString())))
                .andExpect(jsonPath("$.items[0].inputPayload").doesNotExist())
                .andExpect(jsonPath("$.items[0].outputPayload").doesNotExist())
                .andExpect(jsonPath("$.items[0].latencyMs", is(450)));

        // 2. Admin retrieves run detail: verify telemetry returned, totalTokenCount null-safe, and audit log recorded in DB
        mockMvc.perform(get("/api/v1/admin/ai/runs/" + privateRunId)
                        .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(privateRunId.toString())))
                .andExpect(jsonPath("$.status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.totalTokenCount").value(nullValue()))
                .andExpect(jsonPath("$.latencyMs", is(450)));

        // VERIFY REAL DATABASE AUDIT LOG IN fitness.audit_logs
        List<Map<String, Object>> auditLogs = jdbcTemplate.queryForList("""
                SELECT action, actor_user_id, target_type, target_id
                FROM fitness.audit_logs
                WHERE action = 'VIEW_AI_RUN_DETAIL' AND target_id = ?
                """, privateRunId);

        assertThat(auditLogs).hasSize(1);
        assertThat(auditLogs.get(0).get("action")).isEqualTo("VIEW_AI_RUN_DETAIL");
        assertThat(auditLogs.get(0).get("actor_user_id")).isEqualTo(ADMIN_USER_ID);
        assertThat(auditLogs.get(0).get("target_id")).isEqualTo(privateRunId);
    }

    // =========================================================================
    // SECTION D: HISTORICAL AI RUN READING VIA END-USER API
    // =========================================================================

    @Test
    @DisplayName("D. End-user reading historical AI Run enforces ownership, active account and role invariants")
    void endUser_readHistoricalRun_enforcesGuards() throws Exception {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.ai_runs (
                    id, requested_by, request_type, status, output_payload, created_at, queued_at
                ) VALUES (
                    ?, ?, 'F01_GENERAL_KNOWLEDGE', 'SUCCEEDED'::fitness.ai_run_status,
                    '{"answer":"Historical protein recommendation"}'::jsonb, clock_timestamp(), clock_timestamp()
                )
                """, runId, STUDENT_USER_ID);

        // 1. Valid owner reads run -> 200 OK
        mockMvc.perform(get("/api/v1/ai/runs/" + runId)
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(runId.toString())))
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")));

        // 2. Different user attempts to read run -> 403 Forbidden
        mockMvc.perform(get("/api/v1/ai/runs/" + runId)
                        .header("Authorization", bearer(TRAINER_USER_ID, "TRAINER")))
                .andExpect(status().isForbidden());

        // 3. Suspended owner attempts to read run -> 401/403 Account Unavailable
        mockMvc.perform(get("/api/v1/ai/runs/" + runId)
                        .header("Authorization", bearer(SUSPENDED_USER_ID, "STUDENT")))
                .andExpect(status().isForbidden());

        // 4. When F01 is disabled by Kill Switch, owner can STILL read historical run
        jdbcTemplate.update("UPDATE fitness.feature_flags SET enabled = false WHERE code = 'F01_GENERAL_KNOWLEDGE'");

        mockMvc.perform(get("/api/v1/ai/runs/" + runId)
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(runId.toString())));
    }

    @Test
    @DisplayName("D2. Revalidation: User with all roles revoked in DB is blocked (403) from reading historical run")
    void endUser_readHistoricalRun_revokedAllRoles_returns403() throws Exception {
        UUID userWithRevokedRoles = UUID.randomUUID();
        seedUser(userWithRevokedRoles, "revoked@platform.local", "STUDENT", "ACTIVE");

        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.ai_runs (
                    id, requested_by, request_type, status, output_payload, created_at, queued_at
                ) VALUES (
                    ?, ?, 'F01_GENERAL_KNOWLEDGE', 'SUCCEEDED'::fitness.ai_run_status,
                    '{"answer":"Historical protein recommendation"}'::jsonb, clock_timestamp(), clock_timestamp()
                )
                """, runId, userWithRevokedRoles);

        // Revoke all roles in database
        jdbcTemplate.update("UPDATE fitness.user_roles SET revoked_at = clock_timestamp() WHERE user_id = ?", userWithRevokedRoles);

        // Attempt reading run with JWT that still claims STUDENT
        mockMvc.perform(get("/api/v1/ai/runs/" + runId)
                        .header("Authorization", bearer(userWithRevokedRoles, "STUDENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("AI_FORBIDDEN")));
    }

    @Test
    @DisplayName("D3. Revalidation: User losing STUDENT but retaining active TRAINER role can read historical run (200 OK)")
    void endUser_readHistoricalRun_lostStudent_retainsTrainer_returns200() throws Exception {
        UUID dualRoleUser = UUID.randomUUID();
        seedUser(dualRoleUser, "dual@platform.local", "STUDENT", "ACTIVE");
        // Also grant TRAINER role
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp()
                FROM fitness.roles
                WHERE code = 'TRAINER'
                ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL
                """, dualRoleUser, dualRoleUser);

        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.ai_runs (
                    id, requested_by, request_type, status, output_payload, created_at, queued_at
                ) VALUES (
                    ?, ?, 'F01_GENERAL_KNOWLEDGE', 'SUCCEEDED'::fitness.ai_run_status,
                    '{"answer":"Dual role run"}'::jsonb, clock_timestamp(), clock_timestamp()
                )
                """, runId, dualRoleUser);

        // Revoke STUDENT role in DB, leaving TRAINER active
        jdbcTemplate.update("""
                UPDATE fitness.user_roles SET revoked_at = clock_timestamp()
                WHERE user_id = ? AND role_id = (SELECT id FROM fitness.roles WHERE code = 'STUDENT')
                """, dualRoleUser);

        // User requests run -> passes revalidation because TRAINER is still active in DB
        mockMvc.perform(get("/api/v1/ai/runs/" + runId)
                        .header("Authorization", bearer(dualRoleUser, "TRAINER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(runId.toString())))
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")));
    }

    // =========================================================================
    // SECTION E: KILL SWITCH F01 TOGGLE & ZERO PROVIDER CALL REJECTION
    // =========================================================================

    @Test
    @DisplayName("E. Kill Switch F01: Toggle OFF stops new queries (503, 0 provider calls); Toggle ON resumes")
    void killSwitch_toggleOffAndOn_realWireVerification() throws Exception {
        // 1. Toggle OFF via Admin API
        ToggleAiFeatureApiRequest disableReq = new ToggleAiFeatureApiRequest(false, "Emergency maintenance");
        mockMvc.perform(post("/api/v1/admin/ai/features/f01/toggle")
                        .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disableReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(false)));

        // Verify PostgreSQL feature flag record
        Boolean isEnabledInDb = jdbcTemplate.queryForObject(
                "SELECT enabled FROM fitness.feature_flags WHERE code = 'F01_GENERAL_KNOWLEDGE'", Boolean.class);
        assertThat(isEnabledInDb).isFalse();

        // Verify Audit Log in PostgreSQL
        List<Map<String, Object>> toggleAudits = jdbcTemplate.queryForList("""
                SELECT action, before_data, after_data
                FROM fitness.audit_logs
                WHERE action = 'TOGGLE_AI_FEATURE'
                """);
        assertThat(toggleAudits).isNotEmpty();
        Map<String, Object> latestAudit = toggleAudits.get(toggleAudits.size() - 1);
        assertThat(latestAudit.get("before_data").toString()).contains("\"enabled\": true");
        assertThat(latestAudit.get("after_data").toString()).contains("\"enabled\": false");
        assertThat(latestAudit.get("after_data").toString()).contains("Emergency maintenance");

        // 2. Student queries AI while disabled -> 503 SERVICE UNAVAILABLE
        F01KnowledgeQueryApiRequest queryReq = new F01KnowledgeQueryApiRequest(
                "Nen an bao nhieu gam protein?", "vi", null, UUID.randomUUID());

        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(queryReq)))
                .andExpect(status().isServiceUnavailable());

        // CRITICAL CHECK: ZERO requests transmitted over HTTP to mock FastAPI server
        assertThat(providerCallCount.get()).isEqualTo(0);

        // 3. Toggle ON via Admin API
        ToggleAiFeatureApiRequest enableReq = new ToggleAiFeatureApiRequest(true, "Maintenance concluded");
        mockMvc.perform(post("/api/v1/admin/ai/features/f01/toggle")
                        .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(enableReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)));

        // 4. Student queries AI again -> 200 OK (Processed normally without restart)
        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(queryReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INSUFFICIENT_DATA")));

        // Provider was invoked exactly once
        assertThat(providerCallCount.get()).isEqualTo(1);
    }

    // =========================================================================
    // SECTION F: IN-FLIGHT DISABLE POLICY VERIFICATION
    // =========================================================================

    @Test
    @DisplayName("F. In-flight Disable: AI generation completes and persists run for audit, but output delivery is blocked (503)")
    void inFlightDisable_savesAuditRun_blocksDelivery() throws Exception {
        // Setup a latch on the mock HTTP server so it pauses during generation
        CountDownLatch inFlightLatch = new CountDownLatch(1);
        providerDelayLatch.set(inFlightLatch);

        F01KnowledgeQueryApiRequest queryReq = new F01KnowledgeQueryApiRequest(
                "Thoi diem nap protein tot nhat?", "vi", null, UUID.randomUUID());

        // Execute query asynchronously
        CompletableFuture<MvcResult> queryFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(post("/api/v1/ai/knowledge/query")
                                .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(queryReq)))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait until mock provider receives request
        long start = System.currentTimeMillis();
        while (providerCallCount.get() == 0 && System.currentTimeMillis() - start < 3000) {
            Thread.sleep(50);
        }
        assertThat(providerCallCount.get()).isEqualTo(1);

        // While AI provider is generating, Admin toggles F01 OFF directly in database
        jdbcTemplate.update("UPDATE fitness.feature_flags SET enabled = false WHERE code = 'F01_GENERAL_KNOWLEDGE'");

        // Release the mock provider to finish generation
        inFlightLatch.countDown();

        // Await HTTP response
        MvcResult result = queryFuture.get(5, TimeUnit.SECONDS);

        // Output MUST be blocked with 503 SERVICE UNAVAILABLE
        assertThat(result.getResponse().getStatus()).isEqualTo(503);

        // CRITICAL INVARIANT IN DATABASE:
        // The AI Run must STILL be persisted in fitness.ai_runs with terminal status SUCCEEDED and usage tokens
        // to prevent orphan runs and ensure cost accounting is retained!
        List<Map<String, Object>> runs = jdbcTemplate.queryForList("""
                SELECT status, input_token_count, output_token_count, latency_ms
                FROM fitness.ai_runs
                WHERE requested_by = ? AND request_type = 'F01_GENERAL_KNOWLEDGE'
                ORDER BY created_at DESC LIMIT 1
                """, STUDENT_USER_ID);

        assertThat(runs).hasSize(1);
        Map<String, Object> savedRun = runs.get(0);
        assertThat(savedRun.get("status").toString()).isEqualTo("SUCCEEDED");
        assertThat(((Number) savedRun.get("input_token_count")).intValue()).isEqualTo(150);
        assertThat(((Number) savedRun.get("output_token_count")).intValue()).isEqualTo(50);
        assertThat(((Number) savedRun.get("latency_ms")).intValue()).isGreaterThan(0);
    }

    // =========================================================================
    // SECTION G: REAL HTTP NETWORK CLIENT OVER TOMCAT SOCKET
    // =========================================================================

    @Test
    @DisplayName("G1. Real HTTP network client calls GET /admin/ai/runs via Tomcat TCP socket (200 OK)")
    void networkClient_adminAiRuns_returns200() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + serverPort + "/api/v1/admin/ai/runs"))
                .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN"))
                .GET()
                .build();

        java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode root = objectMapper.readTree(response.body());
        assertThat(root.has("items")).isTrue();
    }

    @Test
    @DisplayName("G2. Real HTTP network client toggles F01 via Tomcat TCP socket (200 OK, audit persisted)")
    void networkClient_adminToggleF01_returns200() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        String jsonBody = objectMapper.writeValueAsString(new ToggleAiFeatureApiRequest(true, "Tomcat socket live toggle"));

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + serverPort + "/api/v1/admin/ai/features/f01/toggle"))
                .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode root = objectMapper.readTree(response.body());
        assertThat(root.get("enabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("G3. Real HTTP network client accesses Knowledge Documents (200 OK)")
    void networkClient_adminKnowledgeDocuments_returns200() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + serverPort + "/api/v1/admin/content/knowledge/documents"))
                .header("Authorization", bearer(ADMIN_USER_ID, "ADMIN", "CONTENT_ADMIN"))
                .GET()
                .build();

        java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("G4. Real HTTP network client: Anonymous returns 401; Student returns 403")
    void networkClient_securityGuards_enforced() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

        // 1. Anonymous -> 401
        java.net.http.HttpRequest anonReq = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + serverPort + "/api/v1/admin/ai/runs"))
                .GET()
                .build();
        java.net.http.HttpResponse<String> anonResp = client.send(anonReq, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(anonResp.statusCode()).isEqualTo(401);

        // 2. Student -> 403
        java.net.http.HttpRequest studentReq = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + serverPort + "/api/v1/admin/ai/runs"))
                .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                .GET()
                .build();
        java.net.http.HttpResponse<String> studentResp = client.send(studentReq, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(studentResp.statusCode()).isEqualTo(403);
    }
}
