package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.ai.application.service.AgentToolGatewayService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiAgentConversationAndToolGatewayIntegrationTest {

    private static final UUID STUDENT_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STUDENT_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TRAINER_1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String INTERNAL_KEY = "test-agent-internal-key-32chars!!";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("agent_integration")
            .withUsername("fitness_app")
            .withPassword("testpass123");

    static {
        postgres.start();
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
        registry.add("ai.service.internal-service-key", () -> INTERNAL_KEY);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AgentToolGatewayService gatewayService;
    @Autowired private com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase sharingUseCase;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM fitness.ai_conversation_turns");
        jdbcTemplate.execute("DELETE FROM fitness.ai_conversations");
        for (UUID uid : List.of(STUDENT_1, STUDENT_2)) {
            jdbcTemplate.update("""
                    INSERT INTO fitness.users (id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at)
                    VALUES (?, ?, 'hash', 'Student', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                    ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                    """, uid, uid + "@fit.local");
            jdbcTemplate.update("""
                    INSERT INTO fitness.student_profiles (user_id, created_at, updated_at)
                    VALUES (?, clock_timestamp(), clock_timestamp())
                    ON CONFLICT (user_id) DO NOTHING
                    """, uid);
            jdbcTemplate.update("""
                    INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                    SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'STUDENT'
                    ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL
                    """, uid, uid);
        }
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, 'trainer1@fit.local', 'hash', 'Trainer', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                """, TRAINER_1);
        jdbcTemplate.update("""
                INSERT INTO fitness.trainer_profiles (user_id, created_at, updated_at)
                VALUES (?, clock_timestamp(), clock_timestamp())
                ON CONFLICT (user_id) DO NOTHING
                """, TRAINER_1);
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'TRAINER'
                ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL
                """, TRAINER_1, TRAINER_1);
    }

    private String bearer(UUID userId, String... roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(userId.toString())
                .claim("email", userId + "@fit.local")
                .claim("roles", List.of(roles))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    @DisplayName("AG-M4: Conversation ownership, ai_runs lifecycle, idempotency, concurrency, and cross-user/trainer isolation")
    void conversationLifecycleIdempotencyConcurrencyAndIsolation() throws Exception {
        String s1Token = bearer(STUDENT_1, "STUDENT");
        String s2Token = bearer(STUDENT_2, "STUDENT");
        String t1Token = bearer(TRAINER_1, "TRAINER");

        // Trainer without active relationship with STUDENT_1 MUST be blocked (403)
        mockMvc.perform(post("/api/v1/ai/conversations")
                        .header("Authorization", t1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorRole\":\"TRAINER\",\"subjectId\":\"" + STUDENT_1 + "\",\"title\":\"Xem học viên\"}"))
                .andExpect(status().isForbidden());

        MvcResult createRes = mockMvc.perform(post("/api/v1/ai/conversations")
                        .header("Authorization", s1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorRole\":\"STUDENT\",\"title\":\"Hỏi đáp tập luyện\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andReturn();

        JsonNode created = objectMapper.readTree(createRes.getResponse().getContentAsString());
        String convId = created.get("conversationId").asText();

        // Turn 1 with operationKey -> idempotent retry returns exact same turnId and aiRunId
        String opKey = "op-" + UUID.randomUUID();
        MvcResult t1Res = mockMvc.perform(post("/api/v1/ai/conversations/" + convId + "/turns")
                        .header("Authorization", s1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"tư vấn\",\"operationKey\":\"" + opKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultType", is("CLARIFICATION_REQUIRED")))
                .andReturn();
        JsonNode t1Json = objectMapper.readTree(t1Res.getResponse().getContentAsString());

        MvcResult t1Retry = mockMvc.perform(post("/api/v1/ai/conversations/" + convId + "/turns")
                        .header("Authorization", s1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"tư vấn\",\"operationKey\":\"" + opKey + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode t1RetryJson = objectMapper.readTree(t1Retry.getResponse().getContentAsString());
        assertThat(t1RetryJson.get("turnId").asText()).isEqualTo(t1Json.get("turnId").asText());
        assertThat(t1RetryJson.get("aiRunId").asText()).isEqualTo(t1Json.get("aiRunId").asText());

        mockMvc.perform(post("/api/v1/ai/conversations/" + convId + "/turns")
                        .header("Authorization", s1Token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Một nội dung khác\",\"operationKey\":\"" + opKey + "\"}"))
                .andExpect(status().isConflict());

        // Client input must not promote a self-scoped Student into a Trainer.
        mockMvc.perform(post("/api/v1/ai/conversations")
                        .header("Authorization", s1Token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorRole\":\"TRAINER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actorRole", is("STUDENT")));

        // Verify ai_runs transitioned to SUCCEEDED (not stuck in QUEUED)
        String run1Status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.ai_runs WHERE id = ?::uuid",
                String.class, t1Json.get("aiRunId").asText()
        );
        assertThat(run1Status).isEqualTo("SUCCEEDED");

        // Turn 2: Guardrail block -> ai_runs transitions to REJECTED_BY_VALIDATOR
        MvcResult t2Res = mockMvc.perform(post("/api/v1/ai/conversations/" + convId + "/turns")
                        .header("Authorization", s1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"cách dùng steroid tăng cơ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultType", is("BLOCKED")))
                .andExpect(jsonPath("$.reasonCode", is("RULE_BLOCKED")))
                .andReturn();
        String run2Status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.ai_runs WHERE id = ?::uuid",
                String.class, objectMapper.readTree(t2Res.getResponse().getContentAsString()).get("aiRunId").asText()
        );
        assertThat(run2Status).isEqualTo("REJECTED_BY_VALIDATOR");

        // Concurrent turns on same conversation -> both succeed with distinct turn_index
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> c = () -> mockMvc.perform(post("/api/v1/ai/conversations/" + convId + "/turns")
                            .header("Authorization", s1Token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"tư vấn\"}"))
                    .andReturn().getResponse().getStatus();
            Future<Integer> f1 = pool.submit(c);
            Future<Integer> f2 = pool.submit(c);
            assertThat(f1.get()).isEqualTo(200);
            assertThat(f2.get()).isEqualTo(200);
        }

        // Cross-user isolation: STUDENT_2 must get 403
        mockMvc.perform(get("/api/v1/ai/conversations/" + convId)
                        .header("Authorization", s2Token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AG-M3 & AG-M5: Internal tool gateway enforces service key, run binding, subject match, and disabled proposal flag")
    void internalToolGatewayAuthAndProposalFlag() throws Exception {
        mockMvc.perform(post("/internal/v1/agent/tools/current-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + UUID.randomUUID() + "\",\"actorId\":\"" + STUDENT_1 + "\"}"))
                .andExpect(status().isUnauthorized());

        // Insert a RUNNING ai_run bound to STUDENT_1
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.ai_runs (id, requested_by, student_id, request_type, status, correlation_id, input_payload)
                VALUES (?, ?, ?, 'F01_GENERAL_KNOWLEDGE', 'RUNNING', ?, '{}'::jsonb)
                """, runId, STUDENT_1, STUDENT_1, UUID.randomUUID());

        // Forged actorId != run.requestedBy -> 403
        mockMvc.perform(post("/internal/v1/agent/tools/current-plan")
                        .header("X-Internal-Service-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\",\"actorId\":\"" + STUDENT_2 + "\",\"actorRole\":\"STUDENT\"}"))
                .andExpect(status().isForbidden());

        // Mismatched subjectId != run.studentId -> 403
        mockMvc.perform(post("/internal/v1/agent/tools/current-plan")
                        .header("X-Internal-Service-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\",\"actorId\":\"" + STUDENT_1 + "\",\"actorRole\":\"STUDENT\",\"subjectId\":\"" + STUDENT_2 + "\"}"))
                .andExpect(status().isForbidden());

        // Valid actorId + runId + matching subjectId -> 200
        mockMvc.perform(post("/internal/v1/agent/tools/current-plan")
                        .header("X-Internal-Service-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\",\"actorId\":\"" + STUDENT_1 + "\",\"actorRole\":\"STUDENT\",\"subjectId\":\"" + STUDENT_1 + "\"}"))
                .andExpect(status().isOk());

        // Proposal tool disabled by default -> DENIED
        var draftOut = gatewayService.prepareWorkoutAdjustment(
                new AgentToolGatewayService.ToolCallInput(runId, STUDENT_1, "STUDENT", STUDENT_1, Map.of())
        );
        assertThat(draftOut.status()).isEqualTo("DENIED");
    }

    @Test
    @DisplayName("AG-M3 & AG-M4: Trainer with valid authority succeeds and mid-turn consent revocation denies before data read")
    void trainerAuthorityAndMidTurnRevocationEnforcedBeforeDataRead() throws Exception {
        jdbcTemplate.execute("DELETE FROM fitness.data_sharing_permissions");
        jdbcTemplate.execute("DELETE FROM fitness.coaching_periods");
        jdbcTemplate.execute("DELETE FROM fitness.coaching_relationships");
        jdbcTemplate.update("""
                UPDATE fitness.trainer_profiles
                SET verification_status = 'VERIFIED', is_accepting_students = true
                WHERE user_id = ?
                """, TRAINER_1);

        UUID relationshipId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.coaching_relationships
                (id, student_id, trainer_id, status, requested_by, requested_at, accepted_at, started_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, clock_timestamp() - interval '2 days',
                        clock_timestamp() - interval '2 days', clock_timestamp() - interval '2 days')
                """, relationshipId, STUDENT_1, TRAINER_1, STUDENT_1);
        UUID periodId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.coaching_periods
                (id, student_id, mode, coaching_relationship_id, trainer_id, started_at, created_by)
                VALUES (?, ?, 'HUMAN_COACH', ?, ?, clock_timestamp() - interval '2 days', ?)
                """, periodId, STUDENT_1, relationshipId, TRAINER_1, TRAINER_1);
        UUID permId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id, relationship_id, student_id, trainer_id, data_scope, decision, access_level, valid_from, granted_by)
                VALUES (?, ?, ?, ?, 'WORKOUT_PLAN', 'ALLOW', 'MANAGE'::fitness.data_access_level,
                        clock_timestamp() - interval '1 day', ?)
                """, permId, relationshipId, STUDENT_1, TRAINER_1, STUDENT_1);
        UUID historyPermId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id, relationship_id, student_id, trainer_id, data_scope, decision, access_level, valid_from, granted_by)
                VALUES (?, ?, ?, ?, 'WORKOUT_HISTORY', 'ALLOW', 'VIEW'::fitness.data_access_level,
                        clock_timestamp() - interval '1 day', ?)
                """, historyPermId, relationshipId, STUDENT_1, TRAINER_1, STUDENT_1);

        String trainerToken = bearer(TRAINER_1, "TRAINER");

        // (c) Trainer with valid authority creates subject-scoped conversation -> 200 OK
        MvcResult createRes = mockMvc.perform(post("/api/v1/ai/conversations")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorRole\":\"TRAINER\",\"subjectId\":\"" + STUDENT_1 + "\",\"title\":\"Review student\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorRole", is("TRAINER")))
                .andExpect(jsonPath("$.subjectId", is(STUDENT_1.toString())))
                .andReturn();
        String convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("conversationId").asText();

        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.ai_runs (id, requested_by, student_id, request_type, status, correlation_id, input_payload)
                VALUES (?, ?, ?, 'F01_GENERAL_KNOWLEDGE', 'RUNNING', ?, '{}'::jsonb)
                """, runId, TRAINER_1, STUDENT_1, UUID.randomUUID());

        // Tool gateway allows Trainer read while relationship & consent are active -> 200 OK
        mockMvc.perform(post("/internal/v1/agent/tools/current-plan")
                        .header("X-Internal-Service-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\",\"actorId\":\"" + TRAINER_1 + "\",\"actorRole\":\"TRAINER\",\"subjectId\":\"" + STUDENT_1 + "\"}"))
                .andExpect(status().isOk());

        // (d) Authority/consent revoked mid-turn -> subsequent tool calls & turns MUST be denied (403) before data read
        long planPermVer = jdbcTemplate.queryForObject("SELECT version FROM fitness.data_sharing_permissions WHERE id = ?", Long.class, permId);
        sharingUseCase.revoke(STUDENT_1, relationshipId, permId, planPermVer, "Consent revoked mid-turn", UUID.randomUUID());
        long histPermVer = jdbcTemplate.queryForObject("SELECT version FROM fitness.data_sharing_permissions WHERE id = ?", Long.class, historyPermId);
        sharingUseCase.revoke(STUDENT_1, relationshipId, historyPermId, histPermVer, "History consent revoked mid-turn", UUID.randomUUID());
        jdbcTemplate.update("""
                UPDATE fitness.coaching_relationships
                SET status = 'ENDED', ended_at = clock_timestamp(), version = version + 1
                WHERE id = ?
                """, relationshipId);

        mockMvc.perform(post("/internal/v1/agent/tools/current-plan")
                        .header("X-Internal-Service-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\",\"actorId\":\"" + TRAINER_1 + "\",\"actorRole\":\"TRAINER\",\"subjectId\":\"" + STUDENT_1 + "\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/internal/v1/agent/tools/knowledge-access")
                        .header("X-Internal-Service-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\",\"actorId\":\"" + TRAINER_1 + "\",\"actorRole\":\"TRAINER\",\"subjectId\":\"" + STUDENT_1 + "\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/ai/conversations/" + convId + "/turns")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Xem lại giáo án của học viên\"}"))
                .andExpect(status().isForbidden());
    }
}
