package com.fitnesscoaching.platform;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.ConfirmBatch;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.Item;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-End Runtime Integration Test executing against a REAL FastAPI process.
 *
 * Verifies the complete live runtime path:
 * HTTP MockMvc -> Spring Boot (AiWorkoutRescheduleController)
 *              -> AiWorkoutRescheduleService
 *              -> HttpF05AiServiceClient (Real HTTP Client)
 *              -> Real FastAPI Uvicorn Process (/internal/v1/workout-reschedules/generate)
 *              -> F05RuntimeOutputValidator
 *              -> PostgreSQL (fitness.ai_recommendations, fitness.ai_runs)
 *              -> Accept / Reject commands
 *              -> Workout Schedule Domain (WorkoutScheduleChangeUseCase)
 *              -> PostgreSQL (fitness.planned_workouts, applications, audit_log)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("F05 Runtime Verification against Real FastAPI Process and PostgreSQL")
class AiWorkoutRescheduleRealFastApiIntegrationTest {

    private static final String SERVICE_KEY = "test-real-fastapi-service-key-f05";
    private static Process fastApiProcess;
    private static int fastApiPort;

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_f05_real_fastapi_integration")
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

        // Point AI client to real FastAPI process over HTTP
        registry.add("ai.service.mode", () -> "http");
        registry.add("ai.service.base-url", () -> "http://127.0.0.1:" + fastApiPort);
        registry.add("ai.service.internal-service-key", () -> SERVICE_KEY);
        registry.add("ai.service.connect-timeout-ms", () -> "5000");
        registry.add("ai.service.read-timeout-ms", () -> "10000");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiRunRepository aiRunRepository;
    @Autowired private AiRecommendationRepository recommendationRepository;
    @Autowired private WorkoutScheduleBatchUseCase batches;

    private record TestFixture(UUID studentId, UUID planId, UUID versionId, UUID sessionId) {}

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

