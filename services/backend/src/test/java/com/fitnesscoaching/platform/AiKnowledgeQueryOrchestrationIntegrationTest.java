package com.fitnesscoaching.platform;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.SimulatedF01AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiContextSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunInputReference;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunKnowledgeChunk;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiValidationResult;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiKnowledgeQueryOrchestrationIntegrationTest {

    private static final UUID STUDENT_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRAINER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BASE_ADMIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_orch_integration")
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
    private SimulatedF01AiServiceClient simulatedAiClient;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID activeExerciseId;

    @BeforeEach
    void setUp() {
        simulatedAiClient.reset();

        jdbcTemplate.execute("DELETE FROM fitness.ai_runs");
        jdbcTemplate.execute("DELETE FROM fitness.user_roles");
        jdbcTemplate.execute("DELETE FROM fitness.student_profiles");
        jdbcTemplate.execute("DELETE FROM fitness.trainer_profiles");
        jdbcTemplate.execute("DELETE FROM fitness.users");

        seedUser(STUDENT_USER_ID, "student@fitplatform.local", "STUDENT");
        seedUser(TRAINER_USER_ID, "trainer@fitplatform.local", "TRAINER");
        seedUser(BASE_ADMIN_ID, "admin@fitplatform.local", "ADMIN");

        activeExerciseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.exercises (
                    id, code, name, difficulty, movement_pattern, admin_status, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, 'BEGINNER', 'SQUAT', 'ACTIVE', clock_timestamp(), clock_timestamp()
                ) ON CONFLICT (id) DO NOTHING
                """, activeExerciseId, "TEST-SQUAT-" + UUID.randomUUID().toString().substring(0, 8), "Standard Air Squat");
    }

    @AfterEach
    void tearDown() {
        simulatedAiClient.reset();
    }

    private void seedUser(UUID userId, String email, String roleCode) {
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'hash', 'Test User', 'ACTIVE', clock_timestamp(), clock_timestamp())
                ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                """, userId, email);

        if ("STUDENT".equals(roleCode)) {
            jdbcTemplate.update("""
                    INSERT INTO fitness.student_profiles (user_id, created_at, updated_at)
                    VALUES (?, clock_timestamp(), clock_timestamp())
                    ON CONFLICT (user_id) DO NOTHING
                    """, userId);
        } else if ("TRAINER".equals(roleCode)) {
            jdbcTemplate.update("""
                    INSERT INTO fitness.trainer_profiles (user_id, verification_status, created_at, updated_at)
                    VALUES (?, 'VERIFIED', clock_timestamp(), clock_timestamp())
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
    @DisplayName("1. Successful INFORMATION query for Student -> 200 OK with citations and context provenance")
    void successfulInformationQueryForStudent() throws Exception {
        UUID correlationId = UUID.randomUUID();
        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Nguyên tắc vận động cho người trưởng thành là gì?",
                "vi",
                null,
                correlationId
        );

        String responseContent = mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", notNullValue()))
                .andExpect(jsonPath("$.correlationId", is(correlationId.toString())))
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INFORMATION")))
                .andExpect(jsonPath("$.reasonCode", is("NONE")))
                .andExpect(jsonPath("$.answer", notNullValue()))
                .andExpect(jsonPath("$.citations[0].documentId", is("ISSN-PROTEIN-EXERCISE-2017-VI-04")))
                .andExpect(jsonPath("$.limitations", notNullValue()))
                .andExpect(jsonPath("$.asOf", notNullValue()))
                .andExpect(jsonPath("$.usage.promptTokens", is(520)))
                .andReturn().getResponse().getContentAsString();

        JsonNode responseJson = objectMapper.readTree(responseContent);
        UUID runId = UUID.fromString(responseJson.get("runId").asText());

        // Verify DB persistence of AI Run
        Optional<AiRun> savedRun = aiRunRepository.findById(runId);
        assertThat(savedRun).isPresent();
        assertThat(savedRun.get().status()).isEqualTo(AiRunStatus.SUCCEEDED);
        assertThat(savedRun.get().requestedBy()).isEqualTo(STUDENT_USER_ID);

        // Verify DB persistence of Context Envelope
        Optional<AiContextSnapshot> snapshotOpt = provenanceRepository.findSnapshotByRunId(runId);
        assertThat(snapshotOpt).isPresent();
        JsonNode envelopeNode = objectMapper.readTree(snapshotOpt.get().contextPayload());
        assertThat(envelopeNode.get("requestId").asText()).isEqualTo(runId.toString());
        assertThat(envelopeNode.get("requestType").asText()).isEqualTo("F01_GENERAL_KNOWLEDGE");
        assertThat(envelopeNode.get("schemaVersion").asText()).isEqualTo("1.0.0");
        assertThat(envelopeNode.get("actor").get("userId").asText()).isEqualTo(STUDENT_USER_ID.toString());
        assertThat(envelopeNode.get("actor").get("role").asText()).isEqualTo("STUDENT");
        assertThat(envelopeNode.get("purpose").asText()).isEqualTo("GENERAL_FITNESS_EXPLANATION");
        assertThat(envelopeNode.get("allowedActions").get(0).asText()).isEqualTo("READ_INFORMATION");
        assertThat(envelopeNode.get("knowledgeScope").get("status").asText()).isEqualTo("ACTIVE");

        // Verify validation result saved in provenance
        List<AiValidationResult> validations = provenanceRepository.findValidationResultsByRunId(runId);
        assertThat(validations).hasSize(1);
        assertThat(validations.get(0).passed()).isTrue();
    }

    @Test
    @DisplayName("2. Successful query for Trainer with Exercise -> 200 OK and exerciseContext in envelope")
    void successfulQueryWithExerciseForTrainer() throws Exception {
        UUID correlationId = UUID.randomUUID();
        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Cách thực hiện động tác squat chuẩn và an toàn?",
                "vi",
                activeExerciseId,
                correlationId
        );

        String responseContent = mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(TRAINER_USER_ID, "TRAINER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INFORMATION")))
                .andReturn().getResponse().getContentAsString();

        JsonNode responseJson = objectMapper.readTree(responseContent);
        UUID runId = UUID.fromString(responseJson.get("runId").asText());

        // Verify exercise reference in DB
        List<AiRunInputReference> inputRefs = provenanceRepository.findInputReferencesByRunId(runId);
        assertThat(inputRefs).hasSize(1);
        assertThat(inputRefs.get(0).entityType()).isEqualTo("EXERCISE");
        assertThat(inputRefs.get(0).entityId()).isEqualTo(activeExerciseId);

        // Verify exerciseContext in envelope
        Optional<AiContextSnapshot> snapshotOpt = provenanceRepository.findSnapshotByRunId(runId);
        assertThat(snapshotOpt).isPresent();
        JsonNode envelopeNode = objectMapper.readTree(snapshotOpt.get().contextPayload());
        assertThat(envelopeNode.has("exerciseContext")).isTrue();
        assertThat(envelopeNode.get("exerciseContext").get("exerciseId").asText()).isEqualTo(activeExerciseId.toString());
        assertThat(envelopeNode.get("exerciseContext").get("exerciseName").asText()).isEqualTo("Standard Air Squat");
    }

    @Test
    @DisplayName("3. Insufficient data outcome -> 200 OK with INSUFFICIENT_DATA and NO_RELEVANT_EVIDENCE")
    void insufficientDataOutcome() throws Exception {
        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Có tài liệu nào về phương pháp này không? trigger_insufficient_data",
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
                .andExpect(jsonPath("$.missingData[0]", notNullValue()))
                .andExpect(jsonPath("$.limitations[0]", notNullValue()));
    }

    @Test
    @DisplayName("4. Policy Guardrail triggered -> 200 OK with REJECTED_BY_VALIDATOR and BLOCKED")
    void policyGuardrailBlocked() throws Exception {
        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Làm sao để dùng steroid và doping hiệu quả? trigger_blocked",
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
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.citations").doesNotExist())
                .andExpect(jsonPath("$.error.code", is("POLICY_GUARDRAIL_TRIGGERED")));
    }

    @Test
    @DisplayName("5. AI Service Timeout -> 504 Gateway Timeout, run marked FAILED")
    void providerTimeoutFailure() throws Exception {
        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Hỏi câu hỏi sẽ bị trigger_timeout quá hạn",
                "vi",
                null,
                UUID.randomUUID()
        );

        String responseContent = mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.runStatus", is("FAILED")))
                .andExpect(jsonPath("$.reasonCode", is("TIMEOUT")))
                .andExpect(jsonPath("$.resultType").doesNotExist())
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.error.code", is("TIMEOUT")))
                .andReturn().getResponse().getContentAsString();

        JsonNode responseJson = objectMapper.readTree(responseContent);
        UUID runId = UUID.fromString(responseJson.get("runId").asText());

        Optional<AiRun> runOpt = aiRunRepository.findById(runId);
        assertThat(runOpt).isPresent();
        assertThat(runOpt.get().status()).isEqualTo(AiRunStatus.FAILED);
        assertThat(runOpt.get().errorCode()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("6. AI Provider Error (502) -> 502 Bad Gateway, run marked FAILED")
    void providerFailure() throws Exception {
        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Hỏi câu hỏi sẽ bị trigger_provider_error từ upstream",
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
                .andExpect(jsonPath("$.reasonCode", is("MODEL_ERROR")))
                .andExpect(jsonPath("$.error.code", is("MODEL_ERROR")));
    }

    @Test
    @DisplayName("7. AI Output Validation Failure -> 502 Bad Gateway, run marked REJECTED_BY_VALIDATOR (never SUCCEEDED)")
    void outputValidationFailure() throws Exception {
        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Hỏi câu hỏi bị trigger_validation_failure cấu trúc",
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
                .andExpect(jsonPath("$.error.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        JsonNode responseJson = objectMapper.readTree(responseContent);
        UUID runId = UUID.fromString(responseJson.get("runId").asText());

        Optional<AiRun> runOpt = aiRunRepository.findById(runId);
        assertThat(runOpt).isPresent();
        assertThat(runOpt.get().status()).isEqualTo(AiRunStatus.REJECTED_BY_VALIDATOR);
        assertThat(runOpt.get().errorCode()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("8. Unauthorized & Forbidden Rejections (401, 403, 404, 400)")
    void accessControlAndInputValidation() throws Exception {
        F01KnowledgeQueryApiRequest validReq = new F01KnowledgeQueryApiRequest(
                "Nguyên tắc vận động cho người trưởng thành?",
                "vi",
                null,
                UUID.randomUUID()
        );

        // 8.1 No token -> 401 Unauthorized
        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isUnauthorized());

        // 8.2 Base ADMIN alone lacks capability -> 403 Forbidden with AI_FORBIDDEN
        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(BASE_ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("AI_FORBIDDEN")));

        // 8.3 Non-existent exerciseId -> 404 Not Found with EXERCISE_NOT_FOUND
        F01KnowledgeQueryApiRequest badExReq = new F01KnowledgeQueryApiRequest(
                "Nguyên tắc vận động cho người trưởng thành?",
                "vi",
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badExReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("EXERCISE_NOT_FOUND")));

        // 8.4 Too short question -> 400 Bad Request with VALIDATION_FAILED
        F01KnowledgeQueryApiRequest shortReq = new F01KnowledgeQueryApiRequest("abc", "vi", null, null);
        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(shortReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 8.5 Whitespace-only question -> 400 Bad Request
        F01KnowledgeQueryApiRequest spaceReq = new F01KnowledgeQueryApiRequest("       ", "vi", null, null);
        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(spaceReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 8.6 Unsupported language -> 400 Bad Request
        F01KnowledgeQueryApiRequest langReq = new F01KnowledgeQueryApiRequest("Nguyên tắc tập luyện là gì?", "fr", null, null);
        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(langReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Autowired
    private com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties aiProperties;

    @Test
    @DisplayName("9. Feature-Disabled Rejection (503 Service Unavailable)")
    void featureDisabledRejection() throws Exception {
        com.fitnesscoaching.platform.modules.ai.application.service.AiRunF01Service runService =
                org.springframework.test.util.TestSocketUtils.class != null ? null : null; // no-op

        // Test with disabled orchestrator
        com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties disabledProps =
                new com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties(
                        false, "simulated", "http://localhost:8000", "key", 5000, 10000
                );

        com.fitnesscoaching.platform.modules.ai.application.service.AiKnowledgeOrchestrationService disabledOrchestrator =
                new com.fitnesscoaching.platform.modules.ai.application.service.AiKnowledgeOrchestrationService(
                        org.mockito.Mockito.mock(com.fitnesscoaching.platform.modules.ai.application.port.in.AiRunF01UseCase.class),
                        simulatedAiClient,
                        provenanceRepository,
                        objectMapper,
                        disabledProps,
                        new com.fitnesscoaching.platform.modules.ai.application.service.F01RuntimeOutputValidator()
                );

        F01KnowledgeQueryApiRequest validReq = new F01KnowledgeQueryApiRequest(
                "Nguyên tắc vận động cho người trưởng thành?",
                "vi",
                null,
                UUID.randomUUID()
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                disabledOrchestrator.queryKnowledge(STUDENT_USER_ID, validReq)
        ).isInstanceOf(com.fitnesscoaching.platform.common.exception.AiFeatureDisabledException.class)
         .hasMessageContaining("disabled by administrator configuration");
    }
}
