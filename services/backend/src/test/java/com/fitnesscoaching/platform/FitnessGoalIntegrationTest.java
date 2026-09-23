package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FitnessGoalIntegrationTest {

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

        jdbcTemplate.execute("TRUNCATE TABLE fitness.audit_logs, fitness.fitness_goal_status_history, " +
                "fitness.goal_targets, fitness.goal_objectives, fitness.fitness_goal_versions, " +
                "fitness.fitness_goals, fitness.student_profiles, fitness.user_roles, fitness.users CASCADE");

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

    @Test
    @DisplayName("Create goal: successfully creates DRAFT goal with version, objectives, and targets")
    void createGoal_draft_persistsVersionAndObjectivesAndTargets() throws Exception {
        UUID studentId = createActiveStudentUser("student.draft@example.com");
        String token = createAccessToken(studentId, "student.draft@example.com", List.of("STUDENT"));

        String payload = """
                {
                  "title": "Hypertrophy Program Q4",
                  "startDate": "2026-10-01",
                  "targetDate": "2026-12-31",
                  "activateImmediately": false,
                  "objectives": [
                    {
                      "goalTypeCode": "MUSCLE_GAIN",
                      "priority": "PRIMARY",
                      "sortOrder": 0,
                      "notes": "Main hypertrophy focus"
                    },
                    {
                      "goalTypeCode": "FAT_LOSS",
                      "priority": "SECONDARY",
                      "sortOrder": 1,
                      "notes": "Secondary fat control"
                    }
                  ],
                  "targets": [
                    {
                      "metricCode": "WEIGHT",
                      "startValue": 70.0,
                      "targetValue": 75.0,
                      "targetMinValue": 74.0,
                      "targetMaxValue": 76.0,
                      "unitCode": "KG",
                      "targetDate": "2026-12-31",
                      "notes": "Body weight target"
                    }
                  ]
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.studentId", is(studentId.toString())))
                .andExpect(jsonPath("$.title", is("Hypertrophy Program Q4")))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andExpect(jsonPath("$.activatedAt", nullValue()))
                .andExpect(jsonPath("$.currentVersion.versionNumber", is(1)))
                .andExpect(jsonPath("$.currentVersion.lockedAt", nullValue()))
                .andExpect(jsonPath("$.currentVersion.objectives", hasSize(2)))
                .andExpect(jsonPath("$.currentVersion.objectives[0].goalTypeCode", is("MUSCLE_GAIN")))
                .andExpect(jsonPath("$.currentVersion.objectives[0].priority", is("PRIMARY")))
                .andExpect(jsonPath("$.currentVersion.objectives[1].goalTypeCode", is("FAT_LOSS")))
                .andExpect(jsonPath("$.currentVersion.objectives[1].priority", is("SECONDARY")))
                .andExpect(jsonPath("$.currentVersion.targets", hasSize(1)))
                .andExpect(jsonPath("$.currentVersion.targets[0].metricCode", is("WEIGHT")))
                .andExpect(jsonPath("$.currentVersion.targets[0].unitCode", is("KG")))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> respMap = objectMapper.readValue(responseBody, Map.class);
        UUID goalId = UUID.fromString((String) respMap.get("id"));

        // Verify database state
        String goalStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(goalStatus).isEqualTo("DRAFT");

        String lockReason = jdbcTemplate.queryForObject(
                "SELECT lock_reason::text FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", String.class, goalId);
        assertThat(lockReason).isNull();

        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(historyCount).isEqualTo(1);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'FITNESS_GOAL_CREATED'", Integer.class, goalId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Activate goal: transitions DRAFT to ACTIVE, locks version, writes history and audit")
    void activateGoal_draftToActive_locksVersionAndRecordsHistoryAndAudit() throws Exception {
        UUID studentId = createActiveStudentUser("student.activate@example.com");
        String token = createAccessToken(studentId, "student.activate@example.com", List.of("STUDENT"));

        // Create draft goal
        String createPayload = """
                {
                  "title": "Strength Builder",
                  "startDate": "2026-10-01",
                  "activateImmediately": false,
                  "objectives": [
                    { "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }
                  ],
                  "targets": []
                }
                """;
        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> createMap = objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class);
        UUID goalId = UUID.fromString((String) createMap.get("id"));

        // Activate goal
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Starting the program today\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(goalId.toString())))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.activatedAt", notNullValue()))
                .andExpect(jsonPath("$.currentVersion.lockedAt", notNullValue()))
                .andExpect(jsonPath("$.currentVersion.lockReason", is("ACTIVATED")));

        // Verify in DB
        String goalStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(goalStatus).isEqualTo("ACTIVE");

        String lockReason = jdbcTemplate.queryForObject(
                "SELECT lock_reason::text FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", String.class, goalId);
        assertThat(lockReason).isEqualTo("ACTIVATED");

        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(historyCount).isEqualTo(2); // DRAFT, then ACTIVE

        Integer activatedAudit = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'FITNESS_GOAL_ACTIVATED'", Integer.class, goalId);
        assertThat(activatedAudit).isEqualTo(1);
    }

    @Test
    @DisplayName("Create goal: activateImmediately=true creates active goal and satisfies all DB triggers")
    void createGoal_activateImmediately_locksVersionAndSetsActiveDirectly() throws Exception {
        UUID studentId = createActiveStudentUser("student.direct@example.com");
        String token = createAccessToken(studentId, "student.direct@example.com", List.of("STUDENT"));

        String payload = """
                {
                  "title": "Immediate Muscle Gain",
                  "startDate": "2026-10-01",
                  "targetDate": "2026-12-31",
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
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.activatedAt", notNullValue()))
                .andExpect(jsonPath("$.currentVersion.lockedAt", notNullValue()))
                .andExpect(jsonPath("$.currentVersion.lockReason", is("ACTIVATED")))
                .andReturn();

        Map<String, Object> respMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        UUID goalId = UUID.fromString((String) respMap.get("id"));

        String goalStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(goalStatus).isEqualTo("ACTIVE");

        String lockReason = jdbcTemplate.queryForObject(
                "SELECT lock_reason::text FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", String.class, goalId);
        assertThat(lockReason).isEqualTo("ACTIVATED");
    }

    @Test
    @DisplayName("Active goal uniqueness: student cannot have two concurrent ACTIVE goals")
    void activeGoalUniqueness_concurrentOrSecondActiveGoal_rejectedWith409() throws Exception {
        UUID studentId = createActiveStudentUser("student.unique@example.com");
        String token = createAccessToken(studentId, "student.unique@example.com", List.of("STUDENT"));

        // Create first active goal
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "First Active Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());

        // Attempt to create second active goal -> 409
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Second Active Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("ACTIVE_FITNESS_GOAL_ALREADY_EXISTS")));

        // Create a draft goal -> succeeds
        MvcResult draftResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Draft Waiting Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [
                                    { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> draftMap = objectMapper.readValue(draftResult.getResponse().getContentAsString(), Map.class);
        UUID draftGoalId = UUID.fromString((String) draftMap.get("id"));

        // Attempt to activate draft goal while active goal exists -> 409
        mockMvc.perform(post("/api/v1/fitness-goals/" + draftGoalId + "/activate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("ACTIVE_FITNESS_GOAL_ALREADY_EXISTS")));
    }

    @Test
    @DisplayName("GET /me/current - returns active goal when present, 404 when absent")
    void getCurrentActiveGoal_returnsActiveGoalOr404() throws Exception {
        UUID studentId = createActiveStudentUser("student.current@example.com");
        String token = createAccessToken(studentId, "student.current@example.com", List.of("STUDENT"));

        // When no active goal exists
        mockMvc.perform(get("/api/v1/fitness-goals/me/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("FITNESS_GOAL_NOT_FOUND")));

        // Create active goal
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Active Goal Now",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());

        // Now returns the active goal
        mockMvc.perform(get("/api/v1/fitness-goals/me/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Active Goal Now")))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("Ownership check: student B cannot view or activate student A's goal")
    void ownershipCheck_crossStudentAccess_forbidden() throws Exception {
        UUID studentA = createActiveStudentUser("student.a@example.com");
        UUID studentB = createActiveStudentUser("student.b@example.com");

        String tokenA = createAccessToken(studentA, "student.a@example.com", List.of("STUDENT"));
        String tokenB = createAccessToken(studentB, "student.b@example.com", List.of("STUDENT"));

        MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Student A Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> goalMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        UUID goalAId = UUID.fromString((String) goalMap.get("id"));

        // Student B tries to get detail of Student A's goal -> 403
        mockMvc.perform(get("/api/v1/fitness-goals/" + goalAId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));

        // Student B tries to activate Student A's goal -> 403
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalAId + "/activate")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
    }

    @Test
    @DisplayName("Authority check: user without STUDENT role is rejected with 403 STUDENT_CAPABILITY_UNAVAILABLE")
    void nonStudentRole_rejected() throws Exception {
        UUID trainerUserId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, 'trainer.only@example.com', 'hash', 'Trainer User', 'ACTIVE'::fitness.account_status, now(), now())
                """, trainerUserId);

        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now()
                FROM fitness.roles r
                WHERE r.code = 'TRAINER'
                """, trainerUserId, trainerUserId);

        String token = createAccessToken(trainerUserId, "trainer.only@example.com", List.of("TRAINER"));

        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Trainer Goal Attempt",
                                  "startDate": "2026-10-01",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));
    }

    @Test
    @DisplayName("Validation check: unit dimension mismatch rejected with 400 VALIDATION_FAILED")
    void unitDimensionMismatch_rejected() throws Exception {
        UUID studentId = createActiveStudentUser("student.unit@example.com");
        String token = createAccessToken(studentId, "student.unit@example.com", List.of("STUDENT"));

        // Metric WEIGHT has dimension MASS. CM has dimension LENGTH.
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Mismatched Unit Goal",
                                  "startDate": "2026-10-01",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetValue": 75.0,
                                      "unitCode": "CM"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Validation check: duplicate target metric rejected with 400 VALIDATION_FAILED")
    void duplicateTargetMetric_rejected() throws Exception {
        UUID studentId = createActiveStudentUser("student.dup@example.com");
        String token = createAccessToken(studentId, "student.dup@example.com", List.of("STUDENT"));

        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Duplicate Target Metric Goal",
                                  "startDate": "2026-10-01",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetValue": 75.0,
                                      "unitCode": "KG"
                                    },
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetValue": 80.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Concurrency: concurrent activation of the same goal permits only one 200 OK, the other gets 409 Conflict")
    void concurrentActivation_sameGoal_onlyOneSucceeds() throws Exception {
        UUID studentId = createActiveStudentUser("student.conc.same@example.com");
        String token = createAccessToken(studentId, "student.conc.same@example.com", List.of("STUDENT"));

        // Create a DRAFT goal
        MvcResult draftResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Concurrent Same Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> draftMap = objectMapper.readValue(draftResult.getResponse().getContentAsString(), Map.class);
        UUID goalId = UUID.fromString((String) draftMap.get("id"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger status1 = new AtomicInteger(0);
        AtomicInteger status2 = new AtomicInteger(0);
        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        executor.submit(() -> {
            try {
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn();
                status1.set(result.getResponse().getStatus());
            } catch (Throwable t) {
                error1.set(t);
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn();
                status2.set(result.getResponse().getStatus());
            } catch (Throwable t) {
                error2.set(t);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(error1.get()).isNull();
        assertThat(error2.get()).isNull();

        List<Integer> statuses = List.of(status1.get(), status2.get());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // Verify database state: status is ACTIVE
        String goalStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(goalStatus).isEqualTo("ACTIVE");

        // Exactly one activation history row
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? AND to_status = 'ACTIVE'",
                Integer.class, goalId);
        assertThat(historyCount).isEqualTo(1);

        // Exactly one activation audit log
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'FITNESS_GOAL_ACTIVATED'",
                Integer.class, goalId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrency: concurrent activation of different goals for the same student results in exactly one ACTIVE goal")
    void concurrentActivation_differentGoals_sameStudent_onlyOneBecomesActive() throws Exception {
        UUID studentId = createActiveStudentUser("student.conc.diff@example.com");
        String token = createAccessToken(studentId, "student.conc.diff@example.com", List.of("STUDENT"));

        // Create two DRAFT goals
        MvcResult draftResult1 = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Concurrent Goal 1",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goal1Id = UUID.fromString((String) objectMapper.readValue(draftResult1.getResponse().getContentAsString(), Map.class).get("id"));

        MvcResult draftResult2 = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Concurrent Goal 2",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [
                                    { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goal2Id = UUID.fromString((String) objectMapper.readValue(draftResult2.getResponse().getContentAsString(), Map.class).get("id"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger status1 = new AtomicInteger(0);
        AtomicInteger status2 = new AtomicInteger(0);
        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        executor.submit(() -> {
            try {
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals/" + goal1Id + "/activate")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn();
                status1.set(result.getResponse().getStatus());
            } catch (Throwable t) {
                error1.set(t);
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals/" + goal2Id + "/activate")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn();
                status2.set(result.getResponse().getStatus());
            } catch (Throwable t) {
                error2.set(t);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(error1.get()).isNull();
        assertThat(error2.get()).isNull();

        List<Integer> statuses = List.of(status1.get(), status2.get());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // Verify in DB that exactly one goal is ACTIVE for this student
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goals WHERE student_id = ? AND status = 'ACTIVE'::fitness.lifecycle_status AND deleted_at IS NULL",
                Integer.class, studentId);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Timeline validation: same-day timeline (targetDate == startDate) rejected with 400 VALIDATION_FAILED")
    void createGoal_sameDayTimeline_rejectedWith400ValidationFailed() throws Exception {
        UUID studentId = createActiveStudentUser("student.sameday@example.com");
        String token = createAccessToken(studentId, "student.sameday@example.com", List.of("STUDENT"));

        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Same Day Goal",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2026-10-01",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("targetDate")))
                .andExpect(jsonPath("$.fieldErrors[0].code", is("InvalidRange")));
    }

    @Test
    @DisplayName("Timeline validation: mismatched targetDate and durationDays rejected with 400 VALIDATION_FAILED")
    void createGoal_mismatchedTimeline_rejectedWith400ValidationFailed() throws Exception {
        UUID studentId = createActiveStudentUser("student.mismatch@example.com");
        String token = createAccessToken(studentId, "student.mismatch@example.com", List.of("STUDENT"));

        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Mismatched Timeline Goal",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2026-10-31",
                                  "durationDays": 60,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("durationDays")))
                .andExpect(jsonPath("$.fieldErrors[0].code", is("Mismatch")));
    }

    @Test
    @DisplayName("Timeline validation: matching targetDate and durationDays persists successfully")
    void createGoal_matchingTimeline_persistsSuccessfully() throws Exception {
        UUID studentId = createActiveStudentUser("student.matching@example.com");
        String token = createAccessToken(studentId, "student.matching@example.com", List.of("STUDENT"));

        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Matching Timeline Goal",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2026-10-31",
                                  "durationDays": 30,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentVersion.startDate", is("2026-10-01")))
                .andExpect(jsonPath("$.currentVersion.targetDate", is("2026-10-31")))
                .andExpect(jsonPath("$.currentVersion.durationDays", is(30)));
    }

    @Test
    @DisplayName("Audit rollback: audit failure during goal creation rolls back entire goal aggregate")
    void createGoal_auditFailure_rollsBackEntireGoalCreation() throws Exception {
        UUID studentId = createActiveStudentUser("student.auditcreate@example.com");
        String token = createAccessToken(studentId, "student.auditcreate@example.com", List.of("STUDENT"));

        doThrow(new RuntimeException("Simulated audit failure during goal creation"))
                .when(auditService).recordAudit(any());

        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Audit Fail Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetValue": 70.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isInternalServerError());

        // Verify database has 0 rows across all goal tables
        Integer goalsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goals", Integer.class);
        Integer versionsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_versions", Integer.class);
        Integer objectivesCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.goal_objectives", Integer.class);
        Integer targetsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.goal_targets", Integer.class);
        Integer historyCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_status_history", Integer.class);

        assertThat(goalsCount).isEqualTo(0);
        assertThat(versionsCount).isEqualTo(0);
        assertThat(objectivesCount).isEqualTo(0);
        assertThat(targetsCount).isEqualTo(0);
        assertThat(historyCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Audit rollback: audit failure during goal activation rolls back activation")
    void activateGoal_auditFailure_rollsBackEntireGoalActivation() throws Exception {
        UUID studentId = createActiveStudentUser("student.auditact@example.com");
        String token = createAccessToken(studentId, "student.auditact@example.com", List.of("STUDENT"));

        // Create draft goal successfully (audit works)
        MvcResult draftResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Draft Before Audit Fail",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(draftResult.getResponse().getContentAsString(), Map.class).get("id"));

        // Now spy auditService to fail
        doThrow(new RuntimeException("Simulated audit failure during goal activation"))
                .when(auditService).recordAudit(any());

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError());

        // Verify goal status is still DRAFT
        String status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(status).isEqualTo("DRAFT");

        // Verify activated_at is null
        Object activatedAt = jdbcTemplate.queryForObject(
                "SELECT activated_at FROM fitness.fitness_goals WHERE id = ?", Object.class, goalId);
        assertThat(activatedAt).isNull();

        // Verify version is still unlocked
        Object lockedAt = jdbcTemplate.queryForObject(
                "SELECT locked_at FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", Object.class, goalId);
        assertThat(lockedAt).isNull();

        // Verify no activation history, only initial draft history remains
        Integer activeHistoryCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? AND to_status = 'ACTIVE'", Integer.class, goalId);
        assertThat(activeHistoryCount).isEqualTo(0);

        Integer totalHistoryCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(totalHistoryCount).isEqualTo(1);
    }
}
