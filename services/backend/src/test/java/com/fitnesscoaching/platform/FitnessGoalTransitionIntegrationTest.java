package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.audit.AuditService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
class FitnessGoalTransitionIntegrationTest {

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

        jdbcTemplate.execute("TRUNCATE TABLE fitness.audit_logs, fitness.goal_transitions, " +
                "fitness.goal_proposal_status_history, fitness.goal_proposal_targets, " +
                "fitness.goal_proposal_objectives, fitness.goal_proposals, " +
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
                       ('MAINTAIN', 'Maintain', true),
                       ('INACTIVE_GOAL_TYPE', 'Inactive Type', false)
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

        jdbcTemplate.execute("""
                INSERT INTO fitness.measurement_sources (code, name, source_kind)
                VALUES ('MANUAL', 'Manual Entry', 'MANUAL')
                ON CONFLICT (code) DO NOTHING;
                """);

        jdbcTemplate.execute("""
                INSERT INTO fitness.measurement_methods (code, name)
                VALUES ('SCALE', 'Weight Scale')
                ON CONFLICT (code) DO NOTHING;
                """);
    }

    private UUID createActiveTrainerUser(String email) {
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

        jdbcTemplate.update("""
                INSERT INTO fitness.trainer_profiles (user_id, public_slug, bio, is_accepting_students, is_active, created_at, updated_at)
                VALUES (?, 'trainer-slug-' || substr(md5(random()::text), 1, 8), 'Professional Trainer', true, true, now(), now())
                """,
                userId);

        return userId;
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

    private UUID createActiveGoal(UUID studentId, String token, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2026-12-31",
                                  "durationDays": 91,
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetValue": 75.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """.formatted(title)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString((String) objectMapper.readValue(result.getResponse().getContentAsString(), Map.class).get("id"));
    }

    @Test
    @DisplayName("Direct goal transition: success transitions old goal to REPLACED and creates new ACTIVE goal starting at V1")
    void createGoalTransition_success_replacesOldGoalAndCreatesNewGoal() throws Exception {
        UUID studentId = createActiveStudentUser("student.trans@example.com");
        String token = createAccessToken(studentId, "student.trans@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Muscle Building Phase");

        UUID oldV1Id = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 1",
                UUID.class, goalId);

        String payload = """
                {
                    "title": "Fat Loss Cutting Phase",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Switching journey from muscle gain to cutting",
                    "notes": "Student wants to lean down after bulking season",
                    "objectives": [
                        {
                            "goalTypeCode": "FAT_LOSS",
                            "priority": "PRIMARY"
                        }
                    ],
                    "targets": [
                        {
                            "metricCode": "WEIGHT",
                            "startValue": 85.0,
                            "targetValue": 78.0,
                            "unitCode": "KG"
                        }
                    ]
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/transitions/")))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.previousGoalId", is(goalId.toString())))
                .andExpect(jsonPath("$.newGoalId", notNullValue()))
                .andExpect(jsonPath("$.transitionReason", is("Switching journey from muscle gain to cutting")))
                .andExpect(jsonPath("$.notes", is("Student wants to lean down after bulking season")))
                .andExpect(jsonPath("$.newGoal.id", notNullValue()))
                .andExpect(jsonPath("$.newGoal.status", is("ACTIVE")))
                .andExpect(jsonPath("$.newGoal.currentVersion.versionNumber", is(1)))
                .andExpect(jsonPath("$.newGoal.currentVersion.title", is("Fat Loss Cutting Phase")))
                .andReturn();

        Map<String, Object> respMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        UUID transitionId = UUID.fromString((String) respMap.get("id"));
        UUID newGoalId = UUID.fromString((String) respMap.get("newGoalId"));

        // 1. Verify old goal is REPLACED
        String oldStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(oldStatus).isEqualTo("REPLACED");

        // 2. Verify old goal V1 is closed
        Instant effectiveUntil = jdbcTemplate.queryForObject(
                "SELECT effective_until FROM fitness.fitness_goal_versions WHERE id = ?", Instant.class, oldV1Id);
        assertThat(effectiveUntil).isNotNull();

        // 3. Verify status history on old goal
        String oldStatusHistory = jdbcTemplate.queryForObject(
                "SELECT to_status::text FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? ORDER BY changed_at DESC LIMIT 1",
                String.class, goalId);
        assertThat(oldStatusHistory).isEqualTo("REPLACED");

        // 4. Verify new goal is ACTIVE and owned by student
        String newStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ? AND student_id = ?",
                String.class, newGoalId, studentId);
        assertThat(newStatus).isEqualTo("ACTIVE");

        // 5. Verify new goal has Version 1 with FAT_LOSS
        Integer newVCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 1",
                Integer.class, newGoalId);
        assertThat(newVCount).isEqualTo(1);

        String newPrimaryCode = jdbcTemplate.queryForObject("""
                SELECT gt.code FROM fitness.goal_objectives o
                JOIN fitness.fitness_goal_versions v ON v.id = o.goal_version_id
                JOIN fitness.goal_types gt ON gt.id = o.goal_type_id
                WHERE v.fitness_goal_id = ? AND o.priority = 'PRIMARY'::fitness.objective_priority
                """, String.class, newGoalId);
        assertThat(newPrimaryCode).isEqualTo("FAT_LOSS");

        // 6. Verify transition row in goal_transitions
        Integer transCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.goal_transitions WHERE id = ? AND previous_goal_id = ? AND new_goal_id = ?",
                Integer.class, transitionId, goalId, newGoalId);
        assertThat(transCount).isEqualTo(1);

        // 7. Verify audit records created
        Integer auditCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM fitness.audit_logs
                WHERE actor_user_id = ? AND action IN (
                    'FITNESS_GOAL_REPLACED', 'FITNESS_GOAL_CREATED', 'FITNESS_GOAL_ACTIVATED', 'GOAL_TRANSITION_CREATED'
                )
                """, Integer.class, studentId);
        assertThat(auditCount).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("Direct goal transition: same primary goal type throws 409 SAME_GOAL_JOURNEY_NOT_PERMITTED")
    void createGoalTransition_samePrimaryType_throws409SameGoalJourneyNotPermitted() throws Exception {
        UUID studentId = createActiveStudentUser("student.same@example.com");
        String token = createAccessToken(studentId, "student.same@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Muscle Building Phase");

        String payload = """
                {
                    "title": "Muscle Building Phase 2",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Trying to transition within same journey",
                    "objectives": [
                        {
                            "goalTypeCode": "MUSCLE_GAIN",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("SAME_GOAL_JOURNEY_NOT_PERMITTED")));

        // Verify old goal remains ACTIVE
        String status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(status).isEqualTo("ACTIVE");

        // Verify no transition row created
        Integer transCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.goal_transitions WHERE previous_goal_id = ?", Integer.class, goalId);
        assertThat(transCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Direct goal transition: non-ACTIVE goal throws 409 INVALID_LIFECYCLE_TRANSITION")
    void createGoalTransition_nonActiveGoal_throws409InvalidLifecycleTransition() throws Exception {
        UUID studentId = createActiveStudentUser("student.nonactive@example.com");
        String token = createAccessToken(studentId, "student.nonactive@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Paused Goal");

        // Change status to PAUSED
        jdbcTemplate.update("UPDATE fitness.fitness_goals SET status = 'PAUSED'::fitness.lifecycle_status WHERE id = ?", goalId);

        String payload = """
                {
                    "title": "Fat Loss Phase",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Switching journey",
                    "objectives": [
                        {
                            "goalTypeCode": "FAT_LOSS",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));
    }

    @Test
    @DisplayName("Direct goal transition: non-owner student receives 403 ACCESS_DENIED")
    void createGoalTransition_notOwner_throws403AccessDenied() throws Exception {
        UUID ownerId = createActiveStudentUser("owner@example.com");
        String ownerToken = createAccessToken(ownerId, "owner@example.com", List.of("STUDENT"));
        UUID otherId = createActiveStudentUser("other@example.com");
        String otherToken = createAccessToken(otherId, "other@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(ownerId, ownerToken, "Owner Goal");

        String payload = """
                {
                    "title": "Fat Loss Phase",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Unauthorized transition",
                    "objectives": [
                        {
                            "goalTypeCode": "FAT_LOSS",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
    }

    @Test
    @DisplayName("Direct goal transition: suspended student receives 403 ACCOUNT_UNAVAILABLE")
    void createGoalTransition_suspendedStudent_throws403AccountUnavailable() throws Exception {
        UUID studentId = createActiveStudentUser("student.suspended@example.com");
        String token = createAccessToken(studentId, "student.suspended@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Goal");

        // Suspend user
        jdbcTemplate.update("UPDATE fitness.users SET status = 'SUSPENDED'::fitness.account_status WHERE id = ?", studentId);

        String payload = """
                {
                    "title": "Fat Loss Phase",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Switching journey",
                    "objectives": [
                        {
                            "goalTypeCode": "FAT_LOSS",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }

    @Test
    @DisplayName("GET transitions: returns transition history and detail for owner")
    void getTransitions_success_returnsListAndDetail() throws Exception {
        UUID studentId = createActiveStudentUser("student.gettrans@example.com");
        String token = createAccessToken(studentId, "student.gettrans@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Goal 1");

        String payload = """
                {
                    "title": "Goal 2 Fat Loss",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Completed Bulking",
                    "notes": "Starting Cut",
                    "objectives": [
                        {
                            "goalTypeCode": "FAT_LOSS",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> respMap = objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class);
        String transitionId = (String) respMap.get("id");

        // List transitions for goal 1
        mockMvc.perform(get("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(transitionId)))
                .andExpect(jsonPath("$[0].transitionReason", is("Completed Bulking")));

        // Get transition detail
        mockMvc.perform(get("/api/v1/fitness-goals/{goalId}/transitions/{transitionId}", goalId, transitionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(transitionId)))
                .andExpect(jsonPath("$.transitionReason", is("Completed Bulking")))
                .andExpect(jsonPath("$.notes", is("Starting Cut")));

        // Unknown transitionId returns 404
        mockMvc.perform(get("/api/v1/fitness-goals/{goalId}/transitions/{transitionId}", goalId, UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("GOAL_TRANSITION_NOT_FOUND")));
    }

    @Test
    @DisplayName("Concurrency: concurrent transitions on same active goal allows exactly 1 to win")
    void concurrentTransitions_onlyOneWins() throws Exception {
        UUID studentId = createActiveStudentUser("student.concurrent@example.com");
        String token = createAccessToken(studentId, "student.concurrent@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Bulking Goal");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    String payload = """
                            {
                                "title": "Cutting Goal %d",
                                "startDate": "2027-01-01",
                                "targetDate": "2027-03-31",
                                "durationDays": 89,
                                "transitionReason": "Concurrent attempt %d",
                                "objectives": [
                                    {
                                        "goalTypeCode": "FAT_LOSS",
                                        "priority": "PRIMARY"
                                    }
                                ]
                            }
                            """.formatted(idx, idx);

                    MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn();

                    int status = res.getResponse().getStatus();
                    if (status == 201) {
                        successCount.incrementAndGet();
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Exactly one transition record in DB
        Integer transCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.goal_transitions WHERE previous_goal_id = ?", Integer.class, goalId);
        assertThat(transCount).isEqualTo(1);

        // Exactly one active goal for student
        Integer activeGoalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goals WHERE student_id = ? AND status = 'ACTIVE'::fitness.lifecycle_status",
                Integer.class, studentId);
        assertThat(activeGoalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Transaction rollback: failure during audit rolls back the entire transition")
    void createGoalTransition_auditFailure_rollsBackTransaction() throws Exception {
        UUID studentId = createActiveStudentUser("student.rollback@example.com");
        String token = createAccessToken(studentId, "student.rollback@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Goal to Rollback");

        doThrow(new RuntimeException("Simulated audit failure"))
                .when(auditService).recordAudit(any());

        String payload = """
                {
                    "title": "Fat Loss Cutting Phase",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Switching journey",
                    "objectives": [
                        {
                            "goalTypeCode": "FAT_LOSS",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isInternalServerError());

        // Verify old goal remains ACTIVE
        String status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(status).isEqualTo("ACTIVE");

        // Verify no transition row created
        Integer transCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.goal_transitions WHERE previous_goal_id = ?", Integer.class, goalId);
        assertThat(transCount).isEqualTo(0);

        // Verify only 1 goal exists in total
        Integer goalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goals WHERE student_id = ?", Integer.class, studentId);
        assertThat(goalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("DB append-only trigger: UPDATE or DELETE on goal_transitions is blocked by trigger")
    void dbTrigger_preventsUpdateOrDeleteOnGoalTransitions() throws Exception {
        UUID studentId = createActiveStudentUser("student.trigger@example.com");
        String token = createAccessToken(studentId, "student.trigger@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Bulking Phase");

        String payload = """
                {
                    "title": "Fat Loss Cutting Phase",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Switching journey",
                    "objectives": [
                        {
                            "goalTypeCode": "FAT_LOSS",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> respMap = objectMapper.readValue(res.getResponse().getContentAsString(), Map.class);
        UUID transitionId = UUID.fromString((String) respMap.get("id"));

        // UPDATE should fail due to trigger
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE fitness.goal_transitions SET transition_reason = 'Tampered' WHERE id = ?", transitionId))
                .hasMessageContaining("goal_transitions is append-only");

        // DELETE should fail due to trigger
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM fitness.goal_transitions WHERE id = ?", transitionId))
                .hasMessageContaining("goal_transitions is append-only");
    }

    @Test
    @DisplayName("History preservation: previous goal versions and history remain accessible after transition")
    void historyPreservation_previousGoalVersionsAccessible() throws Exception {
        UUID studentId = createActiveStudentUser("student.history@example.com");
        String token = createAccessToken(studentId, "student.history@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Bulking Goal 1");

        // Transition to new goal
        String payload = """
                {
                    "title": "Cutting Goal 2",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Switching journey",
                    "objectives": [
                        {
                            "goalTypeCode": "FAT_LOSS",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        // Previous goal version history is still fully queryable
        mockMvc.perform(get("/api/v1/fitness-goals/{goalId}/versions", goalId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].versionNumber", is(1)))
                .andExpect(jsonPath("$.items[0].title", is("Bulking Goal 1")));
    }

    @Test
    @DisplayName("History preservation: workouts, measurements, nutrition, and progress remain linked to old goal after transition")
    void historyPreservation_crossDomain_workoutsMeasurementsNutritionProgressIntact() throws Exception {
        UUID studentId = createActiveStudentUser("student.fulldata@example.com");
        String token = createAccessToken(studentId, "student.fulldata@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Phase 1 Muscle");

        UUID v1Id = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 1",
                UUID.class, goalId);

        // 1. Seed workout_plan linked to goalId
        UUID workoutPlanId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.workout_plans (id, student_id, fitness_goal_id, name, source, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 'Hypertrophy Plan', 'STUDENT'::fitness.plan_source, 'DRAFT'::fitness.plan_status, ?, now(), now())
                """, workoutPlanId, studentId, goalId, studentId);

        // 2. Seed nutrition_goal linked to goalId
        UUID nutritionGoalId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.nutrition_goals (id, student_id, fitness_goal_id, title, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 'Caloric Surplus', 'DRAFT'::fitness.lifecycle_status, ?, now(), now())
                """, nutritionGoalId, studentId, goalId, studentId);

        // 3. Seed progress_snapshot linked to goalId
        UUID snapshotId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.progress_snapshots (id, student_id, fitness_goal_id, goal_version_id, scope, period_start, period_end, algorithm_version, calculated_at)
                VALUES (?, ?, ?, ?, 'CURRENT_GOAL', '2026-10-01', '2026-10-31', 'v1.0.0', now())
                """, snapshotId, studentId, goalId, v1Id);

        // 4. Seed goal_progress_checkpoint linked to goalId
        UUID checkpointId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.goal_progress_checkpoints (id, fitness_goal_id, goal_version_id, checkpoint_date, status, calculated_at)
                VALUES (?, ?, ?, '2026-10-15', 'CALCULATED', now())
                """, checkpointId, goalId, v1Id);

        // 5. Seed measurement for student
        UUID measurementId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.measurements (id, student_id, metric_definition_id, numeric_value, unit_id, measured_at, source_id, method_id, created_at)
                VALUES (?, ?, (SELECT id FROM fitness.metric_definitions WHERE code = 'WEIGHT'), 75.0, (SELECT id FROM fitness.measurement_units WHERE code = 'KG'), now(), (SELECT id FROM fitness.measurement_sources WHERE code = 'MANUAL'), (SELECT id FROM fitness.measurement_methods WHERE code = 'SCALE'), now())
                """, measurementId, studentId);

        // Perform transition to new goal (FAT_LOSS)
        String payload = """
                {
                    "title": "Phase 2 Fat Loss",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Transitioning to fat loss journey",
                    "objectives": [
                        { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                    ]
                }
                """;

        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> respMap = objectMapper.readValue(res.getResponse().getContentAsString(), Map.class);
        UUID newGoalId = UUID.fromString((String) respMap.get("newGoalId"));

        // Assert: previous goal is REPLACED and its V1 is closed
        String oldStatus = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(oldStatus).isEqualTo("REPLACED");

        // Assert: workout_plan still references old goal
        UUID planGoal = jdbcTemplate.queryForObject("SELECT fitness_goal_id FROM fitness.workout_plans WHERE id = ?", UUID.class, workoutPlanId);
        assertThat(planGoal).isEqualTo(goalId);

        // Assert: nutrition_goal still references old goal
        UUID nutGoal = jdbcTemplate.queryForObject("SELECT fitness_goal_id FROM fitness.nutrition_goals WHERE id = ?", UUID.class, nutritionGoalId);
        assertThat(nutGoal).isEqualTo(goalId);

        // Assert: progress_snapshot still references old goal
        UUID snapGoal = jdbcTemplate.queryForObject("SELECT fitness_goal_id FROM fitness.progress_snapshots WHERE id = ?", UUID.class, snapshotId);
        assertThat(snapGoal).isEqualTo(goalId);

        // Assert: goal_progress_checkpoint still references old goal
        UUID chkGoal = jdbcTemplate.queryForObject("SELECT fitness_goal_id FROM fitness.goal_progress_checkpoints WHERE id = ?", UUID.class, checkpointId);
        assertThat(chkGoal).isEqualTo(goalId);

        // Assert: measurement is still present for student
        BigDecimal weight = jdbcTemplate.queryForObject("SELECT numeric_value FROM fitness.measurements WHERE id = ?", BigDecimal.class, measurementId);
        assertThat(weight).isEqualByComparingTo("75.0");

        // Assert: new goal has NO workout plans, nutrition goals, progress snapshots, or checkpoints attached to it
        int newPlanCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.workout_plans WHERE fitness_goal_id = ?", Integer.class, newGoalId);
        assertThat(newPlanCount).isZero();

        int newNutCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.nutrition_goals WHERE fitness_goal_id = ?", Integer.class, newGoalId);
        assertThat(newNutCount).isZero();

        int newSnapCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.progress_snapshots WHERE fitness_goal_id = ?", Integer.class, newGoalId);
        assertThat(newSnapCount).isZero();

        int newChkCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.goal_progress_checkpoints WHERE fitness_goal_id = ?", Integer.class, newGoalId);
        assertThat(newChkCount).isZero();
    }

    @Test
    @DisplayName("Authorization: Trainer cannot directly transition a student's fitness goal -> 403")
    void authorization_trainerCannotDirectlyTransitionStudentGoal() throws Exception {
        UUID studentId = createActiveStudentUser("student.trcheck@example.com");
        String studentToken = createAccessToken(studentId, "student.trcheck@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, studentToken, "Goal for Trainer Block");

        UUID trainerId = createActiveTrainerUser("trainer.directblock@example.com");
        String trainerToken = createAccessToken(trainerId, "trainer.directblock@example.com", List.of("TRAINER"));

        String payload = """
                {
                    "title": "Trainer Proposed Change",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Trainer direct transition attempt",
                    "objectives": [
                        { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Authorization: Unauthenticated transition request returns 401 Unauthorized")
    void authorization_unauthenticatedRequest_returns401() throws Exception {
        UUID studentId = createActiveStudentUser("student.noauth@example.com");
        String token = createAccessToken(studentId, "student.noauth@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Goal for No Auth");

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Unauthenticated Attempt",
                                    "startDate": "2027-01-01",
                                    "targetDate": "2027-03-31",
                                    "durationDays": 89,
                                    "transitionReason": "Testing no auth",
                                    "objectives": [
                                        { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                                    ]
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authorization: User with STUDENT role but missing student profile returns 404")
    void authorization_missingStudentProfile_returns404() throws Exception {
        UUID studentId = createActiveStudentUser("student.hasgoal@example.com");
        String studentToken = createAccessToken(studentId, "student.hasgoal@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, studentToken, "Goal Before Profile Removal");

        // Create second user with STUDENT role but NO profile
        UUID noProfileUserId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, 'student.noprofile@example.com', ?, 'No Profile User', '+84901234568', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """,
                noProfileUserId, passwordEncoder.encode("Password123!"));
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now()
                FROM fitness.roles r
                WHERE r.code = 'STUDENT'
                """,
                noProfileUserId, noProfileUserId);
        String noProfileToken = createAccessToken(noProfileUserId, "student.noprofile@example.com", List.of("STUDENT"));

        String payload = """
                {
                    "title": "Missing Profile Attempt",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Testing missing profile",
                    "objectives": [
                        { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + noProfileToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_PROFILE_NOT_FOUND")));
    }

    @Test
    @DisplayName("Authorization: User with revoked STUDENT role returns 403")
    void authorization_revokedStudentRole_returns403() throws Exception {
        UUID studentId = createActiveStudentUser("student.revoked@example.com");
        String token = createAccessToken(studentId, "student.revoked@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Goal Before Role Revoke");

        // Revoke role
        jdbcTemplate.update("DELETE FROM fitness.user_roles WHERE user_id = ?", studentId);

        String payload = """
                {
                    "title": "Revoked Role Attempt",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Testing revoked role",
                    "objectives": [
                        { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));
    }

    @Test
    @DisplayName("Validation: Transition reason boundary: exactly 100 characters accepted, 101 characters rejected (400)")
    void validation_transitionReasonBoundary_100CharsAccepted_101CharsRejected() throws Exception {
        UUID studentId = createActiveStudentUser("student.boundary@example.com");
        String token = createAccessToken(studentId, "student.boundary@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Boundary Test Goal");

        // 101 characters
        String reason101 = "A".repeat(101);
        String payload101 = """
                {
                    "title": "Overlength Reason Goal",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "%s",
                    "objectives": [
                        { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                    ]
                }
                """.formatted(reason101);

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 100 characters
        String reason100 = "B".repeat(100);
        String payload100 = """
                {
                    "title": "Exact Length Reason Goal",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "%s",
                    "objectives": [
                        { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                    ]
                }
                """.formatted(reason100);

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload100))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transitionReason", is(reason100)));
    }

    @Test
    @DisplayName("Validation: Special characters in reason and title are escaped safely in audit JSON")
    void validation_specialCharactersInReasonAndTitle_auditJsonValid() throws Exception {
        UUID studentId = createActiveStudentUser("student.special@example.com");
        String token = createAccessToken(studentId, "student.special@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Goal Before Special Chars");

        String payload = """
                {
                    "title": "Special \\"Goal\\" with \\\\ backslash",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Reason with \\"quotes\\" and \\\\ slashes and \\n newlines",
                    "objectives": [
                        { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        // Verify audit logs stored valid parseable JSON
        List<String> auditJsons = jdbcTemplate.queryForList(
                "SELECT metadata::text FROM fitness.audit_logs WHERE actor_user_id = ?", String.class, studentId);
        assertThat(auditJsons).isNotEmpty();
        for (String json : auditJsons) {
            assertThat(objectMapper.readTree(json)).isNotNull();
        }
    }

    @Test
    @DisplayName("Validation: Invalid timeline (startDate > targetDate) and duplicate targets return 400")
    void validation_invalidTimelineAndDuplicateTargets() throws Exception {
        UUID studentId = createActiveStudentUser("student.badval@example.com");
        String token = createAccessToken(studentId, "student.badval@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Goal For Bad Validation");

        // Case 1: startDate after targetDate
        String badTimelinePayload = """
                {
                    "title": "Bad Timeline Goal",
                    "startDate": "2027-05-01",
                    "targetDate": "2027-01-01",
                    "durationDays": 30,
                    "transitionReason": "Testing invalid timeline",
                    "objectives": [
                        { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                    ]
                }
                """;
        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badTimelinePayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Case 2: duplicate target metric
        String dupTargetsPayload = """
                {
                    "title": "Duplicate Targets Goal",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Testing duplicate targets",
                    "objectives": [
                        { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                    ],
                    "targets": [
                        { "metricCode": "WEIGHT", "targetValue": 70.0, "unitCode": "KG" },
                        { "metricCode": "WEIGHT", "targetValue": 68.0, "unitCode": "KG" }
                    ]
                }
                """;
        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dupTargetsPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Query: Requesting transition detail with mismatched goalId returns 404")
    void getTransitions_mismatchedGoalId_returns404() throws Exception {
        UUID studentId = createActiveStudentUser("student.mismatch@example.com");
        String token = createAccessToken(studentId, "student.mismatch@example.com", List.of("STUDENT"));
        UUID goal1Id = createActiveGoal(studentId, token, "First Goal");

        // Transition goal 1
        String payload = """
                {
                    "title": "Second Goal",
                    "startDate": "2027-01-01",
                    "targetDate": "2027-03-31",
                    "durationDays": 89,
                    "transitionReason": "Transition from goal 1",
                    "objectives": [
                        { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                    ]
                }
                """;
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/transitions", goal1Id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> respMap = objectMapper.readValue(res.getResponse().getContentAsString(), Map.class);
        UUID transitionId = UUID.fromString((String) respMap.get("id"));

        UUID unrelatedGoalId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/fitness-goals/{goalId}/transitions/{transitionId}", unrelatedGoalId, transitionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("FITNESS_GOAL_NOT_FOUND")));
    }
}
