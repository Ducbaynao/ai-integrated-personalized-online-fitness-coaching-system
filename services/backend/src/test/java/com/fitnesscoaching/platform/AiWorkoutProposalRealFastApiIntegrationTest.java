package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRecommendationApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRecommendationApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutProposalApiRequest;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.ExercisePrescription;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionTemplate;
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
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Multi-tier End-to-End Integration Test executing against a REAL FastAPI process running Uvicorn.
 *
 * Verifies the complete HTTP communication chain:
 * MockMvc (HTTP Client) -> Spring Boot (AiWorkoutProposalController)
 *                       -> AiWorkoutProposalService
 *                       -> HttpF02AiServiceClient (Real HTTP Client)
 *                       -> Real FastAPI Uvicorn Process (/internal/v1/workout-proposals/generate)
 *                       -> Runtime Output Validator (F02RuntimeOutputValidator)
 *                       -> PostgreSQL (AiRecommendationRepository, AiRunRepository)
 *                       -> Accept/Reject commands
 *                       -> Workout Domain (WorkoutPlanCommandUseCase)
 *                       -> PostgreSQL (fitness.workout_plans, versions, sessions, prescriptions)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("F02 Multi-Tier End-to-End Real FastAPI Process Integration Tests")
class AiWorkoutProposalRealFastApiIntegrationTest {

    private static final String SERVICE_KEY = "test-real-fastapi-service-key-f02";
    private static Process fastApiProcess;
    private static int fastApiPort;

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_f02_real_fastapi_integration")
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
        long deadline = System.currentTimeMillis() + 12000;
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

        // Force HTTP mode pointing to real FastAPI process
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
    private AiRecommendationRepository recommendationRepository;

    @Autowired
    private WorkoutPlanQueryUseCase workoutPlanQuery;

    @Autowired
    private WorkoutPlanCommandUseCase workoutPlanCommands;