    private TestFixture createFixture(boolean coachRequired) {
        UUID student = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        jdbc.update("INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone) VALUES (?, ?, 'hash', 'Student', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh')",
                student, student + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
        jdbc.update("""
                INSERT INTO fitness.workout_plans(id, student_id, name, source, status, created_by, decision_owner_type, decision_owner_id)
                VALUES (?, ?, 'Runtime Test Plan', 'STUDENT', 'DRAFT', ?, 'STUDENT', ?)
                """, plan, student, student, student);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions(id, workout_plan_id, version_number, change_level, created_by)
                VALUES (?, ?, 1, 'INITIAL', ?)
                """, version, plan, student);

        String supervision = coachRequired ? "COACH_REQUIRED" : "SELF_ALLOWED";
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id, workout_plan_version_id, week_number, day_number, sequence_number, name, supervision_requirement)
                VALUES (?, ?, 1, 1, 1, 'Chest & Back', ?::fitness.supervision_requirement)
                """, session, version, supervision);

        UUID exercise = UUID.randomUUID();
        UUID variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id, code, name, admin_status, created_by) VALUES (?, ?, 'Barbell Row', 'ACTIVE', ?)",
                exercise, "ex-" + exercise, student);
        jdbc.update("INSERT INTO fitness.exercise_variations(id, exercise_id, code, name, is_active) VALUES (?, ?, ?, 'Bent-over Row', true)",
                variation, exercise, "row-var-" + variation);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_session_exercises
                (id, workout_plan_session_id, exercise_variation_id, sequence_number, target_sets, target_reps_min, target_reps_max)
                VALUES (?, ?, ?, 1, 3, 8, 12)
                """, UUID.randomUUID(), session, variation);

        jdbc.update("""
                UPDATE fitness.workout_plan_versions SET effective_from=clock_timestamp(),
                    locked_at=clock_timestamp(), locked_by=?, lock_reason='ACTIVATED' WHERE id=?
                """, student, version);
        jdbc.update("""
                UPDATE fitness.workout_plans SET status='ACTIVE', version=version+1,
                    assigned_by=?, assigned_at=clock_timestamp() WHERE id=?
                """, student, plan);

        return new TestFixture(student, plan, version, session);
    }

    private UUID createOccurrence(TestFixture f, LocalDateTime start) {
        LocalDate anchor = start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return batches.confirmDirect(new ConfirmBatch(
                f.studentId(), f.planId(), f.versionId(), 1, anchor, "Asia/Ho_Chi_Minh",
                List.of(new Item(UUID.randomUUID(), f.sessionId(), 1, 1, 1,
                        start, start.plusHours(1), "+07:00", "+07:00")),
                "confirm-runtime-" + UUID.randomUUID()
        )).items().getFirst().occurrenceId();
    }

    private void setupCoachingPeriod(UUID studentId, UUID trainerId, String mode) {
        jdbc.update("INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone) VALUES (?, ?, 'hash', 'Trainer', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh') ON CONFLICT DO NOTHING",
                trainerId, trainerId + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id, role_id, assigned_by, assigned_at) SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'TRAINER' ON CONFLICT DO NOTHING", trainerId, trainerId);
        jdbc.update("INSERT INTO fitness.trainer_profiles(user_id, bio, verification_status, activity_status, is_active) VALUES (?, 'Certified trainer', 'VERIFIED', 'ACTIVE', true) ON CONFLICT DO NOTHING", trainerId);

        UUID relId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships (id, trainer_id, student_id, status, requested_by, requested_at, accepted_at, started_at, created_at)
                VALUES (?, ?, ?, 'ACTIVE'::fitness.coaching_relationship_status, ?, clock_timestamp(), clock_timestamp(), clock_timestamp(), clock_timestamp())
                """, relId, trainerId, studentId, studentId);

        jdbc.update("""
                INSERT INTO fitness.coaching_periods(id, student_id, mode, coaching_relationship_id, trainer_id, started_at, created_by, created_at)
                VALUES (gen_random_uuid(), ?, ?::fitness.coaching_mode, ?, ?, clock_timestamp(), ?, clock_timestamp())
                """, studentId, mode, relId, trainerId, studentId);

        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id, relationship_id, student_id, trainer_id, data_scope, decision, access_level, valid_from, granted_by)
                VALUES (gen_random_uuid(), ?, ?, ?, 'WORKOUT_PLAN', 'ALLOW', 'MANAGE'::fitness.data_access_level,
                        clock_timestamp() - interval '1 day', ?)
                """, relId, studentId, trainerId, studentId);
    }

    @Test
    @DisplayName("Complete HTTP E2E Flow: Generate (Real FastAPI) -> Read -> Accept -> Domain Readback & Audit")
    void completeE2EFlow_generateReadAccept_domainReadback() throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(10, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);
        String studentToken = generateToken(f.studentId(), "STUDENT");

        // 1. Generate Proposal via HTTP POST -> calls Real FastAPI
        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-runtime-e2e-1", "Heavy rain and traffic jam", null
        );

        MvcResult genResult = mockMvc.perform(post("/api/v1/ai/workout-reschedules")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(generateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").isNotEmpty())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.decisionAuthority", is("STUDENT")))
                .andExpect(jsonPath("$.structuredPayload.reschedule.occurrenceId", is(occurrenceId.toString())))
                .andExpect(jsonPath("$.structuredPayload.reschedule.destination.timezone", is("Asia/Ho_Chi_Minh")))
                .andReturn();

        JsonNode genJson = objectMapper.readTree(genResult.getResponse().getContentAsString());
        UUID recommendationId = UUID.fromString(genJson.get("recommendationId").asText());

        // 2. Read Proposal Detail via HTTP GET
        MvcResult readResult = mockMvc.perform(get("/api/v1/ai/workout-reschedules/" + recommendationId)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(recommendationId.toString())))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.structuredPayload.reschedule.occurrenceId", is(occurrenceId.toString())))
                .andReturn();

        JsonNode readJson = objectMapper.readTree(readResult.getResponse().getContentAsString());

        // 3. Accept Proposal via HTTP POST
        var acceptReq = new AcceptRescheduleApiRequest(
                "accept-runtime-e2e-1", "Rescheduled to match availability", 1, 0L, null, null
        );

        MvcResult acceptResult = mockMvc.perform(post("/api/v1/ai/workout-reschedules/" + recommendationId + "/accept")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId", is(recommendationId.toString())))
                .andExpect(jsonPath("$.status", is("APPLIED")))
                .andExpect(jsonPath("$.resultingEntityType", is("WORKOUT_OCCURRENCE")))
                .andExpect(jsonPath("$.resultingEntityId").isNotEmpty())
                .andExpect(jsonPath("$.replayed", is(false)))
                .andReturn();

        JsonNode acceptJson = objectMapper.readTree(acceptResult.getResponse().getContentAsString());
        UUID replacementId = UUID.fromString(acceptJson.get("resultingEntityId").asText());

        // 4. Domain Readback in PostgreSQL
        String oldOccurrenceStatus = jdbc.queryForObject(
                "SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, occurrenceId);
        assertThat(oldOccurrenceStatus).isEqualTo("RESCHEDULED");

        String newOccurrenceStatus = jdbc.queryForObject(
                "SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, replacementId);
        assertThat(newOccurrenceStatus).isEqualTo("SCHEDULED");

        UUID newLinkedSource = jdbc.queryForObject(
                "SELECT rescheduled_from_id FROM fitness.planned_workouts WHERE id=?", UUID.class, replacementId);
        assertThat(newLinkedSource).isEqualTo(occurrenceId);

        // Verify Recommendation Application Receipt
        Integer applicationCount = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id=?",
                Integer.class, recommendationId);
        assertThat(applicationCount).isOne();

        // Verify Audit Log
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE action='AI_WORKOUT_RESCHEDULE_APPLIED' AND target_id=?",
                Integer.class, replacementId);
        assertThat(auditCount).isOne();

        // 5. Export sanitized JSON evidence for repository documentation
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("timestamp", Instant.now().toString());
        evidence.put("flow", "HTTP MockMvc -> Spring Boot -> Real FastAPI -> Spring Boot -> Domain -> PostgreSQL");
        evidence.put("fastApiPort", fastApiPort);
        evidence.put("studentId", f.studentId().toString());
        evidence.put("originalOccurrenceId", occurrenceId.toString());
        evidence.put("replacementOccurrenceId", replacementId.toString());
        evidence.put("recommendationId", recommendationId.toString());
        evidence.put("generateResponse", genJson);
        evidence.put("readResponse", readJson);
        evidence.put("acceptResponse", acceptJson);
        evidence.put("domainReadback", Map.of(
                "oldOccurrenceStatus", oldOccurrenceStatus,
                "newOccurrenceStatus", newOccurrenceStatus,
                "applicationRecorded", true,
                "auditRecorded", true
        ));

        Path evidenceDir = Paths.get("../../docs/08-ai/artifacts/f05-runtime-evidence");
        Files.createDirectories(evidenceDir);
        Files.writeString(evidenceDir.resolve("runtime_verification_evidence.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    @Test
    @DisplayName("Idempotent retry with identical commandKey returns replay receipt without second mutation")
    void retrySameKey_idempotentReplay_noSecondMutation() throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(11, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);
        String studentToken = generateToken(f.studentId(), "STUDENT");

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-runtime-idem", "Conflict", null
        );

        MvcResult genResult = mockMvc.perform(post("/api/v1/ai/workout-reschedules")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(generateReq)))
                .andExpect(status().isOk())
                .andReturn();

        UUID recommendationId = UUID.fromString(
                objectMapper.readTree(genResult.getResponse().getContentAsString()).get("recommendationId").asText()
        );

        var acceptReq = new AcceptRescheduleApiRequest("idem-key-runtime-1", "First apply", 1, 0L, null, null);

        // First apply -> 200 isReplay=false
        mockMvc.perform(post("/api/v1/ai/workout-reschedules/" + recommendationId + "/accept")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(false)));

        // Second apply with identical commandKey -> 200 isReplay=true
        mockMvc.perform(post("/api/v1/ai/workout-reschedules/" + recommendationId + "/accept")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(true)));

        // Exactly 1 replacement occurrence exists
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?", Integer.class, occurrenceId);
        assertThat(count).isOne();
    }

    @Test
    @DisplayName("Reject proposal transitions status to REJECTED and leaves planned workout unchanged")
    void rejectProposal_leavesWorkoutUnchanged() throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(14, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);
        String studentToken = generateToken(f.studentId(), "STUDENT");

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-runtime-rej", "Schedule clash", null
        );

        MvcResult genResult = mockMvc.perform(post("/api/v1/ai/workout-reschedules")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(generateReq)))
                .andExpect(status().isOk())
                .andReturn();

        UUID recommendationId = UUID.fromString(
                objectMapper.readTree(genResult.getResponse().getContentAsString()).get("recommendationId").asText()
        );

        var rejectReq = new RejectRescheduleApiRequest("Not suitable destination time");

        mockMvc.perform(post("/api/v1/ai/workout-reschedules/" + recommendationId + "/reject")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")));

        AiRecommendation rec = recommendationRepository.findById(recommendationId).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.REJECTED);

        // Workout occurrence remains SCHEDULED
        String occurrenceStatus = jdbc.queryForObject(
                "SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, occurrenceId);
        assertThat(occurrenceStatus).isEqualTo("SCHEDULED");

        Integer replacements = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?", Integer.class, occurrenceId);
        assertThat(replacements).isZero();
    }

    @Test
    @DisplayName("Source occurrence version conflict returns HTTP 409 Conflict without mutation")
    void occurrenceVersionConflict_returnsConflict_noMutation() throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(15, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);
        String studentToken = generateToken(f.studentId(), "STUDENT");

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-runtime-ver", "Conflict", null
        );

        MvcResult genResult = mockMvc.perform(post("/api/v1/ai/workout-reschedules")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(generateReq)))
                .andExpect(status().isOk())
                .andReturn();

        UUID recommendationId = UUID.fromString(
                objectMapper.readTree(genResult.getResponse().getContentAsString()).get("recommendationId").asText()
        );

        // Bump occurrence version in DB
        jdbc.update("UPDATE fitness.planned_workouts SET version = version + 1 WHERE id=?", occurrenceId);

        var acceptReq = new AcceptRescheduleApiRequest("accept-key-stale-ver", "Accepting stale", 1, 0L, null, null);

        mockMvc.perform(post("/api/v1/ai/workout-reschedules/" + recommendationId + "/accept")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isConflict());

        // Occurrence still has original status and no replacement
        Integer replacements = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?", Integer.class, occurrenceId);
        assertThat(replacements).isZero();
    }

    @Test
    @DisplayName("Unauthorized actor in HUMAN_COACH mode is denied with HTTP 403")
    void unauthorizedActor_deniedWith403() throws Exception {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");

        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(16, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);
        String studentToken = generateToken(f.studentId(), "STUDENT");

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-runtime-auth", "Conflict", null
        );

        MvcResult genResult = mockMvc.perform(post("/api/v1/ai/workout-reschedules")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(generateReq)))
                .andExpect(status().isOk())
                .andReturn();

        UUID recommendationId = UUID.fromString(
                objectMapper.readTree(genResult.getResponse().getContentAsString()).get("recommendationId").asText()
        );

        // Student tries to unilaterally accept in HUMAN_COACH mode -> 403 Forbidden
        var acceptReq = new AcceptRescheduleApiRequest("accept-key-unauth", "Student trying to accept", 1, 0L, null, null);

        mockMvc.perform(post("/api/v1/ai/workout-reschedules/" + recommendationId + "/accept")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isForbidden());
    }
}
