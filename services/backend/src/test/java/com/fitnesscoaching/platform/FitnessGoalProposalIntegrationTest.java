package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.*;
import com.fitnesscoaching.platform.modules.goal.domain.ObjectivePriority;
import com.fitnesscoaching.platform.modules.goal.domain.ProposalDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FitnessGoalProposalIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("digital_fitness")
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
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @MockitoSpyBean
    private AuditService auditService;

    @BeforeEach
    void setUp() throws SQLException {
        reset(auditService);
        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            String actualUrl = conn.getMetaData().getURL();
            int containerPort = postgres.getMappedPort(5432);
            if (!actualUrl.contains(":" + containerPort) || actualUrl.contains(":5433")) {
                throw new IllegalStateException(
                        "CRITICAL SAFETY VIOLATION: Refusing to truncate! Connection URL ["
                                + actualUrl + "] does not match isolated test container port ["
                                + containerPort + "] or points to development database port 5433."
                );
            }
        }

        jdbcTemplate.execute("TRUNCATE TABLE fitness.audit_logs, fitness.goal_proposal_status_history, " +
                "fitness.goal_proposal_targets, fitness.goal_proposal_objectives, fitness.goal_proposals, " +
                "fitness.data_sharing_permissions, fitness.coaching_periods, fitness.coaching_relationships, " +
                "fitness.fitness_goal_status_history, fitness.goal_targets, fitness.goal_objectives, " +
                "fitness.fitness_goal_versions, fitness.fitness_goals, fitness.student_profiles, " +
                "fitness.trainer_profiles, fitness.user_roles, fitness.users CASCADE");

        jdbcTemplate.execute("""
                INSERT INTO fitness.goal_types (code, name, is_active)
                VALUES ('MUSCLE_GAIN', 'Muscle Gain', true),
                       ('FAT_LOSS', 'Fat Loss', true),
                       ('WEIGHT_GAIN', 'Weight Gain', true),
                       ('WEIGHT_LOSS', 'Weight Loss', true),
                       ('STRENGTH', 'Strength', true),
                       ('IMPROVE_FITNESS', 'Improve Fitness', true),
                       ('MAINTAIN', 'Maintain', true)
                ON CONFLICT (code) DO NOTHING;
                """);

        jdbcTemplate.execute("""
                INSERT INTO fitness.measurement_units (code, symbol, dimension, base_unit_code, multiplier_to_base, offset_to_base)
                VALUES ('KG', 'kg', 'MASS', 'KG', 1, 0),
                       ('CM', 'cm', 'LENGTH', 'M', 0.01, 0),
                       ('PERCENT', '%', 'RATIO', 'PERCENT', 1, 0),
                       ('SCORE', 'score', 'SCORE', 'SCORE', 1, 0)
                ON CONFLICT (code) DO NOTHING;
                """);

        jdbcTemplate.execute("""
                INSERT INTO fitness.metric_definitions (code, display_name, description, value_type, collection_type, default_unit_id, valid_min, valid_max, is_active)
                SELECT 'WEIGHT', 'Weight', 'Body weight', 'NUMERIC'::fitness.measurement_value_type, 'DIRECT'::fitness.metric_collection_type, u.id, 20.0, 500.0, true
                FROM fitness.measurement_units u
                WHERE u.code = 'KG'
                ON CONFLICT (code) DO NOTHING;
                """);
    }

    private String createAccessToken(UUID userId, String email, List<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .id(UUID.randomUUID().toString())
                .claim("email", email)
                .claim("roles", roles)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private UUID createActiveStudentUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, ?, ?, 'Test Student', '+84901234567', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """,
                userId, email, passwordEncoder.encode("Password123!"));

        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now()
                FROM fitness.roles r
                WHERE r.code = 'STUDENT'
                """,
                userId, userId);

        jdbcTemplate.update("""
                INSERT INTO fitness.student_profiles (user_id, date_of_birth, gender, training_experience_level, training_experience_months, available_days_per_week, preferred_session_minutes, onboarding_completed_at, created_at, updated_at)
                VALUES (?, '1995-05-15', 'MALE'::fitness.gender_code, 'INTERMEDIATE', 24, 4, 60, now(), now(), now())
                """,
                userId);

        return userId;
    }

    private UUID createActiveTrainerUser(String email, boolean verified, boolean activeProfile) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, ?, ?, 'Test Trainer', '+84909876543', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """,
                userId, email, passwordEncoder.encode("Password123!"));

        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now()
                FROM fitness.roles r
                WHERE r.code = 'TRAINER'
                """,
                userId, userId);

        String verStatus = verified ? "VERIFIED" : "PENDING";
        jdbcTemplate.update("""
                INSERT INTO fitness.trainer_profiles (user_id, bio, years_experience, verification_status, verified_at, is_accepting_students, is_active, created_at, updated_at)
                VALUES (?, 'Expert Coach', 5.0, ?::fitness.trainer_verification_state, CASE WHEN ? THEN now() ELSE NULL END, true, ?, now(), now())
                """,
                userId, verStatus, verified, activeProfile);

        return userId;
    }

    private UUID createCoachingContext(UUID trainerId, UUID studentId, boolean activePeriod, boolean grantPermission) {
        UUID relId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.coaching_relationships (id, trainer_id, student_id, status, requested_by, requested_at, accepted_at, started_at, created_at)
                VALUES (?, ?, ?, 'ACTIVE'::fitness.coaching_relationship_status, ?, now(), now(), now(), now())
                """,
                relId, trainerId, studentId, studentId);

        if (activePeriod) {
            jdbcTemplate.update("""
                    INSERT INTO fitness.coaching_periods (id, student_id, mode, coaching_relationship_id, trainer_id, started_at, created_by, created_at)
                    VALUES (gen_random_uuid(), ?, 'HUMAN_COACH'::fitness.coaching_mode, ?, ?, now(), ?, now())
                    """,
                    studentId, relId, trainerId, studentId);
        }

        if (grantPermission) {
            jdbcTemplate.update("""
                    INSERT INTO fitness.data_sharing_permissions (id, relationship_id, student_id, trainer_id, data_scope, decision, valid_from, granted_by, created_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, 'FITNESS_GOAL'::fitness.data_scope_code, 'ALLOW'::fitness.permission_decision, now(), ?, now())
                    """,
                    relId, studentId, trainerId, studentId);
        }

        return relId;
    }

    private UUID createActiveGoal(UUID studentId, String title) throws Exception {
        String studentToken = createAccessToken(studentId, "student@example.com", List.of("STUDENT"));
        CreateFitnessGoalRequest request = new CreateFitnessGoalRequest(
                title,
                LocalDate.now(),
                LocalDate.now().plusDays(90),
                90,
                true,
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of(new CreateGoalTargetRequest(1, null, null, BigDecimal.valueOf(80), BigDecimal.valueOf(75), null, null, (short) 1, null, null, null, null))
        );

        MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) resp.get("id"));
    }

    @Test
    @DisplayName("End-to-end: Trainer proposes goal change, student views, rejects, then proposes again and accepts")
    void endToEnd_goalProposalWorkflow() throws Exception {
        UUID studentId = createActiveStudentUser("student.e2e@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.e2e@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Original Weight Loss Goal");

        String trainerToken = createAccessToken(trainerId, "trainer.e2e@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.e2e@example.com", List.of("STUDENT"));

        // 1. Trainer submits Proposal 1
        CreateGoalProposalRequest proposal1Req = new CreateGoalProposalRequest(
                "Aggressive Fat Loss",
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                60,
                "Accelerate progress for competition",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of(new CreateGoalTargetRequest(1, null, null, BigDecimal.valueOf(78), BigDecimal.valueOf(72), null, null, (short) 1, null, null, null, null))
        );

        MvcResult create1Result = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(proposal1Req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.proposedTitle", is("Aggressive Fat Loss")))
                .andExpect(jsonPath("$.reason", is("Accelerate progress for competition")))
                .andReturn();

        Map<String, Object> prop1 = objectMapper.readValue(create1Result.getResponse().getContentAsString(), Map.class);
        UUID proposal1Id = UUID.fromString((String) prop1.get("id"));

        // Verify active goal in DB remains untouched
        String goalTitle = jdbcTemplate.queryForObject("SELECT title FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(goalTitle).isEqualTo("Original Weight Loss Goal");

        Integer versionCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(versionCount).isEqualTo(1);

        // 2. Student lists proposals
        mockMvc.perform(get("/api/v1/fitness-goal-proposals/me")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(proposal1Id.toString())))
                .andExpect(jsonPath("$.items[0].status", is("PENDING")));

        // 3. Student views proposal detail with baseVersion
        mockMvc.perform(get("/api/v1/fitness-goal-proposals/{proposalId}", proposal1Id)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(proposal1Id.toString())))
                .andExpect(jsonPath("$.baseVersion", notNullValue()))
                .andExpect(jsonPath("$.baseVersion.versionNumber", is(1)))
                .andExpect(jsonPath("$.baseVersion.title", is("Original Weight Loss Goal")));

        // 4. Student REJECTS Proposal 1
        DecideGoalProposalRequest rejectReq = new DecideGoalProposalRequest(ProposalDecision.REJECT, "Too intense right now");
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposal1Id)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.decisionNote", is("Too intense right now")));

        // Verify proposal 1 status in DB and goal remains untouched
        String prop1DbStatus = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.goal_proposals WHERE id = ?", String.class, proposal1Id);
        assertThat(prop1DbStatus).isEqualTo("REJECTED");

        Integer vCountAfterReject = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(vCountAfterReject).isEqualTo(1);

        // 5. Trainer submits Proposal 2 with moderate targets
        CreateGoalProposalRequest proposal2Req = new CreateGoalProposalRequest(
                "Moderate Fat Loss Phase 2",
                LocalDate.now(),
                LocalDate.now().plusDays(75),
                75,
                "Moderate pace adjustment",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of(new CreateGoalTargetRequest(1, null, null, BigDecimal.valueOf(78), BigDecimal.valueOf(74), null, null, (short) 1, null, null, null, null))
        );

        MvcResult create2Result = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(proposal2Req)))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> prop2 = objectMapper.readValue(create2Result.getResponse().getContentAsString(), Map.class);
        UUID proposal2Id = UUID.fromString((String) prop2.get("id"));

        // 6. Student ACCEPTS Proposal 2
        DecideGoalProposalRequest acceptReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposal2Id)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACCEPTED")));

        // 7. Verify DB state after ACCEPT:
        // Version 1 is closed (effective_until IS NOT NULL)
        Boolean v1EffectiveUntilSet = jdbcTemplate.queryForObject("SELECT (effective_until IS NOT NULL) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 1", Boolean.class, goalId);
        assertThat(v1EffectiveUntilSet).isTrue();

        // Version 2 exists and is current (effective_until IS NULL, lock_reason = APPROVED)
        String v2LockReason = jdbcTemplate.queryForObject("SELECT lock_reason::text FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 2", String.class, goalId);
        assertThat(v2LockReason).isEqualTo("APPROVED");

        String v2Title = jdbcTemplate.queryForObject("SELECT title FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 2", String.class, goalId);
        assertThat(v2Title).isEqualTo("Moderate Fat Loss Phase 2");

        // Goal title updated
        String updatedGoalTitle = jdbcTemplate.queryForObject("SELECT title FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(updatedGoalTitle).isEqualTo("Moderate Fat Loss Phase 2");

        // Current active version returned from GET /api/v1/fitness-goals/me/current is version 2
        mockMvc.perform(get("/api/v1/fitness-goals/me/current")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Moderate Fat Loss Phase 2")))
                .andExpect(jsonPath("$.currentVersion.versionNumber", is(2)))
                .andExpect(jsonPath("$.currentVersion.title", is("Moderate Fat Loss Phase 2")))
                .andExpect(jsonPath("$.currentVersion.lockReason", is("APPROVED")));

        // Verify status history table
        Integer historyRows = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.goal_proposal_status_history WHERE goal_proposal_id = ?", Integer.class, proposal2Id);
        assertThat(historyRows).isEqualTo(2); // Initial PENDING insert + ACCEPTED transition
    }

    @Test
    @DisplayName("Authority: Trainer without FITNESS_GOAL permission is rejected with 403")
    void trainerAuthority_missingPermission_rejected() throws Exception {
        UUID studentId = createActiveStudentUser("student.noperm@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.noperm@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, false); // no permission!
        UUID goalId = createActiveGoal(studentId, "Some Goal");

        String trainerToken = createAccessToken(trainerId, "trainer.noperm@example.com", List.of("TRAINER"));
        CreateGoalProposalRequest request = new CreateGoalProposalRequest(
                "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("DATA_SHARING_PERMISSION_REQUIRED")));
    }

    @Test
    @DisplayName("Authority: Ineligible trainer (profile suspended) is rejected with 403")
    void trainerAuthority_ineligible_rejected() throws Exception {
        UUID studentId = createActiveStudentUser("student.inelig@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.inelig@example.com", true, false); // is_active = false
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Some Goal");

        String trainerToken = createAccessToken(trainerId, "trainer.inelig@example.com", List.of("TRAINER"));
        CreateGoalProposalRequest request = new CreateGoalProposalRequest(
                "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_NOT_ELIGIBLE")));
    }

    @Test
    @DisplayName("Student Confirmation Authority: Non-owning student cannot decide proposal (403)")
    void studentAuthority_otherStudentCannotDecide() throws Exception {
        UUID student1Id = createActiveStudentUser("student1.auth@example.com");
        UUID student2Id = createActiveStudentUser("student2.auth@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.auth@example.com", true, true);
        createCoachingContext(trainerId, student1Id, true, true);
        UUID goalId = createActiveGoal(student1Id, "Student 1 Goal");

        String trainerToken = createAccessToken(trainerId, "trainer.auth@example.com", List.of("TRAINER"));
        CreateGoalProposalRequest request = new CreateGoalProposalRequest(
                "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> prop = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        UUID proposalId = UUID.fromString((String) prop.get("id"));

        String student2Token = createAccessToken(student2Id, "student2.auth@example.com", List.of("STUDENT"));
        DecideGoalProposalRequest decideReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);

        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + student2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decideReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("GOAL_PROPOSAL_ACCESS_DENIED")));
    }

    @Test
    @DisplayName("Concurrency: Double decision results in exactly one success (200) and one conflict (409)")
    void concurrency_doubleDecisionSerialized() throws Exception {
        UUID studentId = createActiveStudentUser("student.race@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.race@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Race Test");

        String trainerToken = createAccessToken(trainerId, "trainer.race@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.race@example.com", List.of("STUDENT"));

        CreateGoalProposalRequest request = new CreateGoalProposalRequest(
                "New Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> prop = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        UUID proposalId = UUID.fromString((String) prop.get("id"));

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        Runnable task1 = () -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                DecideGoalProposalRequest req = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
                var mvcRes = mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                                .header("Authorization", "Bearer " + studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andReturn();
                if (mvcRes.getResponse().getStatus() == 200) successCount.incrementAndGet();
                else if (mvcRes.getResponse().getStatus() == 409) conflictCount.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        };

        Runnable task2 = () -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                DecideGoalProposalRequest req = new DecideGoalProposalRequest(ProposalDecision.REJECT, "Reason");
                var mvcRes = mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                                .header("Authorization", "Bearer " + studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andReturn();
                if (mvcRes.getResponse().getStatus() == 200) successCount.incrementAndGet();
                else if (mvcRes.getResponse().getStatus() == 409) conflictCount.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        };

        executor.submit(task1);
        executor.submit(task2);
        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Stale Proposal: Accepting proposal with stale base version fails with 409 STALE_GOAL_PROPOSAL")
    void staleProposal_failsWithConflict() throws Exception {
        UUID studentId = createActiveStudentUser("student.stale@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.stale@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Stale Test");

        String trainerToken = createAccessToken(trainerId, "trainer.stale@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.stale@example.com", List.of("STUDENT"));

        // Create Proposal 1 (targeting version 1)
        CreateGoalProposalRequest req1 = new CreateGoalProposalRequest(
                "Proposal 1 Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason 1",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res1 = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposal1Id = UUID.fromString((String) objectMapper.readValue(res1.getResponse().getContentAsString(), Map.class).get("id"));

        // Create Proposal 2 (also targeting version 1)
        CreateGoalProposalRequest req2 = new CreateGoalProposalRequest(
                "Proposal 2 Title", LocalDate.now(), LocalDate.now().plusDays(40), 40, "Reason 2",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res2 = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposal2Id = UUID.fromString((String) objectMapper.readValue(res2.getResponse().getContentAsString(), Map.class).get("id"));

        // Student accepts Proposal 1 -> version 1 is closed, version 2 created!
        DecideGoalProposalRequest acceptReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposal1Id)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isOk());

        // Now Proposal 2 points to base version 1 which is already closed -> 409 STALE_GOAL_PROPOSAL!
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposal2Id)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("STALE_GOAL_PROPOSAL")));
    }

    @Test
    @DisplayName("Rollback: Audit failure during proposal creation rolls back entire database transaction")
    void rollback_auditFailureDuringCreation_rollsBack() throws Exception {
        UUID studentId = createActiveStudentUser("student.auditfail@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.auditfail@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Audit Failure Test");

        String trainerToken = createAccessToken(trainerId, "trainer.auditfail@example.com", List.of("TRAINER"));

        doThrow(new RuntimeException("Simulated audit write failure"))
                .when(auditService).recordAudit(any());

        CreateGoalProposalRequest request = new CreateGoalProposalRequest(
                "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());

        // Verify that NO proposal or history was persisted
        Integer propCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.goal_proposals WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(propCount).isEqualTo(0);

        Integer histCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.goal_proposal_status_history", Integer.class);
        assertThat(histCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Student Authority: Suspended student cannot list, view, or decide proposal (403)")
    void studentAuthority_suspendedStudent_forbidden() throws Exception {
        UUID studentId = createActiveStudentUser("student.susp@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.susp@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Suspended Student");

        String trainerToken = createAccessToken(trainerId, "trainer.susp@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.susp@example.com", List.of("STUDENT"));

        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "New Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // Suspend student in database
        jdbcTemplate.update("UPDATE fitness.users SET status = 'SUSPENDED'::fitness.account_status WHERE id = ?", studentId);

        // 1. Cannot list proposals
        mockMvc.perform(get("/api/v1/fitness-goal-proposals/me")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // 2. Cannot view proposal detail
        mockMvc.perform(get("/api/v1/fitness-goal-proposals/{proposalId}", proposalId)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // 3. Cannot decide proposal
        DecideGoalProposalRequest decideReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decideReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Student Authority: Revoked student role cannot list, view, or decide proposal (403)")
    void studentAuthority_revokedRole_forbidden() throws Exception {
        UUID studentId = createActiveStudentUser("student.revoked@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.revoked@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Revoked Role");

        String trainerToken = createAccessToken(trainerId, "trainer.revoked@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.revoked@example.com", List.of("STUDENT"));

        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "New Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // Revoke STUDENT role from user_roles
        jdbcTemplate.update("DELETE FROM fitness.user_roles WHERE user_id = ?", studentId);

        // 1. Cannot list proposals
        mockMvc.perform(get("/api/v1/fitness-goal-proposals/me")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // 2. Cannot view proposal detail
        mockMvc.perform(get("/api/v1/fitness-goal-proposals/{proposalId}", proposalId)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // 3. Cannot decide proposal
        DecideGoalProposalRequest decideReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decideReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Trainer Authority: Creator trainer can view proposal detail")
    void trainerAuthority_creatorCanViewDetail() throws Exception {
        UUID studentId = createActiveStudentUser("student.trview@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.trview@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Trainer View");

        String trainerToken = createAccessToken(trainerId, "trainer.trview@example.com", List.of("TRAINER"));
        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "Proposed Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(get("/api/v1/fitness-goal-proposals/{proposalId}", proposalId)
                        .header("Authorization", "Bearer " + trainerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(proposalId.toString())))
                .andExpect(jsonPath("$.proposedTitle", is("Proposed Title")));
    }

    @Test
    @DisplayName("Trainer Authority: Creator trainer loses relationship or permission cannot view proposal detail (403)")
    void trainerAuthority_lostRelationshipOrPermission_forbidden() throws Exception {
        UUID studentId = createActiveStudentUser("student.lostrel@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.lostrel@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Lost Rel");

        String trainerToken = createAccessToken(trainerId, "trainer.lostrel@example.com", List.of("TRAINER"));
        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "Proposed Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // Revoke permission by setting to DENY
        jdbcTemplate.update("UPDATE fitness.data_sharing_permissions SET decision = 'DENY'::fitness.permission_decision WHERE trainer_id = ? AND student_id = ?", trainerId, studentId);

        mockMvc.perform(get("/api/v1/fitness-goal-proposals/{proposalId}", proposalId)
                        .header("Authorization", "Bearer " + trainerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("DATA_SHARING_PERMISSION_REQUIRED")));

        // Re-grant permission but end relationship
        jdbcTemplate.update("UPDATE fitness.data_sharing_permissions SET decision = 'ALLOW'::fitness.permission_decision WHERE trainer_id = ? AND student_id = ?", trainerId, studentId);
        jdbcTemplate.update("UPDATE fitness.coaching_relationships SET status = 'ENDED'::fitness.coaching_relationship_status WHERE trainer_id = ? AND student_id = ?", trainerId, studentId);

        mockMvc.perform(get("/api/v1/fitness-goal-proposals/{proposalId}", proposalId)
                        .header("Authorization", "Bearer " + trainerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("COACHING_RELATIONSHIP_REQUIRED")));
    }

    @Test
    @DisplayName("Trainer Authority: created_by = null does not cause 500 NPE, returns 403")
    void trainerAuthority_createdByNull_noNpe() throws Exception {
        UUID studentId = createActiveStudentUser("student.nullcr@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.nullcr@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Null Creator");

        String trainerToken = createAccessToken(trainerId, "trainer.nullcr@example.com", List.of("TRAINER"));
        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "Proposed Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // Simulate created_by became NULL
        jdbcTemplate.update("UPDATE fitness.goal_proposals SET created_by = NULL WHERE id = ?", proposalId);

        mockMvc.perform(get("/api/v1/fitness-goal-proposals/{proposalId}", proposalId)
                        .header("Authorization", "Bearer " + trainerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("GOAL_PROPOSAL_ACCESS_DENIED")));
    }

    @Test
    @DisplayName("Timeline: Partial updates calculate matching dates and invalid timelines fail with 400")
    void timelineResolution_partialUpdatesAndValidation() throws Exception {
        UUID studentId = createActiveStudentUser("student.timeline@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.timeline@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Base 90-Day Goal");

        String trainerToken = createAccessToken(trainerId, "trainer.timeline@example.com", List.of("TRAINER"));

        // Case A: Only startDate provided -> duration preserved (90), targetDate = newStart + 90
        LocalDate newStart = LocalDate.now().plusDays(7);
        CreateGoalProposalRequest reqA = new CreateGoalProposalRequest(
                "Start Shifted", newStart, null, null, "Shift start",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposedStartDate", is(newStart.toString())))
                .andExpect(jsonPath("$.proposedDurationDays", is(90)))
                .andExpect(jsonPath("$.proposedTargetDate", is(newStart.plusDays(90).toString())));

        // Case B: Only targetDate provided -> start preserved, duration = target - start
        LocalDate newTarget = LocalDate.now().plusDays(45);
        CreateGoalProposalRequest reqB = new CreateGoalProposalRequest(
                "Target Shortened", null, newTarget, null, "Shorten target",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposedDurationDays", is(45)))
                .andExpect(jsonPath("$.proposedTargetDate", is(newTarget.toString())));

        // Case C: Only duration provided -> start preserved, target = start + duration
        CreateGoalProposalRequest reqC = new CreateGoalProposalRequest(
                "Duration 120", null, null, 120, "Extend duration",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqC)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposedDurationDays", is(120)))
                .andExpect(jsonPath("$.proposedTargetDate", is(LocalDate.now().plusDays(120).toString())));

        // Case D: Invalid timeline targetDate <= startDate -> 400
        CreateGoalProposalRequest reqD = new CreateGoalProposalRequest(
                "Invalid dates", LocalDate.now(), LocalDate.now(), null, "Zero duration",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Case E: Mismatch between targetDate and durationDays -> 400
        CreateGoalProposalRequest reqE = new CreateGoalProposalRequest(
                "Mismatch", LocalDate.now(), LocalDate.now().plusDays(30), 45, "Mismatch dates",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Accept Revalidation: Inactive goal type or metric deactivates before ACCEPT -> 400 without mutation")
    void revalidationOnAccept_inactiveCatalogItems_failsWith400() throws Exception {
        UUID studentId = createActiveStudentUser("student.reval@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.reval@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Revalidation");

        String trainerToken = createAccessToken(trainerId, "trainer.reval@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.reval@example.com", List.of("STUDENT"));

        // Create proposal with MUSCLE_GAIN objective and WEIGHT target
        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "Proposal with Catalog Items",
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                60,
                "Ready for review",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of(new CreateGoalTargetRequest(1, null, null, BigDecimal.valueOf(80), BigDecimal.valueOf(75), null, null, (short) 1, null, null, null, null))
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // 1. Deactivate goal type in DB
        jdbcTemplate.update("UPDATE fitness.goal_types SET is_active = false WHERE code = 'MUSCLE_GAIN'");

        DecideGoalProposalRequest acceptReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Proposal remains PENDING, version count = 1
        String pStatus1 = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.goal_proposals WHERE id = ?", String.class, proposalId);
        assertThat(pStatus1).isEqualTo("PENDING");
        Integer vCount1 = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(vCount1).isEqualTo(1);

        // Reactivate goal type, but deactivate metric definition
        jdbcTemplate.update("UPDATE fitness.goal_types SET is_active = true WHERE code = 'MUSCLE_GAIN'");
        jdbcTemplate.update("UPDATE fitness.metric_definitions SET is_active = false WHERE code = 'WEIGHT'");

        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Still PENDING and untouched
        String pStatus2 = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.goal_proposals WHERE id = ?", String.class, proposalId);
        assertThat(pStatus2).isEqualTo("PENDING");
        Integer vCount2 = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(vCount2).isEqualTo(1);

        // Restore metric definition
        jdbcTemplate.update("UPDATE fitness.metric_definitions SET is_active = true WHERE code = 'WEIGHT'");
    }

    @Test
    @DisplayName("Decision Note: REJECT requires non-blank decisionNote and note > 2000 chars is rejected (400)")
    void decisionNote_validationRules() throws Exception {
        UUID studentId = createActiveStudentUser("student.decnote@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.decnote@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Note Validation");

        String trainerToken = createAccessToken(trainerId, "trainer.decnote@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.decnote@example.com", List.of("STUDENT"));

        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // 1. REJECT with null note -> 400
        DecideGoalProposalRequest nullReject = new DecideGoalProposalRequest(ProposalDecision.REJECT, null);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nullReject)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 2. REJECT with blank note -> 400
        DecideGoalProposalRequest blankReject = new DecideGoalProposalRequest(ProposalDecision.REJECT, "   ");
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blankReject)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 3. Decision with > 2000 chars note -> 400
        String longNote = "a".repeat(2001);
        DecideGoalProposalRequest longNoteReq = new DecideGoalProposalRequest(ProposalDecision.REJECT, longNote);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(longNoteReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Proposal remains PENDING
        String pStatus = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.goal_proposals WHERE id = ?", String.class, proposalId);
        assertThat(pStatus).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Objective Notes: Notes are stored in proposal objectives and copied to goal version objectives on ACCEPT")
    void objectiveNotes_persistedAndCopiedToVersion() throws Exception {
        UUID studentId = createActiveStudentUser("student.notes@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.notes@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal with Notes");

        String trainerToken = createAccessToken(trainerId, "trainer.notes@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.notes@example.com", List.of("STUDENT"));

        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "Hypertrophy Focus",
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                60,
                "Hypertrophy periodization",
                null,
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, "Focus on upper chest development")),
                List.of()
        );

        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.objectives[0].notes", is("Focus on upper chest development")))
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // Student views proposal detail -> notes visible
        mockMvc.perform(get("/api/v1/fitness-goal-proposals/{proposalId}", proposalId)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectives[0].notes", is("Focus on upper chest development")));

        // Student accepts proposal
        DecideGoalProposalRequest acceptReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, "Looks great");
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isOk());

        // Verify notes copied to fitness.goal_objectives for version 2
        String copiedNotes = jdbcTemplate.queryForObject("""
                SELECT o.notes
                FROM fitness.goal_objectives o
                JOIN fitness.fitness_goal_versions v ON v.id = o.goal_version_id
                WHERE v.fitness_goal_id = ? AND v.version_number = 2
                """, String.class, goalId);
        assertThat(copiedNotes).isEqualTo("Focus on upper chest development");
    }

    @Test
    @DisplayName("Expiration: Decision on expired proposal fails with 409 GOAL_PROPOSAL_EXPIRED")
    void expiration_expiredProposal_failsWith409() throws Exception {
        UUID studentId = createActiveStudentUser("student.exp@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.exp@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Expiration");

        String trainerToken = createAccessToken(trainerId, "trainer.exp@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.exp@example.com", List.of("STUDENT"));

        // Create proposal with future expiration
        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "Expiring Proposal",
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                30,
                "Short window",
                Instant.now().plus(1, ChronoUnit.HOURS),
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // Update expires_at to 10 minutes ago
        jdbcTemplate.update("UPDATE fitness.goal_proposals SET expires_at = now() - interval '10 minutes' WHERE id = ?", proposalId);

        // Student tries to ACCEPT -> 409 GOAL_PROPOSAL_EXPIRED
        DecideGoalProposalRequest acceptReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_PROPOSAL_EXPIRED")));

        // Proposal remains PENDING
        String pStatus = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.goal_proposals WHERE id = ?", String.class, proposalId);
        assertThat(pStatus).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Concurrency: Two concurrent ACCEPT decisions on same base version -> 1x200, 1x409 STALE_GOAL_PROPOSAL, losing remains PENDING")
    void concurrency_concurrentAcceptOnSameBaseVersion() throws Exception {
        UUID studentId = createActiveStudentUser("student.racev@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.racev@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Base Version Race");

        String trainerToken = createAccessToken(trainerId, "trainer.racev@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.racev@example.com", List.of("STUDENT"));

        // Create Proposal 1 targeting version 1
        CreateGoalProposalRequest req1 = new CreateGoalProposalRequest(
                "Proposal 1 Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason 1",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res1 = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID prop1Id = UUID.fromString((String) objectMapper.readValue(res1.getResponse().getContentAsString(), Map.class).get("id"));

        // Create Proposal 2 targeting version 1
        CreateGoalProposalRequest req2 = new CreateGoalProposalRequest(
                "Proposal 2 Title", LocalDate.now(), LocalDate.now().plusDays(40), 40, "Reason 2",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res2 = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID prop2Id = UUID.fromString((String) objectMapper.readValue(res2.getResponse().getContentAsString(), Map.class).get("id"));

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger staleCount = new AtomicInteger();

        Runnable task1 = () -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                DecideGoalProposalRequest req = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
                var mvcRes = mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", prop1Id)
                                .header("Authorization", "Bearer " + studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andReturn();
                if (mvcRes.getResponse().getStatus() == 200) successCount.incrementAndGet();
                else if (mvcRes.getResponse().getStatus() == 409) staleCount.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        };

        Runnable task2 = () -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                DecideGoalProposalRequest req = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
                var mvcRes = mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", prop2Id)
                                .header("Authorization", "Bearer " + studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andReturn();
                if (mvcRes.getResponse().getStatus() == 200) successCount.incrementAndGet();
                else if (mvcRes.getResponse().getStatus() == 409) staleCount.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        };

        executor.submit(task1);
        executor.submit(task2);
        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(staleCount.get()).isEqualTo(1);

        // Total versions is exactly 2 (version 1 closed, version 2 created)
        Integer totalVersions = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(totalVersions).isEqualTo(2);

        // One proposal is ACCEPTED, the other proposal remains PENDING because its transaction rolled back
        Integer acceptedCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.goal_proposals WHERE fitness_goal_id = ? AND status = 'ACCEPTED'", Integer.class, goalId);
        Integer pendingCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.goal_proposals WHERE fitness_goal_id = ? AND status = 'PENDING'", Integer.class, goalId);
        assertThat(acceptedCount).isEqualTo(1);
        assertThat(pendingCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Rollback: Audit failure during ACCEPT rolls back version closing, new version, and status transition")
    void rollback_auditFailureDuringAccept_rollsBack() throws Exception {
        UUID studentId = createActiveStudentUser("student.auditacc@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.auditacc@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Audit Accept Rollback");

        String trainerToken = createAccessToken(trainerId, "trainer.auditacc@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.auditacc@example.com", List.of("STUDENT"));

        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "Proposed Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // Simulate audit failure during decision
        doThrow(new RuntimeException("Simulated audit write failure"))
                .when(auditService).recordAudit(any());

        DecideGoalProposalRequest acceptReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isInternalServerError());

        // Verify DB: proposal remains PENDING
        String pStatus = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.goal_proposals WHERE id = ?", String.class, proposalId);
        assertThat(pStatus).isEqualTo("PENDING");

        // Version 1 still current
        Boolean v1StillCurrent = jdbcTemplate.queryForObject("SELECT (effective_until IS NULL) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 1", Boolean.class, goalId);
        assertThat(v1StillCurrent).isTrue();

        // No version 2 created
        Integer vCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(vCount).isEqualTo(1);

        // Goal title unchanged
        String gTitle = jdbcTemplate.queryForObject("SELECT title FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(gTitle).isEqualTo("Goal for Audit Accept Rollback");

        // Proposal status history only has 1 row (creation)
        Integer hCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.goal_proposal_status_history WHERE goal_proposal_id = ?", Integer.class, proposalId);
        assertThat(hCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Rollback: Audit failure during REJECT rolls back proposal status transition and history")
    void rollback_auditFailureDuringReject_rollsBack() throws Exception {
        UUID studentId = createActiveStudentUser("student.auditrej@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.auditrej@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Audit Reject Rollback");

        String trainerToken = createAccessToken(trainerId, "trainer.auditrej@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.auditrej@example.com", List.of("STUDENT"));

        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "Proposed Title", LocalDate.now(), LocalDate.now().plusDays(30), 30, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // Simulate audit failure during decision
        doThrow(new RuntimeException("Simulated audit write failure"))
                .when(auditService).recordAudit(any());

        DecideGoalProposalRequest rejectReq = new DecideGoalProposalRequest(ProposalDecision.REJECT, "Rejecting for rollback test");
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectReq)))
                .andExpect(status().isInternalServerError());

        // Verify DB: proposal remains PENDING
        String pStatus = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.goal_proposals WHERE id = ?", String.class, proposalId);
        assertThat(pStatus).isEqualTo("PENDING");

        // Proposal status history only has 1 row (creation)
        Integer hCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.goal_proposal_status_history WHERE goal_proposal_id = ?", Integer.class, proposalId);
        assertThat(hCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Acceptance revalidation: proposal proposing different PRIMARY goal type returns 409 NEW_GOAL_JOURNEY_REQUIRED")
    void acceptProposal_changingPrimaryGoalType_returns409NewGoalJourneyRequired() throws Exception {
        UUID studentId = createActiveStudentUser("student.diffpri@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.diffpri@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Original Muscle Gain Goal");

        String studentToken = createAccessToken(studentId, "student.diffpri@example.com", List.of("STUDENT"));

        // Get V1 id
        UUID v1Id = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 1",
                UUID.class, goalId);

        // Get FAT_LOSS id
        short fatLossId = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.goal_types WHERE code = 'FAT_LOSS'", Short.class);

        // Directly insert a PENDING proposal that changes primary goal type to FAT_LOSS
        UUID proposalId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.goal_proposals (
                    id, student_id, fitness_goal_id, base_goal_version_id, source, created_by,
                    proposed_title, proposed_start_date, proposed_target_date, proposed_duration_days,
                    reason, status, expires_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, 'TRAINER'::fitness.proposal_source, ?,
                    'Switch to Fat Loss', '2026-10-01', '2027-01-31', 122,
                    'Proposing new journey', 'PENDING'::fitness.proposal_status, now() + interval '7 days', now(), now()
                )
                """, proposalId, studentId, goalId, v1Id, trainerId);

        jdbcTemplate.update("""
                INSERT INTO fitness.goal_proposal_objectives (
                    id, goal_proposal_id, goal_type_id, priority, sort_order
                ) VALUES (
                    gen_random_uuid(), ?, ?, 'PRIMARY'::fitness.objective_priority, 0
                )
                """, proposalId, fatLossId);

        DecideGoalProposalRequest acceptReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("NEW_GOAL_JOURNEY_REQUIRED")));

        // Verify proposal is still PENDING
        String status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.goal_proposals WHERE id = ?", String.class, proposalId);
        assertThat(status).isEqualTo("PENDING");

        // Verify version count is still 1
        Integer vCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(vCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Proposal acceptance: reason > 100 characters records <= 100 char change_reason and full summary")
    void acceptProposal_withLongReason_recordsShortChangeReasonAndFullSummary() throws Exception {
        UUID studentId = createActiveStudentUser("student.longreason@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.longreason@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Long Reason Acceptance");

        String trainerToken = createAccessToken(trainerId, "trainer.longreason@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.longreason@example.com", List.of("STUDENT"));

        String longReason = "R".repeat(150);
        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                "Revised Goal Title",
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                60,
                longReason,
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        DecideGoalProposalRequest acceptReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACCEPTED")));

        // Verify version 2 has change_reason <= 100 chars
        String changeReason = jdbcTemplate.queryForObject(
                "SELECT change_reason FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 2",
                String.class, goalId);
        assertThat(changeReason).isNotNull();
        assertThat(changeReason.length()).isLessThanOrEqualTo(100);

        String changeSummary = jdbcTemplate.queryForObject(
                "SELECT change_summary FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 2",
                String.class, goalId);
        assertThat(changeSummary).contains(longReason);
    }

    @Test
    @DisplayName("Proposal acceptance: identical snapshot to base version returns 409 GOAL_VERSION_NO_CHANGES and rolls back")
    void acceptProposal_noOpIdenticalSnapshot_returns409GoalVersionNoChanges() throws Exception {
        UUID studentId = createActiveStudentUser("student.noop@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.noop@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        String goalTitle = "Identical Goal For NoOp";
        UUID goalId = createActiveGoal(studentId, goalTitle);

        String trainerToken = createAccessToken(trainerId, "trainer.noop@example.com", List.of("TRAINER"));
        String studentToken = createAccessToken(studentId, "student.noop@example.com", List.of("STUDENT"));

        // Trainer proposes exact same snapshot as base version created by createActiveGoal
        CreateGoalProposalRequest propReq = new CreateGoalProposalRequest(
                goalTitle,
                LocalDate.now(),
                LocalDate.now().plusDays(90),
                90,
                "No actual changes proposed",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of(new CreateGoalTargetRequest(1, null, null, BigDecimal.valueOf(80), BigDecimal.valueOf(75), null, null, (short) 1, null, null, null, null))
        );
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        DecideGoalProposalRequest acceptReq = new DecideGoalProposalRequest(ProposalDecision.ACCEPT, null);
        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_VERSION_NO_CHANGES")));

        // Transaction rollback verification:
        // 1. Proposal remains PENDING
        String pStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.goal_proposals WHERE id = ?", String.class, proposalId);
        assertThat(pStatus).isEqualTo("PENDING");

        // 2. Base version remains current (effective_until IS NULL, version_number = 1)
        Integer v1Current = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 1 AND effective_until IS NULL",
                Integer.class, goalId);
        assertThat(v1Current).isEqualTo(1);

        // 3. No new version created in fitness_goal_versions (total versions = 1)
        Integer vCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?",
                Integer.class, goalId);
        assertThat(vCount).isEqualTo(1);

        // 4. Proposal status history only has initial PENDING entry
        Integer hCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.goal_proposal_status_history WHERE goal_proposal_id = ?",
                Integer.class, proposalId);
        assertThat(hCount).isEqualTo(1);

        // 5. Audit logs have no GOAL_PROPOSAL_ACCEPTED or FITNESS_GOAL_VERSION_CREATED
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE action IN ('GOAL_PROPOSAL_ACCEPTED', 'FITNESS_GOAL_VERSION_CREATED')",
                Integer.class);
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Proposal creation target validations: rejects zero and negative values for startValue, targetValue, targetMinValue, targetMaxValue")
    void createGoalProposal_targetValidations_rejectsZeroAndNegativeValues() throws Exception {
        UUID studentId = createActiveStudentUser("student.targetval@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.targetval@example.com", true, true);
        createCoachingContext(trainerId, studentId, true, true);
        UUID goalId = createActiveGoal(studentId, "Goal for Target Val");

        String trainerToken = createAccessToken(trainerId, "trainer.targetval@example.com", List.of("TRAINER"));

        // Case 1: startValue = 0
        CreateGoalProposalRequest req1 = new CreateGoalProposalRequest(
                "New Title", LocalDate.now(), LocalDate.now().plusDays(60), 60, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of(new CreateGoalTargetRequest(1, null, null, BigDecimal.ZERO, BigDecimal.valueOf(70), null, null, (short) 1, null, null, null, null))
        );
        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Case 2: targetValue = -1
        CreateGoalProposalRequest req2 = new CreateGoalProposalRequest(
                "New Title", LocalDate.now(), LocalDate.now().plusDays(60), 60, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of(new CreateGoalTargetRequest(1, null, null, BigDecimal.valueOf(80), BigDecimal.valueOf(-1), null, null, (short) 1, null, null, null, null))
        );
        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Case 3: targetMinValue = 0
        CreateGoalProposalRequest req3 = new CreateGoalProposalRequest(
                "New Title", LocalDate.now(), LocalDate.now().plusDays(60), 60, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of(new CreateGoalTargetRequest(1, null, null, BigDecimal.valueOf(80), null, BigDecimal.ZERO, BigDecimal.valueOf(90), (short) 1, null, null, null, null))
        );
        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Case 4: targetMaxValue = -5
        CreateGoalProposalRequest req4 = new CreateGoalProposalRequest(
                "New Title", LocalDate.now(), LocalDate.now().plusDays(60), 60, "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of(new CreateGoalTargetRequest(1, null, null, BigDecimal.valueOf(80), null, BigDecimal.valueOf(10), BigDecimal.valueOf(-5), (short) 1, null, null, null, null))
        );
        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req4)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }
}