    @BeforeEach
    void seedExercises() {
        UUID adminId = person("ADMIN");
        List<UUID> vars = List.of(
                UUID.fromString("a0000000-0000-4000-8000-000000000001"),
                UUID.fromString("a0000000-0000-4000-8000-000000000002"),
                UUID.fromString("a0000000-0000-4000-8000-000000000003"),
                UUID.fromString("a0000000-0000-4000-8000-000000000004")
        );
        for (int i = 0; i < vars.size(); i++) {
            UUID varId = vars.get(i);
            UUID exId = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?) ON CONFLICT (id) DO NOTHING",
                    exId, "real-ex-" + i + "-" + UUID.randomUUID(), "Real Exercise " + i, adminId);
            jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true) ON CONFLICT (id) DO NOTHING",
                    varId, exId, "real-var-" + i + "-" + UUID.randomUUID(), "Real Variation " + i);
        }
    }

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

    private UUID person(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?,'hash','Person','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')",
                id, id + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?",
                id, id, role);
        if (role.equals("STUDENT")) {
            jdbc.update("""
                    INSERT INTO fitness.student_profiles(user_id, training_experience_level, available_days_per_week, preferred_session_minutes)
                    VALUES (?, 'BEGINNER', 3, 60)
                    """, id);
            createActiveGoal(id);
        }
        if (role.equals("TRAINER")) {
            jdbc.update("""
                    INSERT INTO fitness.trainer_profiles
                    (user_id,verification_status,verified_at,is_accepting_students,is_active,activity_status)
                    VALUES (?,'VERIFIED',clock_timestamp(),true,true,'ACTIVE')
                    """, id);
        }
        return id;
    }

    private UUID createActiveGoal(UUID studentId) {
        UUID goalId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.fitness_goals (
                    id, student_id, title, status, created_by, created_at, updated_at
                ) VALUES (
                    ?, ?, 'Real Goal', 'DRAFT'::fitness.lifecycle_status, ?, now(), now()
                )
                """, goalId, studentId, studentId);
        jdbc.update("""
                INSERT INTO fitness.fitness_goal_versions (
                    id, fitness_goal_id, version_number, title, start_date, target_date,
                    duration_days, effective_from, effective_until, change_reason, created_by,
                    created_at, locked_at, locked_by, lock_reason
                ) VALUES (
                    ?, ?, 1, 'Real Goal', current_date, current_date + 30,
                    30, now(), NULL, 'INITIAL_CREATION', ?,
                    now(), now(), ?, 'ACTIVATED'::fitness.version_lock_reason
                )
                """, versionId, goalId, studentId, studentId);
        jdbc.update("""
                UPDATE fitness.fitness_goals
                SET status = 'ACTIVE'::fitness.lifecycle_status, activated_at = now()
                WHERE id = ?
                """, goalId);
        return goalId;
    }

    private UUID createSelfDirectedCoachingPeriod(UUID studentId) {
        UUID periodId = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,created_by) VALUES (?,?,'SELF_DIRECTED',clock_timestamp()-interval '1 day',?)",
                periodId, studentId, studentId);
        return periodId;
    }

    private Map<String, Object> validFastApiPreferences(int days) {
        return Map.of(
                "daysPerWeek", days,
                "preferredDaysPerWeek", days,
                "preferredSessionMinutes", 60,
                "equipment", List.of("BODYWEIGHT"),
                "limitationsConfirmed", true,
                "startDate", java.time.LocalDate.now().plusDays(1).toString()
        );
    }

    private record HumanCoachSetup(UUID studentId, UUID trainerId, UUID relationshipId, UUID periodId) {}

    private HumanCoachSetup createHumanCoachSetup() {
        UUID student = person("STUDENT");
        UUID trainer = person("TRAINER");
        UUID relationship = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                (id,student_id,trainer_id,status,requested_by,requested_at,accepted_at,started_at)
                VALUES (?,?,?,'ACTIVE',?,clock_timestamp()-interval '2 days',
                        clock_timestamp()-interval '2 days',clock_timestamp()-interval '2 days')
                """, relationship, student, trainer, student);
        UUID period = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_periods
                (id,student_id,mode,coaching_relationship_id,trainer_id,started_at,created_by)
                VALUES (?,?,'HUMAN_COACH',?,?,clock_timestamp()-interval '2 days',?)
                """, period, student, relationship, trainer, trainer);
        UUID permission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id,relationship_id,student_id,trainer_id,data_scope,decision,access_level,valid_from,granted_by)
                VALUES (?,?,?,?, 'WORKOUT_PLAN','ALLOW','MANAGE'::fitness.data_access_level,
                        clock_timestamp()-interval '1 day',?)
                """, permission, relationship, student, trainer, student);
        UUID historyPermission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id,relationship_id,student_id,trainer_id,data_scope,decision,access_level,valid_from,granted_by)
                VALUES (?,?,?,?, 'WORKOUT_HISTORY','ALLOW','VIEW'::fitness.data_access_level,
                        clock_timestamp()-interval '1 day',?)
                """, historyPermission, relationship, student, trainer, student);
        return new HumanCoachSetup(student, trainer, relationship, period);
    }

    @Test
    @DisplayName("Multi-Tier SELF_DIRECTED: Web -> HttpF02AiServiceClient -> Real FastAPI -> Spring -> Accept -> PostgreSQL")
    void multiTier_selfDirected_e2e_realFastApi_succeeds() throws Exception {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);
        String token = generateToken(studentId, "STUDENT");

        // 1. Generate Proposal via REST API -> calls FastAPI over HTTP
        WorkoutProposalApiRequest req = new WorkoutProposalApiRequest(
                studentId, "http-gen-key-1", validFastApiPreferences(3)
        );

        String genResponseStr = mockMvc.perform(post("/api/v1/ai/workout-proposals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId", notNullValue()))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.decisionAuthority", is("STUDENT")))
                .andExpect(jsonPath("$.structuredPayload.plan.durationWeeks", is(1)))
                .andExpect(jsonPath("$.structuredPayload.plan.sessions.length()", is(3)))
                .andReturn().getResponse().getContentAsString();

        var genJson = objectMapper.readTree(genResponseStr);
        UUID recId = UUID.fromString(genJson.get("recommendationId").asText());
        UUID runId = UUID.fromString(genJson.get("runId").asText());

        // Verify AiRun in DB
        AiRun run = aiRunRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AiRunStatus.SUCCEEDED);
        assertThat(run.latencyMs()).isGreaterThan(0);

        // 2. Accept Proposal via REST API
        AcceptRecommendationApiRequest acceptReq = new AcceptRecommendationApiRequest(
                "http-accept-key-1", "Applying real HTTP generated proposal"
        );

        String acceptResponseStr = mockMvc.perform(post("/api/v1/ai/recommendations/" + recId + "/accept")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId", is(recId.toString())))
                .andExpect(jsonPath("$.status", is("APPLIED")))
                .andExpect(jsonPath("$.resultingEntityType", is("WORKOUT_PLAN")))
                .andExpect(jsonPath("$.replayed", is(false)))
                .andReturn().getResponse().getContentAsString();

        var acceptJson = objectMapper.readTree(acceptResponseStr);
        UUID resultingPlanId = UUID.fromString(acceptJson.get("resultingEntityId").asText());

        // 3. Verify Workout Plan in PostgreSQL domain store
        var activePlan = workoutPlanQuery.current(studentId, studentId);
        assertThat(activePlan.plan().id()).isEqualTo(resultingPlanId);
        assertThat(activePlan.plan().status()).isEqualTo(WorkoutPlanStatus.ACTIVE);
        assertThat(activePlan.plan().aggregateVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("Multi-Tier 4-Day Selection: FastAPI returns 4 sessions and Workout Plan stores 4 sessions")
    void multiTier_fourDaysSelection_returnsFourSessions() throws Exception {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);
        String token = generateToken(studentId, "STUDENT");

        WorkoutProposalApiRequest req = new WorkoutProposalApiRequest(
                studentId, "http-gen-4days", validFastApiPreferences(4)
        );

        String genResponseStr = mockMvc.perform(post("/api/v1/ai/workout-proposals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structuredPayload.plan.durationWeeks", is(1)))
                .andExpect(jsonPath("$.structuredPayload.plan.sessions.length()", is(4)))
                .andReturn().getResponse().getContentAsString();

        var genJson = objectMapper.readTree(genResponseStr);
        UUID recId = UUID.fromString(genJson.get("recommendationId").asText());

        // Accept the 4-day plan
        mockMvc.perform(post("/api/v1/ai/recommendations/" + recId + "/accept")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptRecommendationApiRequest("accept-4days", "Four day split"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPLIED")));

        var activePlan = workoutPlanQuery.current(studentId, studentId);
        Integer sessionCount = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.workout_plan_sessions WHERE workout_plan_version_id = ?",
                Integer.class, activePlan.currentVersion().id()
        );
        assertThat(sessionCount).isEqualTo(4);
    }

    @Test
    @DisplayName("Multi-Tier HUMAN_COACH: Trainer generates over HTTP -> Student 403 -> Trainer accepts -> APPLIED")
    void multiTier_humanCoach_authorityEnforced() throws Exception {
        HumanCoachSetup setup = createHumanCoachSetup();
        String trainerToken = generateToken(setup.trainerId(), "TRAINER");
        String studentToken = generateToken(setup.studentId(), "STUDENT");

        // 1. Trainer generates proposal for Student
        WorkoutProposalApiRequest req = new WorkoutProposalApiRequest(
                setup.studentId(), "trainer-http-gen-1", validFastApiPreferences(3)
        );

        String genResponseStr = mockMvc.perform(post("/api/v1/ai/workout-proposals")
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionAuthority", is("TRAINER")))
                .andReturn().getResponse().getContentAsString();

        var genJson = objectMapper.readTree(genResponseStr);
        UUID recId = UUID.fromString(genJson.get("recommendationId").asText());

        // 2. Student attempts to accept -> 403 Forbidden
        mockMvc.perform(post("/api/v1/ai/recommendations/" + recId + "/accept")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptRecommendationApiRequest("student-hack", "Student"))))
                .andExpect(status().isForbidden());

        // 3. Trainer accepts -> 200 APPLIED
        mockMvc.perform(post("/api/v1/ai/recommendations/" + recId + "/accept")
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptRecommendationApiRequest("trainer-apply", "Coach approved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPLIED")));
    }

    @Test
    @DisplayName("Multi-Tier Reject: Student rejects recommendation over HTTP -> status REJECTED, no plan created")
    void multiTier_reject_createsNoPlan() throws Exception {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);
        String token = generateToken(studentId, "STUDENT");

        WorkoutProposalApiRequest req = new WorkoutProposalApiRequest(
                studentId, "http-gen-reject", validFastApiPreferences(3)
        );

        String genResponseStr = mockMvc.perform(post("/api/v1/ai/workout-proposals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var genJson = objectMapper.readTree(genResponseStr);
        UUID recId = UUID.fromString(genJson.get("recommendationId").asText());

        // Reject
        mockMvc.perform(post("/api/v1/ai/recommendations/" + recId + "/reject")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RejectRecommendationApiRequest("Not suited for my knees"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.decisionNote", is("Not suited for my knees")));

        AiRecommendation rec = recommendationRepository.findById(recId).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.REJECTED);
    }

    @Test
    @DisplayName("Multi-Tier Edit Draft: Real FastAPI -> PUT /draft increments revision -> accept applies edited plan")
    void multiTier_editDraft_andApplyEditedVersion_succeeds() throws Exception {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);
        String studentToken = generateToken(studentId, "STUDENT");

        UUID otherTrainerId = person("TRAINER");
        String otherTrainerToken = generateToken(otherTrainerId, "TRAINER");

        // 1. Generate Proposal via REST API -> calls Real FastAPI
        WorkoutProposalApiRequest req = new WorkoutProposalApiRequest(
                studentId, "real-fastapi-edit-gen-1", validFastApiPreferences(3)
        );

        String genResponseStr = mockMvc.perform(post("/api/v1/ai/workout-proposals")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andReturn().getResponse().getContentAsString();

        var genJson = objectMapper.readTree(genResponseStr);
        UUID recId = UUID.fromString(genJson.get("recommendationId").asText());
        String originalPayloadStr = objectMapper.writeValueAsString(genJson.get("structuredPayload"));

        // 2. Unauthorized actor (Trainer) tries to edit Student's SELF_DIRECTED draft -> 403 Forbidden
        String modifiedPayloadStr = originalPayloadStr.replace("1-Week Full Body Starter", "Customized Real HTTP Plan");
        String updateReqJson = """
                {
                    "expectedRevision": 1,
                    "title": "Customized Real HTTP Plan",
                    "explanation": "Customized details",
                    "structuredPayload": %s
                }
                """.formatted(modifiedPayloadStr);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/ai/recommendations/" + recId + "/draft")
                        .header("Authorization", "Bearer " + otherTrainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateReqJson))
                .andExpect(status().isForbidden());

        // 3. Authorized Student edits draft -> 200 OK, revision moves to 2, isCustomized: true
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/ai/recommendations/" + recId + "/draft")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateReqJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision", is(2)))
                .andExpect(jsonPath("$.isCustomized", is(true)))
                .andExpect(jsonPath("$.title", is("Customized Real HTTP Plan")));

        // 4. Stale Revision Accept -> expectedRevision = 1 fails with 409 Conflict
        AcceptRecommendationApiRequest staleAcceptReq = new AcceptRecommendationApiRequest(
                "stale-key-1", "Attempting stale accept", 1
        );
        mockMvc.perform(post("/api/v1/ai/recommendations/" + recId + "/accept")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(staleAcceptReq)))
                .andExpect(status().isConflict());

        // 5. Valid Accept -> expectedRevision = 2 succeeds with 200 APPLIED
        AcceptRecommendationApiRequest validAcceptReq = new AcceptRecommendationApiRequest(
                "valid-key-edit", "Accepting revision 2", 2
        );
        mockMvc.perform(post("/api/v1/ai/recommendations/" + recId + "/accept")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validAcceptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPLIED")))
                .andExpect(jsonPath("$.replayed", is(false)));

        // 6. Verify resulting plan in database has the modified name
        var activePlan = workoutPlanQuery.current(studentId, studentId);
        assertThat(activePlan.plan().name()).isEqualTo("Customized Real HTTP Plan");

        // 7. Replay with matching key & expectedRevision: 2 -> returns replayed: true
        mockMvc.perform(post("/api/v1/ai/recommendations/" + recId + "/accept")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validAcceptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(true)));

        // 8. Replay with matching key but differing expectedRevision: 1 -> 409 Conflict
        AcceptRecommendationApiRequest diffRevReplayReq = new AcceptRecommendationApiRequest(
                "valid-key-edit", "Accepting revision 2", 1
        );
        mockMvc.perform(post("/api/v1/ai/recommendations/" + recId + "/accept")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(diffRevReplayReq)))
                .andExpect(status().isConflict());
    }
}
