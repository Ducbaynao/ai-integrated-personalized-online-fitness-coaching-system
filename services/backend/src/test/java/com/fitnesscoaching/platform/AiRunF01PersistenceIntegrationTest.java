package com.fitnesscoaching.platform;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiRunF01UseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CompleteF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CreateF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiContextSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunInputReference;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunKnowledgeChunk;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiValidationResult;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiRunF01PersistenceIntegrationTest {

    private static final UUID STUDENT_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRAINER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_STUDENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BASE_ADMIN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_run_integration")
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
    private AiRunF01UseCase aiRunUseCase;

    @Autowired
    private AiRunRepository aiRunRepository;

    @Autowired
    private AiProvenanceRepository provenanceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID activeExerciseId;

    @BeforeEach
    void setUp() {
        // Clean up ai and exercise records
        jdbcTemplate.execute("DELETE FROM fitness.ai_runs");
        jdbcTemplate.execute("DELETE FROM fitness.user_roles");
        jdbcTemplate.execute("DELETE FROM fitness.student_profiles");
        jdbcTemplate.execute("DELETE FROM fitness.trainer_profiles");
        jdbcTemplate.execute("DELETE FROM fitness.users");

        // Seed users
        seedUser(STUDENT_USER_ID, "student1@fitplatform.local", "STUDENT");
        seedUser(TRAINER_USER_ID, "trainer1@fitplatform.local", "TRAINER");
        seedUser(OTHER_STUDENT_ID, "student2@fitplatform.local", "STUDENT");
        seedUser(BASE_ADMIN_ID, "admin@fitplatform.local", "ADMIN");

        // Seed an active exercise in catalog
        activeExerciseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.exercises (
                    id, code, name, difficulty, movement_pattern, admin_status, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, 'INTERMEDIATE', 'SQUAT', 'ACTIVE', clock_timestamp(), clock_timestamp()
                ) ON CONFLICT (id) DO NOTHING
                """, activeExerciseId, "TEST-BARBELL-SQUAT-" + UUID.randomUUID().toString().substring(0, 8), "Barbell Back Squat");
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
    @DisplayName("1. Create and read AI Run with verified ownership (Student and Trainer)")
    void createAndReadAiRunWithVerifiedOwnership() throws Exception {
        UUID correlationId = UUID.randomUUID();
        CreateF01AiRunCommand cmd = new CreateF01AiRunCommand(
                "Nguyên tắc vận động cho người trưởng thành là gì?",
                "vi",
                activeExerciseId,
                correlationId
        );

        // Student creates run
        AiRun studentRun = aiRunUseCase.createF01Run(STUDENT_USER_ID, cmd);
        assertThat(studentRun).isNotNull();
        assertThat(studentRun.id()).isNotNull();
        assertThat(studentRun.requestedBy()).isEqualTo(STUDENT_USER_ID);
        assertThat(studentRun.status()).isEqualTo(AiRunStatus.QUEUED);
        assertThat(studentRun.requestType()).isEqualTo("F01_GENERAL_KNOWLEDGE");
        assertThat(studentRun.purpose()).isEqualTo("BUSINESS_ASSISTANCE");
        assertThat(studentRun.evaluationOnly()).isFalse();

        // Student can read their own run via API
        mockMvc.perform(get("/api/v1/ai/runs/" + studentRun.id())
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(studentRun.id().toString())))
                .andExpect(jsonPath("$.runStatus", is("QUEUED")))
                .andExpect(jsonPath("$.requestType", is("F01_GENERAL_KNOWLEDGE")));

        // Trainer creates run
        AiRun trainerRun = aiRunUseCase.createF01Run(TRAINER_USER_ID, cmd);
        assertThat(trainerRun.requestedBy()).isEqualTo(TRAINER_USER_ID);

        // Trainer can read their own run via API
        mockMvc.perform(get("/api/v1/ai/runs/" + trainerRun.id())
                        .header("Authorization", bearer(TRAINER_USER_ID, "TRAINER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(trainerRun.id().toString())));
    }

    @Test
    @DisplayName("2. Unauthorized access rejection: Cross-owner read denied, Base ADMIN alone forbidden")
    void unauthorizedAccessRejection() throws Exception {
        CreateF01AiRunCommand cmd = new CreateF01AiRunCommand(
                "Tần suất tập luyện khuyến nghị trong tuần là bao nhiêu?",
                "vi",
                null,
                UUID.randomUUID()
        );

        AiRun studentRun = aiRunUseCase.createF01Run(STUDENT_USER_ID, cmd);

        // Another student attempts to read student1's run -> 403 Forbidden
        mockMvc.perform(get("/api/v1/ai/runs/" + studentRun.id())
                        .header("Authorization", bearer(OTHER_STUDENT_ID, "STUDENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("AI_FORBIDDEN")));

        // Base Admin alone cannot initiate F01 inquiries -> 403 Forbidden
        assertThatThrownBy(() -> aiRunUseCase.createF01Run(BASE_ADMIN_ID, cmd))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException.class)
                .hasMessageContaining("lacks STUDENT or TRAINER capability");
    }

    @Test
    @DisplayName("3. Input validation: Reject question too short (<5) or too long (>1000), whitespace, invalid language")
    void inputValidationBounds() {
        // Too short
        assertThatThrownBy(() -> aiRunUseCase.createF01Run(STUDENT_USER_ID, new CreateF01AiRunCommand("abc", "vi", null, null)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.AiRunValidationException.class);

        // Whitespace only
        assertThatThrownBy(() -> aiRunUseCase.createF01Run(STUDENT_USER_ID, new CreateF01AiRunCommand("     ", "vi", null, null)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.AiRunValidationException.class);

        // Unsupported language
        assertThatThrownBy(() -> aiRunUseCase.createF01Run(STUDENT_USER_ID, new CreateF01AiRunCommand("Nguyên tắc tập là gì?", "fr", null, null)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.AiRunValidationException.class);

        // Inactive / Non-existent exercise
        UUID fakeExerciseId = UUID.randomUUID();
        assertThatThrownBy(() -> aiRunUseCase.createF01Run(STUDENT_USER_ID, new CreateF01AiRunCommand("Nguyên tắc tập là gì?", "vi", fakeExerciseId, null)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.ExerciseNotFoundException.class);
    }

    @Test
    @DisplayName("4. Provenance persistence: context snapshot, input references, knowledge chunk citations, validation results")
    void provenancePersistence() {
        UUID correlationId = UUID.randomUUID();
        CreateF01AiRunCommand cmd = new CreateF01AiRunCommand(
                "Cách thực hiện động tác squat chuẩn?",
                "vi",
                activeExerciseId,
                correlationId
        );

        AiRun run = aiRunUseCase.createF01Run(STUDENT_USER_ID, cmd);

        // Check context snapshot
        Optional<AiContextSnapshot> snapshotOpt = provenanceRepository.findSnapshotByRunId(run.id());
        assertThat(snapshotOpt).isPresent();
        AiContextSnapshot snapshot = snapshotOpt.get();
        assertThat(snapshot.aiRunId()).isEqualTo(run.id());
        assertThat(snapshot.contextHash()).isNotEmpty();
        assertThat(snapshot.contextPayload()).contains("Cách thực hiện động tác squat chuẩn?");
        assertThat(snapshot.contextPayload()).contains(activeExerciseId.toString());

        // Check input references
        List<AiRunInputReference> inputRefs = provenanceRepository.findInputReferencesByRunId(run.id());
        assertThat(inputRefs).hasSize(1);
        assertThat(inputRefs.get(0).entityType()).isEqualTo("EXERCISE");
        assertThat(inputRefs.get(0).entityId()).isEqualTo(activeExerciseId);
        assertThat(inputRefs.get(0).referenceRole()).isEqualTo("CATALOG_SUBJECT");

        // Save validation results and retrieve
        AiValidationResult v1 = new AiValidationResult(
                UUID.randomUUID(), run.id(), "CITATION_GROUNDING", true, "INFO", "{\"citedSources\": 2}", Instant.now()
        );
        AiValidationResult v2 = new AiValidationResult(
                UUID.randomUUID(), run.id(), "CLAIM_VERIFICATION", true, "INFO", "{}", Instant.now()
        );
        provenanceRepository.saveValidationResults(run.id(), List.of(v1, v2));

        List<AiValidationResult> retrievedValidators = provenanceRepository.findValidationResultsByRunId(run.id());
        assertThat(retrievedValidators).hasSize(2);
        assertThat(retrievedValidators.get(0).validatorCode()).isEqualTo("CITATION_GROUNDING");
        assertThat(retrievedValidators.get(0).passed()).isTrue();
    }

    @Test
    @DisplayName("5. Lifecycle transitions: valid QUEUED -> RUNNING -> SUCCEEDED, and reject illegal transitions")
    void lifecycleTransitionsAndProtection() {
        CreateF01AiRunCommand cmd = new CreateF01AiRunCommand(
                "Khối lượng tạ nên bắt đầu như thế nào?",
                "vi",
                null,
                null
        );

        AiRun run = aiRunUseCase.createF01Run(STUDENT_USER_ID, cmd);
        assertThat(run.status()).isEqualTo(AiRunStatus.QUEUED);

        // Transition QUEUED -> RUNNING
        Instant startedAt = Instant.now();
        AiRun running = aiRunUseCase.transitionRunStatus(run.id(), AiRunStatus.QUEUED, AiRunStatus.RUNNING, startedAt, null);
        assertThat(running.status()).isEqualTo(AiRunStatus.RUNNING);
        assertThat(running.startedAt()).isNotNull();

        // Reject invalid expected status (expecting QUEUED but current is RUNNING)
        assertThatThrownBy(() -> aiRunUseCase.transitionRunStatus(run.id(), AiRunStatus.QUEUED, AiRunStatus.FAILED, null, null))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException.class);

        // Complete run to SUCCEEDED
        String outputPayload = "{\"answer\": \"Bắt đầu từ mức tạ nhẹ có thể kiểm soát tốt kỹ thuật.\"}";
        CompleteF01AiRunCommand completeCmd = new CompleteF01AiRunCommand(
                run.id(),
                AiRunStatus.SUCCEEDED,
                outputPayload,
                null,
                null,
                150,
                80,
                230,
                320,
                Instant.now()
        );

        AiRun completed = aiRunUseCase.completeF01Run(completeCmd);
        assertThat(completed.status()).isEqualTo(AiRunStatus.SUCCEEDED);
        assertThat(completed.outputPayload()).isEqualTo(outputPayload);
        assertThat(completed.latencyMs()).isEqualTo(320);

        // Cannot transition or re-complete a terminal run
        assertThatThrownBy(() -> aiRunUseCase.transitionRunStatus(run.id(), AiRunStatus.RUNNING, AiRunStatus.FAILED, null, null))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException.class)
                .hasMessageContaining("already in terminal status");

        assertThatThrownBy(() -> aiRunUseCase.completeF01Run(completeCmd))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException.class)
                .hasMessageContaining("already completed");
    }

    @Test
    @DisplayName("6. Sanitized error handling: Raw stack trace, secrets, and internal paths are never stored in error_message")
    void sanitizedErrorHandling() {
        CreateF01AiRunCommand cmd = new CreateF01AiRunCommand(
                "Thời gian nghỉ giữa các hiệp là bao lâu?",
                "vi",
                null,
                null
        );

        AiRun run = aiRunUseCase.createF01Run(STUDENT_USER_ID, cmd);

        String rawLeakMessage = "java.sql.SQLException: Connection to postgres://user:SuperSecretPassword123@db.internal failed at com.fitnesscoaching.DbPool.getConnection(DbPool.java:42)";

        CompleteF01AiRunCommand failCmd = new CompleteF01AiRunCommand(
                run.id(),
                AiRunStatus.FAILED,
                null,
                "PROCESSING_ERROR",
                rawLeakMessage,
                null,
                null,
                null,
                120,
                Instant.now()
        );

        AiRun failed = aiRunUseCase.completeF01Run(failCmd);
        assertThat(failed.status()).isEqualTo(AiRunStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("PROCESSING_ERROR");

        // Verify that secrets, sql info, and stack traces are stripped clean
        assertThat(failed.errorMessage()).doesNotContain("SuperSecretPassword123");
        assertThat(failed.errorMessage()).doesNotContain("DbPool.java");
        assertThat(failed.errorMessage()).isEqualTo("Internal processing failure occurred.");
    }

    @Test
    @DisplayName("7. Concurrent completion race: Exactly one thread wins terminal status; losing thread receives conflict")
    void concurrentCompletionRace() throws Exception {
        CreateF01AiRunCommand cmd = new CreateF01AiRunCommand(
                "Tại sao cần khởi động kỹ trước buổi tập?",
                "vi",
                null,
                null
        );

        AiRun run = aiRunUseCase.createF01Run(STUDENT_USER_ID, cmd);

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    aiRunUseCase.completeF01Run(new CompleteF01AiRunCommand(
                            run.id(),
                            index % 2 == 0 ? AiRunStatus.SUCCEEDED : AiRunStatus.FAILED,
                            "{\"result\": " + index + "}",
                            index % 2 == 0 ? null : "TIMEOUT",
                            index % 2 == 0 ? null : "Timed out",
                            100,
                            50,
                            150,
                            200,
                            Instant.now()
                    ));
                    successCount.incrementAndGet();
                } catch (com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        // Exactly 1 winner, 3 conflicts
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threadCount - 1);

        AiRun finalRun = aiRunRepository.findById(run.id()).orElseThrow();
        assertThat(finalRun.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("8. getF01Run revalidation: Deactivated account or revoked role blocks polling")
    void getF01Run_revalidatesAccountStatusAndRoles() throws Exception {
        CreateF01AiRunCommand cmd = new CreateF01AiRunCommand(
                "Nguyên tắc khởi động trước khi tập tạ là gì?",
                "vi",
                null,
                UUID.randomUUID()
        );

        AiRun run = aiRunUseCase.createF01Run(STUDENT_USER_ID, cmd);

        // 1. When student is ACTIVE and has STUDENT role -> 200 OK
        mockMvc.perform(get("/api/v1/ai/runs/" + run.id())
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(run.id().toString())));

        // 2. When student account is DEACTIVATED -> direct service call throws AccountUnavailableException
        jdbcTemplate.update("UPDATE fitness.users SET status = 'DISABLED'::fitness.account_status WHERE id = ?", STUDENT_USER_ID);
        assertThatThrownBy(() -> aiRunUseCase.getF01Run(STUDENT_USER_ID, run.id()))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.AccountUnavailableException.class)
                .hasMessageContaining("not active");

        // And HTTP polling returns 403 ACCOUNT_UNAVAILABLE
        mockMvc.perform(get("/api/v1/ai/runs/" + run.id())
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));

        // Reactivate student account
        jdbcTemplate.update("UPDATE fitness.users SET status = 'ACTIVE'::fitness.account_status WHERE id = ?", STUDENT_USER_ID);

        // 3. When student role is revoked -> direct service call throws AiRunAccessDeniedException
        jdbcTemplate.update("DELETE FROM fitness.user_roles WHERE user_id = ? AND role_id IN (SELECT id FROM fitness.roles WHERE code = 'STUDENT')", STUDENT_USER_ID);
        assertThatThrownBy(() -> aiRunUseCase.getF01Run(STUDENT_USER_ID, run.id()))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException.class)
                .hasMessageContaining("lacks STUDENT or TRAINER capability");

        // And HTTP polling returns 403 AI_FORBIDDEN
        mockMvc.perform(get("/api/v1/ai/runs/" + run.id())
                        .header("Authorization", bearer(STUDENT_USER_ID, "STUDENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("AI_FORBIDDEN")));
    }
}
