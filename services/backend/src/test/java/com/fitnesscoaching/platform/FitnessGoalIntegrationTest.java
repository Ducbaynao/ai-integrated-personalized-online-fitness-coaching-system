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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fitnesscoaching.platform.common.exception.GoalLifecycleConflictException;
import com.fitnesscoaching.platform.modules.goal.application.port.out.FitnessGoalPersistencePort;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
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

    @Autowired
    private FitnessGoalPersistencePort fitnessGoalPersistencePort;

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
    @DisplayName("GET /me/current?includePaused=true - returns paused goal after pause and lists in GET /fitness-goals")
    void getCurrentGoal_includePausedAndListGoals() throws Exception {
        UUID studentId = createActiveStudentUser("student.paused.read@example.com");
        String token = createAccessToken(studentId, "student.paused.read@example.com", List.of("STUDENT"));

        // 1. Create and activate goal
        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Journey To Pause",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String goalId = com.jayway.jsonpath.JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

        // 2. Pause goal
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Temporary rest\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PAUSED")));

        // 3. /me/current without param returns 404 (strictly active)
        mockMvc.perform(get("/api/v1/fitness-goals/me/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        // 4. /me/current?includePaused=true returns the PAUSED goal!
        mockMvc.perform(get("/api/v1/fitness-goals/me/current?includePaused=true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(goalId)))
                .andExpect(jsonPath("$.status", is("PAUSED")));

        // 5. GET /api/v1/fitness-goals lists the student's goals
        mockMvc.perform(get("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.items[0].id", is(goalId)))
                .andExpect(jsonPath("$.items[0].status", is("PAUSED")));

        // 6. GET /api/v1/fitness-goals?status=PAUSED filters properly
        mockMvc.perform(get("/api/v1/fitness-goals?status=PAUSED")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)));

        // 7. GET /api/v1/fitness-goals?status=ACTIVE returns 0 items
        mockMvc.perform(get("/api/v1/fitness-goals?status=ACTIVE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));
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

    @Test
    @DisplayName("Validation check: numeric target values zero or negative rejected with 400 VALIDATION_FAILED")
    void createGoal_targetValidations_rejectsZeroAndNegativeValues() throws Exception {
        UUID studentId = createActiveStudentUser("student.tgtneg@example.com");
        String token = createAccessToken(studentId, "student.tgtneg@example.com", List.of("STUDENT"));

        // startValue <= 0
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Zero Start Value Goal",
                                  "startDate": "2026-10-01",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "startValue": 0.0,
                                      "targetValue": 75.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // targetValue <= 0
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Negative Target Value Goal",
                                  "startDate": "2026-10-01",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetValue": -5.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // targetMinValue <= 0
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Zero Target Min Value Goal",
                                  "startDate": "2026-10-01",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetMinValue": 0.0,
                                      "targetMaxValue": 75.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // targetMaxValue <= 0
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Negative Target Max Value Goal",
                                  "startDate": "2026-10-01",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetMinValue": -10.0,
                                      "targetMaxValue": -2.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Pause goal: owner pauses ACTIVE goal successfully")
    void pauseGoal_ownerActiveGoal_transitionsToPausedAndRecordsHistoryAndAudit() throws Exception {
        UUID studentId = createActiveStudentUser("student.pause.success@example.com");
        String token = createAccessToken(studentId, "student.pause.success@example.com", List.of("STUDENT"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Goal To Pause",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2026-12-31",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Recovering from wrist strain"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(goalId.toString())))
                .andExpect(jsonPath("$.status", is("PAUSED")))
                .andExpect(jsonPath("$.statusReason", is("Recovering from wrist strain")))
                .andExpect(jsonPath("$.pausedAt", notNullValue()));

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(dbStatus).isEqualTo("PAUSED");

        String dbReason = jdbcTemplate.queryForObject(
                "SELECT status_reason FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(dbReason).isEqualTo("Recovering from wrist strain");

        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT from_status, to_status, reason, changed_by FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? ORDER BY changed_at ASC",
                goalId);
        assertThat(history).hasSize(2);
        Map<String, Object> pauseEntry = history.get(1);
        assertThat(pauseEntry.get("from_status")).isEqualTo("ACTIVE");
        assertThat(pauseEntry.get("to_status")).isEqualTo("PAUSED");
        assertThat(pauseEntry.get("reason")).isEqualTo("Recovering from wrist strain");
        assertThat(pauseEntry.get("changed_by")).isEqualTo(studentId);

        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "SELECT action, actor_user_id, actor_role FROM fitness.audit_logs WHERE target_id = ? AND action = 'FITNESS_GOAL_PAUSED'",
                goalId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("actor_user_id")).isEqualTo(studentId);
        assertThat(audits.get(0).get("actor_role")).isEqualTo("STUDENT");
    }


    @Test
    @DisplayName("Resume goal: owner resumes PAUSED goal successfully, clearing paused_at and preserving duration")
    void resumeGoal_ownerPausedGoal_transitionsToActiveAndClearsPausedAt() throws Exception {
        UUID studentId = createActiveStudentUser("student.resume.success@example.com");
        String token = createAccessToken(studentId, "student.resume.success@example.com", List.of("STUDENT"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Goal To Resume",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2026-12-31",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        Map<String, Object> initialVersion = jdbcTemplate.queryForMap(
                "SELECT target_date, duration_days, version_number FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", goalId);

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Temporary break"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Ready to resume training"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(goalId.toString())))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.statusReason", is("Ready to resume training")))
                .andExpect(jsonPath("$.pausedAt", nullValue()));

        Map<String, Object> goalRow = jdbcTemplate.queryForMap(
                "SELECT status::text, paused_at, status_reason FROM fitness.fitness_goals WHERE id = ?", goalId);
        assertThat(goalRow.get("status")).isEqualTo("ACTIVE");
        assertThat(goalRow.get("paused_at")).isNull();
        assertThat(goalRow.get("status_reason")).isEqualTo("Ready to resume training");

        Map<String, Object> afterResumeVersion = jdbcTemplate.queryForMap(
                "SELECT target_date, duration_days, version_number FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", goalId);
        assertThat(afterResumeVersion.get("target_date")).isEqualTo(initialVersion.get("target_date"));
        assertThat(afterResumeVersion.get("duration_days")).isEqualTo(initialVersion.get("duration_days"));
        assertThat(afterResumeVersion.get("version_number")).isEqualTo(initialVersion.get("version_number"));

        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT from_status, to_status, reason, changed_by FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? ORDER BY changed_at ASC",
                goalId);
        assertThat(history).hasSize(3);
        Map<String, Object> resumeEntry = history.get(2);
        assertThat(resumeEntry.get("from_status")).isEqualTo("PAUSED");
        assertThat(resumeEntry.get("to_status")).isEqualTo("ACTIVE");
        assertThat(resumeEntry.get("reason")).isEqualTo("Ready to resume training");
        assertThat(resumeEntry.get("changed_by")).isEqualTo(studentId);

        List<Map<String, Object>> resumeAudits = jdbcTemplate.queryForList(
                "SELECT action, actor_user_id, actor_role FROM fitness.audit_logs WHERE target_id = ? AND action = 'FITNESS_GOAL_RESUMED'",
                goalId);
        assertThat(resumeAudits).hasSize(1);
        assertThat(resumeAudits.get(0).get("actor_user_id")).isEqualTo(studentId);
    }


    @Test
    @DisplayName("Lifecycle guard: pause non-ACTIVE goal rejected with 409 GOAL_LIFECYCLE_CONFLICT")
    void pauseGoal_nonActiveGoal_rejectedWith409() throws Exception {
        UUID studentId = createActiveStudentUser("student.pause.invalid@example.com");
        String token = createAccessToken(studentId, "student.pause.invalid@example.com", List.of("STUDENT"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Draft Goal Pause Attempt",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Attempting pause on draft"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_LIFECYCLE_CONFLICT")));
    }

    @Test
    @DisplayName("Lifecycle guard: resume non-PAUSED goal rejected with 409 GOAL_LIFECYCLE_CONFLICT")
    void resumeGoal_nonPausedGoal_rejectedWith409() throws Exception {
        UUID studentId = createActiveStudentUser("student.resume.invalid@example.com");
        String token = createAccessToken(studentId, "student.resume.invalid@example.com", List.of("STUDENT"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Active Goal Resume Attempt",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Attempting resume on active goal"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_LIFECYCLE_CONFLICT")));
    }

    @Test
    @DisplayName("Authority check: non-owner student receives 403 ACCESS_DENIED on pause and resume")
    void pauseAndResume_nonOwnerStudent_rejectedWith403() throws Exception {
        UUID ownerId = createActiveStudentUser("student.owner@example.com");
        String ownerToken = createAccessToken(ownerId, "student.owner@example.com", List.of("STUDENT"));

        UUID otherId = createActiveStudentUser("student.other@example.com");
        String otherToken = createAccessToken(otherId, "student.other@example.com", List.of("STUDENT"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Owner Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Malicious pause attempt" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Owner legitimate pause" }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Malicious resume attempt" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
    }

    @Test
    @DisplayName("Authority check: Trainer cannot pause or resume student goal (403 STUDENT_CAPABILITY_UNAVAILABLE)")
    void pauseAndResume_trainer_rejectedWith403() throws Exception {
        UUID studentId = createActiveStudentUser("student.coached@example.com");
        String studentToken = createAccessToken(studentId, "student.coached@example.com", List.of("STUDENT"));

        UUID trainerId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, 'trainer.authority@example.com', 'hash', 'Trainer Authority', 'ACTIVE'::fitness.account_status, now(), now())
                """, trainerId);
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now() FROM fitness.roles r WHERE r.code = 'TRAINER'
                """, trainerId, trainerId);
        String trainerToken = createAccessToken(trainerId, "trainer.authority@example.com", List.of("TRAINER"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Student Active Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Trainer attempting to pause" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Trainer attempting to resume" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));
    }

    @Test
    @DisplayName("Authority check: Administrator is platform authority, not coaching authority (403 STUDENT_CAPABILITY_UNAVAILABLE)")
    void pauseAndResume_admin_rejectedWith403() throws Exception {
        UUID studentId = createActiveStudentUser("student.underadmin@example.com");
        String studentToken = createAccessToken(studentId, "student.underadmin@example.com", List.of("STUDENT"));

        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, 'admin.authority@example.com', 'hash', 'Platform Admin', 'ACTIVE'::fitness.account_status, now(), now())
                """, adminId);
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now() FROM fitness.roles r WHERE r.code = 'ADMIN'
                """, adminId, adminId);
        String adminToken = createAccessToken(adminId, "admin.authority@example.com", List.of("ADMIN"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Admin Student Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Admin attempting to pause" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));
    }

    @Test
    @DisplayName("Audit rollback: audit failure during pause rolls back entire pause transaction")
    void pauseGoal_auditFailure_rollsBackStatusUpdateAndHistory() throws Exception {
        UUID studentId = createActiveStudentUser("student.pauseaudit@example.com");
        String token = createAccessToken(studentId, "student.pauseaudit@example.com", List.of("STUDENT"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Goal For Pause Audit Rollback",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        doThrow(new RuntimeException("Simulated audit failure during goal pause"))
                .when(auditService).recordAudit(any());

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Pause with failing audit" }
                                """))
                .andExpect(status().isInternalServerError());

        String status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(status).isEqualTo("ACTIVE");

        Integer pausedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? AND to_status = 'PAUSED'",
                Integer.class, goalId);
        assertThat(pausedCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Concurrency: two concurrent pause requests result in at most one success (deterministic 200/409)")
    void concurrentPause_twoSimultaneousRequests_exactlyOneSucceeds() throws Exception {
        UUID studentId = createActiveStudentUser("student.concurrentpause@example.com");
        String token = createAccessToken(studentId, "student.concurrentpause@example.com", List.of("STUDENT"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Concurrent Pause Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

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
                MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "reason": "Concurrent pause request 1" }
                                        """))
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
                MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "reason": "Concurrent pause request 2" }
                                        """))
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

        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(finalStatus).isEqualTo("PAUSED");

        Integer pausedHistoryCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? AND to_status = 'PAUSED'",
                Integer.class, goalId);
        assertThat(pausedHistoryCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrency: two concurrent resume requests result in at most one success (deterministic 200/409)")
    void concurrentResume_twoSimultaneousRequests_exactlyOneSucceeds() throws Exception {
        UUID studentId = createActiveStudentUser("student.concurrentresume@example.com");
        String token = createAccessToken(studentId, "student.concurrentresume@example.com", List.of("STUDENT"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Concurrent Resume Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Initial pause" }
                                """))
                .andExpect(status().isOk());

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
                MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "reason": "Concurrent resume request 1" }
                                        """))
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
                MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "reason": "Concurrent resume request 2" }
                                        """))
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

        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(finalStatus).isEqualTo("ACTIVE");

        Integer activeHistoryCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? AND to_status = 'ACTIVE'",
                Integer.class, goalId);
        assertThat(activeHistoryCount).isEqualTo(2);
    }

    @Test
    @DisplayName("Audit rollback: audit failure during resume rolls back entire resume transaction")
    void resumeGoal_auditFailure_rollsBackEntireGoalResume() throws Exception {
        UUID studentId = createActiveStudentUser("student.resumeaudit@example.com");
        String token = createAccessToken(studentId, "student.resumeaudit@example.com", List.of("STUDENT"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Goal For Resume Audit Rollback",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Initial pause" }
                                """))
                .andExpect(status().isOk());

        doThrow(new RuntimeException("Simulated audit failure during goal resume"))
                .when(auditService).recordAudit(any());

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Failing resume" }
                                """))
                .andExpect(status().isInternalServerError());

        String status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(status).isEqualTo("PAUSED");

        Object pausedAt = jdbcTemplate.queryForObject(
                "SELECT paused_at FROM fitness.fitness_goals WHERE id = ?", Object.class, goalId);
        assertThat(pausedAt).isNotNull();

        Integer activeHistoryCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? AND to_status = 'ACTIVE'",
                Integer.class, goalId);
        assertThat(activeHistoryCount).isEqualTo(1);

        Integer resumeAuditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'FITNESS_GOAL_RESUMED'",
                Integer.class, goalId);
        assertThat(resumeAuditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Authority check: suspended student account cannot pause or resume (403 ACCOUNT_UNAVAILABLE)")
    void pauseAndResume_suspendedStudentAccount_rejectedWith403() throws Exception {
        UUID studentId = createActiveStudentUser("student.suspended@example.com");
        String token = createAccessToken(studentId, "student.suspended@example.com", List.of("STUDENT"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Goal Before Suspension",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        jdbcTemplate.update("UPDATE fitness.users SET status = 'SUSPENDED'::fitness.account_status WHERE id = ?", studentId);

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Attempting pause while suspended" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(dbStatus).isEqualTo("ACTIVE");

        jdbcTemplate.update("UPDATE fitness.users SET status = 'ACTIVE'::fitness.account_status WHERE id = ?", studentId);
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Legitimate pause" }
                                """))
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE fitness.users SET status = 'SUSPENDED'::fitness.account_status WHERE id = ?", studentId);

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Attempting resume while suspended" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));

        String dbStatusPaused = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(dbStatusPaused).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("Authority check: student without profile cannot pause or resume (404 STUDENT_PROFILE_NOT_FOUND)")
    void pauseAndResume_studentWithoutProfile_rejectedWith404() throws Exception {
        UUID studentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, 'student.noprofile@example.com', ?, 'No Profile Student', '+84901234568', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """,
                studentId, passwordEncoder.encode("Password123!"));

        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now()
                FROM fitness.roles r
                WHERE r.code = 'STUDENT'
                """,
                studentId, studentId);

        String token = createAccessToken(studentId, "student.noprofile@example.com", List.of("STUDENT"));
        UUID randomGoalId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/fitness-goals/" + randomGoalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Pause without profile" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_PROFILE_NOT_FOUND")));

        mockMvc.perform(post("/api/v1/fitness-goals/" + randomGoalId + "/resume")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Resume without profile" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_PROFILE_NOT_FOUND")));
    }


    @Test
    @DisplayName("Security check: unauthenticated requests to pause and resume return 401")
    void pauseAndResume_unauthenticated_returns401() throws Exception {
        UUID randomGoalId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/fitness-goals/" + randomGoalId + "/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Unauthenticated pause" }
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/fitness-goals/" + randomGoalId + "/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Unauthenticated resume" }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Invariants check: pause and resume do not modify version, lock details, objectives, targets, or duration_days")
    void pauseAndResume_preservesGoalVersionAndObjectivesAndTargets() throws Exception {
        UUID studentId = createActiveStudentUser("student.preserve@example.com");
        String token = createAccessToken(studentId, "student.preserve@example.com", List.of("STUDENT"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Preserve Invariants Goal",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2026-12-31",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY", "sortOrder": 0, "notes": "Primary muscle" },
                                    { "goalTypeCode": "FAT_LOSS", "priority": "SECONDARY", "sortOrder": 1, "notes": "Secondary fat" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "startValue": 70.0,
                                      "targetValue": 75.0,
                                      "unitCode": "KG",
                                      "targetDate": "2026-12-31",
                                      "notes": "Body weight"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        Map<String, Object> initialVersion = jdbcTemplate.queryForMap(
                "SELECT id, version_number, title, start_date, target_date, duration_days, locked_at, locked_by, lock_reason FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?",
                goalId);
        List<Map<String, Object>> initialObjectives = jdbcTemplate.queryForList(
                "SELECT goal_type_id, priority::text, sort_order, notes FROM fitness.goal_objectives WHERE goal_version_id = ? ORDER BY sort_order ASC",
                initialVersion.get("id"));
        List<Map<String, Object>> initialTargets = jdbcTemplate.queryForList(
                "SELECT metric_definition_id, start_value, target_value, unit_id, target_date, notes FROM fitness.goal_targets WHERE goal_version_id = ?",
                initialVersion.get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Rest week" }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Rest week completed" }
                                """))
                .andExpect(status().isOk());

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(versionCount).isEqualTo(1);

        Map<String, Object> finalVersion = jdbcTemplate.queryForMap(
                "SELECT id, version_number, title, start_date, target_date, duration_days, locked_at, locked_by, lock_reason FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?",
                goalId);
        assertThat(finalVersion.get("id")).isEqualTo(initialVersion.get("id"));
        assertThat(finalVersion.get("version_number")).isEqualTo(initialVersion.get("version_number"));
        assertThat(finalVersion.get("title")).isEqualTo(initialVersion.get("title"));
        assertThat(finalVersion.get("start_date")).isEqualTo(initialVersion.get("start_date"));
        assertThat(finalVersion.get("target_date")).isEqualTo(initialVersion.get("target_date"));
        assertThat(finalVersion.get("duration_days")).isEqualTo(initialVersion.get("duration_days"));
        assertThat(finalVersion.get("locked_at")).isEqualTo(initialVersion.get("locked_at"));
        assertThat(finalVersion.get("locked_by")).isEqualTo(initialVersion.get("locked_by"));
        assertThat(finalVersion.get("lock_reason")).isEqualTo(initialVersion.get("lock_reason"));

        List<Map<String, Object>> finalObjectives = jdbcTemplate.queryForList(
                "SELECT goal_type_id, priority::text, sort_order, notes FROM fitness.goal_objectives WHERE goal_version_id = ? ORDER BY sort_order ASC",
                finalVersion.get("id"));
        assertThat(finalObjectives).isEqualTo(initialObjectives);

        List<Map<String, Object>> finalTargets = jdbcTemplate.queryForList(
                "SELECT metric_definition_id, start_value, target_value, unit_id, target_date, notes FROM fitness.goal_targets WHERE goal_version_id = ?",
                finalVersion.get("id"));
        assertThat(finalTargets).isEqualTo(initialTargets);
    }

    @Test
    @DisplayName("CAS ownership: persistence adapter rejects pause or resume if student_id does not match")
    void casOwnership_adapterRejectsMismatchedStudentId() throws Exception {
        UUID ownerId = createActiveStudentUser("student.casowner@example.com");
        String ownerToken = createAccessToken(ownerId, "student.casowner@example.com", List.of("STUDENT"));
        UUID attackerId = createActiveStudentUser("student.casattacker@example.com");

        MvcResult createResult = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "CAS Test Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(createResult.getResponse().getContentAsString(), Map.class).get("id"));

        assertThatThrownBy(() -> fitnessGoalPersistencePort.pauseGoal(goalId, attackerId, "Malicious pause", Instant.now()))
                .isInstanceOf(GoalLifecycleConflictException.class);

        String status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(status).isEqualTo("ACTIVE");

        fitnessGoalPersistencePort.pauseGoal(goalId, ownerId, "Legit pause", Instant.now());
        String pausedStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(pausedStatus).isEqualTo("PAUSED");

        assertThatThrownBy(() -> fitnessGoalPersistencePort.resumeGoal(goalId, attackerId, "Malicious resume", Instant.now()))
                .isInstanceOf(GoalLifecycleConflictException.class);

        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(finalStatus).isEqualTo("PAUSED");
    }


    @Test
    @DisplayName("Race condition: resume vs activate on different goals for same student enforces uq_student_active_fitness_goal with 409 ACTIVE_FITNESS_GOAL_ALREADY_EXISTS")
    void concurrentResumeAndActivate_differentGoalsSameStudent_uniqueIndexEnforced() throws Exception {
        UUID studentId = createActiveStudentUser("student.raceactive@example.com");
        String token = createAccessToken(studentId, "student.raceactive@example.com", List.of("STUDENT"));

        MvcResult goal1Result = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Goal 1 - To Resume",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goal1Id = UUID.fromString((String) objectMapper.readValue(goal1Result.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goal1Id + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Pause Goal 1" }
                                """))
                .andExpect(status().isOk());

        MvcResult goal2Result = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Goal 2 - To Activate",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [
                                    { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goal2Id = UUID.fromString((String) objectMapper.readValue(goal2Result.getResponse().getContentAsString(), Map.class).get("id"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger status1 = new AtomicInteger(0);
        AtomicInteger status2 = new AtomicInteger(0);
        AtomicReference<String> errorBody1 = new AtomicReference<>();
        AtomicReference<String> errorBody2 = new AtomicReference<>();
        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        executor.submit(() -> {
            try {
                startLatch.await();
                MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/" + goal1Id + "/resume")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "reason": "Concurrent resume Goal 1" }
                                        """))
                        .andReturn();
                status1.set(res.getResponse().getStatus());
                if (res.getResponse().getStatus() != 200) {
                    errorBody1.set(res.getResponse().getContentAsString());
                }
            } catch (Throwable t) {
                error1.set(t);
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/" + goal2Id + "/activate")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn();
                status2.set(res.getResponse().getStatus());
                if (res.getResponse().getStatus() != 200) {
                    errorBody2.set(res.getResponse().getContentAsString());
                }
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

        // Database must have EXACTLY ONE ACTIVE goal for this student
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goals WHERE student_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL",
                Integer.class, studentId);
        assertThat(activeCount).isEqualTo(1);

        String goal1Status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goal1Id);
        String goal2Status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goal2Id);

        if (status1.get() == 200) {
            // Outcome A: Resume Goal 1 won, Activate Goal 2 lost
            assertThat(status2.get()).isEqualTo(409);
            assertThat(errorBody2.get()).contains("ACTIVE_FITNESS_GOAL_ALREADY_EXISTS");

            // Goal 1 (winner): status ACTIVE, status history has PAUSED -> ACTIVE, audit has FITNESS_GOAL_RESUMED
            assertThat(goal1Status).isEqualTo("ACTIVE");
            Integer goal1ResumeHistory = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? AND from_status = 'PAUSED' AND to_status = 'ACTIVE'",
                    Integer.class, goal1Id);
            assertThat(goal1ResumeHistory).isEqualTo(1);

            Integer goal1ResumeAudit = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'FITNESS_GOAL_RESUMED'",
                    Integer.class, goal1Id);
            assertThat(goal1ResumeAudit).isEqualTo(1);

            // Goal 2 (loser): status still DRAFT, no DRAFT -> ACTIVE history, no FITNESS_GOAL_ACTIVATED audit
            assertThat(goal2Status).isEqualTo("DRAFT");
            Integer goal2ActivateHistory = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? AND to_status = 'ACTIVE'",
                    Integer.class, goal2Id);
            assertThat(goal2ActivateHistory).isEqualTo(0);

            Integer goal2ActivateAudit = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'FITNESS_GOAL_ACTIVATED'",
                    Integer.class, goal2Id);
            assertThat(goal2ActivateAudit).isEqualTo(0);
        } else {
            // Outcome B: Activate Goal 2 won, Resume Goal 1 lost
            assertThat(status1.get()).isEqualTo(409);
            assertThat(status2.get()).isEqualTo(200);
            assertThat(errorBody1.get()).contains("ACTIVE_FITNESS_GOAL_ALREADY_EXISTS");

            // Goal 2 (winner): status ACTIVE, status history has DRAFT -> ACTIVE, audit has FITNESS_GOAL_ACTIVATED
            assertThat(goal2Status).isEqualTo("ACTIVE");
            Integer goal2ActivateHistory = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? AND from_status = 'DRAFT' AND to_status = 'ACTIVE'",
                    Integer.class, goal2Id);
            assertThat(goal2ActivateHistory).isEqualTo(1);

            Integer goal2ActivateAudit = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'FITNESS_GOAL_ACTIVATED'",
                    Integer.class, goal2Id);
            assertThat(goal2ActivateAudit).isEqualTo(1);

            // Goal 1 (loser): status still PAUSED, no PAUSED -> ACTIVE history, no FITNESS_GOAL_RESUMED audit
            assertThat(goal1Status).isEqualTo("PAUSED");
            Integer goal1ResumeHistory = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ? AND from_status = 'PAUSED' AND to_status = 'ACTIVE'",
                    Integer.class, goal1Id);
            assertThat(goal1ResumeHistory).isEqualTo(0);

            Integer goal1ResumeAudit = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'FITNESS_GOAL_RESUMED'",
                    Integer.class, goal1Id);
            assertThat(goal1ResumeAudit).isEqualTo(0);
        }

        // Total ACTIVE history rows across both goals must be exactly 2
        Integer totalActiveHistory = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id IN (?, ?) AND to_status = 'ACTIVE'",
                Integer.class, goal1Id, goal2Id);
        assertThat(totalActiveHistory).isEqualTo(2);
    }

    @Test
    @DisplayName("List goals: batch hydration, pagination, ordering, and student isolation")
    void listStudentGoals_batchHydration_paginationOrderingAndIsolation() throws Exception {
        UUID studentAId = createActiveStudentUser("student.batch.a@example.com");
        String tokenA = createAccessToken(studentAId, "student.batch.a@example.com", List.of("STUDENT"));

        UUID studentBId = createActiveStudentUser("student.batch.b@example.com");
        String tokenB = createAccessToken(studentBId, "student.batch.b@example.com", List.of("STUDENT"));

        String createPayloadTemplate = """
                {
                  "title": "%s",
                  "startDate": "2026-10-01",
                  "targetDate": "2026-12-31",
                  "activateImmediately": %s,
                  "objectives": [
                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY", "sortOrder": 0, "notes": "Objective notes" }
                  ],
                  "targets": [
                    { "metricCode": "WEIGHT", "startValue": 70.0, "targetValue": 75.0, "unitCode": "KG", "notes": "Target notes" }
                  ]
                }
                """;

        // Student A - Goal 1 (oldest, DRAFT)
        MvcResult res1 = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(createPayloadTemplate, "Goal 1 Oldest", "false")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goal1Id = UUID.fromString((String) objectMapper.readValue(res1.getResponse().getContentAsString(), Map.class).get("id"));
        jdbcTemplate.update("UPDATE fitness.fitness_goals SET created_at = now() - interval '3 days' WHERE id = ?", goal1Id);

        // Student A - Goal 2 (middle, ACTIVE then PAUSED via API)
        MvcResult res2 = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(createPayloadTemplate, "Goal 2 Paused", "true")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goal2Id = UUID.fromString((String) objectMapper.readValue(res2.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goal2Id + "/pause")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Pausing for scheduled recovery" }
                                """))
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE fitness.fitness_goals SET created_at = now() - interval '2 days' WHERE id = ?", goal2Id);

        // Student A - Goal 3 (newest active)
        MvcResult res3 = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(createPayloadTemplate, "Goal 3 Active", "true")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goal3Id = UUID.fromString((String) objectMapper.readValue(res3.getResponse().getContentAsString(), Map.class).get("id"));
        jdbcTemplate.update("UPDATE fitness.fitness_goals SET created_at = now() - interval '1 day' WHERE id = ?", goal3Id);

        // Student A - Goal 4 (soft-deleted, should never appear)
        MvcResult res4 = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(createPayloadTemplate, "Goal 4 Deleted", "false")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goal4Id = UUID.fromString((String) objectMapper.readValue(res4.getResponse().getContentAsString(), Map.class).get("id"));
        jdbcTemplate.update("UPDATE fitness.fitness_goals SET deleted_at = now() WHERE id = ?", goal4Id);

        // Student B - Goal B (other student, should never appear for Student A)
        MvcResult resB = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(createPayloadTemplate, "Goal B Other Student", "true")))
                .andExpect(status().isCreated())
                .andReturn();

        // 1. Direct persistence adapter test (verifying batch hydration and ordering)
        List<FitnessGoal> goalsA = fitnessGoalPersistencePort.findByStudentId(studentAId, null, 10, 0);
        assertThat(goalsA).hasSize(3);
        assertThat(goalsA.get(0).id()).isEqualTo(goal3Id);
        assertThat(goalsA.get(0).title()).isEqualTo("Goal 3 Active");
        assertThat(goalsA.get(1).id()).isEqualTo(goal2Id);
        assertThat(goalsA.get(1).title()).isEqualTo("Goal 2 Paused");
        assertThat(goalsA.get(2).id()).isEqualTo(goal1Id);
        assertThat(goalsA.get(2).title()).isEqualTo("Goal 1 Oldest");

        // Verify batch hydration of currentVersion, objectives, and targets
        for (FitnessGoal g : goalsA) {
            assertThat(g.currentVersion()).isNotNull();
            assertThat(g.currentVersion().objectives()).isNotEmpty();
            assertThat(g.currentVersion().targets()).isNotEmpty();
            assertThat(g.currentVersion().objectives().get(0).goalTypeCode()).isEqualTo("MUSCLE_GAIN");
            assertThat(g.currentVersion().targets().get(0).metricCode()).isEqualTo("WEIGHT");
        }

        // 2. Status filtering via adapter
        List<FitnessGoal> activeGoalsA = fitnessGoalPersistencePort.findByStudentId(studentAId, GoalStatus.ACTIVE, 10, 0);
        assertThat(activeGoalsA).hasSize(1);
        assertThat(activeGoalsA.get(0).id()).isEqualTo(goal3Id);

        // 3. Pagination via adapter
        List<FitnessGoal> paged1 = fitnessGoalPersistencePort.findByStudentId(studentAId, null, 1, 0);
        assertThat(paged1).hasSize(1);
        assertThat(paged1.get(0).id()).isEqualTo(goal3Id);

        List<FitnessGoal> paged2 = fitnessGoalPersistencePort.findByStudentId(studentAId, null, 1, 1);
        assertThat(paged2).hasSize(1);
        assertThat(paged2.get(0).id()).isEqualTo(goal2Id);

        // 4. Web endpoint test for student A - unfiltered pagination
        mockMvc.perform(get("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(1)))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].id", is(goal3Id.toString())))
                .andExpect(jsonPath("$.items[0].title", is("Goal 3 Active")))
                .andExpect(jsonPath("$.items[0].status", is("ACTIVE")))
                .andExpect(jsonPath("$.items[0].currentVersion.objectives", hasSize(1)))
                .andExpect(jsonPath("$.items[0].currentVersion.targets", hasSize(1)))
                .andExpect(jsonPath("$.items[1].id", is(goal2Id.toString())))
                .andExpect(jsonPath("$.items[1].title", is("Goal 2 Paused")))
                .andExpect(jsonPath("$.items[2].id", is(goal1Id.toString())))
                .andExpect(jsonPath("$.items[2].title", is("Goal 1 Oldest")));

        // 5. Web endpoint test for student A - status filter
        mockMvc.perform(get("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(goal3Id.toString())));

        // 6. Web endpoint test for student B - isolation
        mockMvc.perform(get("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title", is("Goal B Other Student")));
    }

    // ==================== GOAL-07 TEST HARDENING ====================

    @Test
    @DisplayName("Lifecycle guard: activating an already ACTIVE goal is rejected with 409 INVALID_LIFECYCLE_TRANSITION")
    void activateGoal_whenAlreadyActive_rejectedWith409() throws Exception {
        UUID studentId = createActiveStudentUser("student.alreadyactive@example.com");
        String token = createAccessToken(studentId, "student.alreadyactive@example.com", List.of("STUDENT"));

        // Create draft goal
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Initial Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // Activate once -> 200 OK
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"First activation\"}"))
                .andExpect(status().isOk());

        // Re-activate -> 409 INVALID_LIFECYCLE_TRANSITION
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Second activation attempt\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));

        // Status remains ACTIVE, exactly 2 history entries (DRAFT, ACTIVE)
        String status = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(status).isEqualTo("ACTIVE");
        Integer historyCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(historyCount).isEqualTo(2);
    }

    @Test
    @DisplayName("Lifecycle guard: activating a PAUSED goal is rejected with 409 INVALID_LIFECYCLE_TRANSITION")
    void activateGoal_whenPaused_rejectedWith409() throws Exception {
        UUID studentId = createActiveStudentUser("student.actpaused@example.com");
        String token = createAccessToken(studentId, "student.actpaused@example.com", List.of("STUDENT"));

        // Create active goal
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Active Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // Pause goal
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Taking a break\"}"))
                .andExpect(status().isOk());

        // Attempt activate on PAUSED goal -> 409 INVALID_LIFECYCLE_TRANSITION
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Attempting activate instead of resume\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));

        // Status remains PAUSED
        String status = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(status).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("Lifecycle guard: activating a goal in terminal states (COMPLETED, ENDED, ABANDONED, REPLACED) is rejected with 409")
    void activateGoal_whenTerminalState_rejectedWith409() throws Exception {
        UUID studentId = createActiveStudentUser("student.actterminal@example.com");
        String token = createAccessToken(studentId, "student.actterminal@example.com", List.of("STUDENT"));

        List<String> terminalStates = List.of("COMPLETED", "ENDED", "ABANDONED", "REPLACED");
        for (String terminalState : terminalStates) {
            MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "Goal for %s",
                                      "startDate": "2026-10-01",
                                      "activateImmediately": false,
                                      "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                    }
                                    """.formatted(terminalState)))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID goalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

            // Set to terminal state directly in DB
            jdbcTemplate.update("UPDATE fitness.fitness_goals SET status = ?::fitness.lifecycle_status WHERE id = ?", terminalState, goalId);

            mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\": \"Attempting to activate terminal goal\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));

            String persisted = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
            assertThat(persisted).isEqualTo(terminalState);
        }
    }

    @Test
    @DisplayName("Lifecycle guard: double pause on already PAUSED goal is rejected with 409 GOAL_LIFECYCLE_CONFLICT")
    void pauseGoal_whenAlreadyPaused_doublePauseRejectedWith409() throws Exception {
        UUID studentId = createActiveStudentUser("student.doublepause@example.com");
        String token = createAccessToken(studentId, "student.doublepause@example.com", List.of("STUDENT"));

        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Pause Test Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // First pause -> 200 OK
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"First pause\"}"))
                .andExpect(status().isOk());

        // Second pause -> 409 Conflict
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Second pause attempt\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_LIFECYCLE_CONFLICT")));

        // Persisted state intact
        String status = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(status).isEqualTo("PAUSED");
        Integer historyCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ?", Integer.class, goalId);
        assertThat(historyCount).isEqualTo(2); // ACTIVE, PAUSED
    }

    @Test
    @DisplayName("Lifecycle guard: pause on terminal states is rejected with 409 GOAL_LIFECYCLE_CONFLICT")
    void pauseGoal_whenTerminalState_rejectedWith409() throws Exception {
        UUID studentId = createActiveStudentUser("student.pauseterminal@example.com");
        String token = createAccessToken(studentId, "student.pauseterminal@example.com", List.of("STUDENT"));

        for (String terminalState : List.of("COMPLETED", "ENDED", "ABANDONED", "REPLACED")) {
            MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "Goal for Pause %s",
                                      "startDate": "2026-10-01",
                                      "activateImmediately": false,
                                      "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                    }
                                    """.formatted(terminalState)))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID goalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

            jdbcTemplate.update("UPDATE fitness.fitness_goals SET status = ?::fitness.lifecycle_status WHERE id = ?", terminalState, goalId);

            mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\": \"Attempting to pause terminal goal\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode", is("GOAL_LIFECYCLE_CONFLICT")));

            String persisted = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
            assertThat(persisted).isEqualTo(terminalState);
        }
    }

    @Test
    @DisplayName("Lifecycle guard: resume on DRAFT or terminal states is rejected with 409 GOAL_LIFECYCLE_CONFLICT")
    void resumeGoal_whenDraftOrTerminalState_rejectedWith409() throws Exception {
        UUID studentId = createActiveStudentUser("student.resumeterminal@example.com");
        String token = createAccessToken(studentId, "student.resumeterminal@example.com", List.of("STUDENT"));

        // 1. Resume on DRAFT goal
        MvcResult draftRes = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Draft Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID draftGoalId = UUID.fromString((String) objectMapper.readValue(draftRes.getResponse().getContentAsString(), Map.class).get("id"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + draftGoalId + "/resume")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Attempting to resume draft\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_LIFECYCLE_CONFLICT")));

        // 2. Resume on terminal states
        for (String terminalState : List.of("COMPLETED", "ENDED", "ABANDONED", "REPLACED")) {
            MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "Resume Terminal %s",
                                      "startDate": "2026-10-01",
                                      "activateImmediately": false,
                                      "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                    }
                                    """.formatted(terminalState)))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID goalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

            jdbcTemplate.update("UPDATE fitness.fitness_goals SET status = ?::fitness.lifecycle_status WHERE id = ?", terminalState, goalId);

            mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\": \"Attempting to resume terminal goal\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode", is("GOAL_LIFECYCLE_CONFLICT")));

            String persisted = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
            assertThat(persisted).isEqualTo(terminalState);
        }
    }

    @Test
    @DisplayName("Single-active-goal invariant: activateImmediately=true when active goal already exists fails with 409 and leaves no orphan data")
    void createGoal_activateImmediately_whenActiveGoalAlreadyExists_rejectedWith409() throws Exception {
        UUID studentId = createActiveStudentUser("student.singlegap@example.com");
        String token = createAccessToken(studentId, "student.singlegap@example.com", List.of("STUDENT"));

        // Create first active goal
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "First Active Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                }
                                """))
                .andExpect(status().isCreated());

        // Snapshot existing rows before second call
        int initialGoalCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goals WHERE student_id = ?", Integer.class, studentId);
        int initialVersionCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_versions", Integer.class);

        // Attempt second active goal creation
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Second Active Goal Attempt",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": true,
                                  "objectives": [{ "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("ACTIVE_FITNESS_GOAL_ALREADY_EXISTS")));

        // Zero partial write verification
        int finalGoalCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goals WHERE student_id = ?", Integer.class, studentId);
        int finalVersionCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_versions", Integer.class);
        assertThat(finalGoalCount).isEqualTo(initialGoalCount).isEqualTo(1);
        assertThat(finalVersionCount).isEqualTo(initialVersionCount);
    }

    @Test
    @DisplayName("Single-active-goal concurrency: concurrent create with activateImmediately=true for same student allows exactly one to succeed")
    void concurrentCreateGoal_activateImmediately_sameStudent_onlyOneSucceeds() throws Exception {
        UUID studentId = createActiveStudentUser("student.concurrentcreate@example.com");
        String token = createAccessToken(studentId, "student.concurrentcreate@example.com", List.of("STUDENT"));

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Future<MvcResult>> futures = new ArrayList<>();
        List<Throwable> executionErrors = new CopyOnWriteArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        if (!startLatch.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting for start latch");
                        }
                        return mockMvc.perform(post("/api/v1/fitness-goals")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {
                                                  "title": "Concurrent Goal %d",
                                                  "startDate": "2026-10-01",
                                                  "activateImmediately": true,
                                                  "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                                }
                                                """.formatted(index)))
                                .andReturn();
                    } catch (Throwable t) {
                        executionErrors.add(t);
                        throw t;
                    } finally {
                        doneLatch.countDown();
                    }
                }));
            }

            boolean ready = readyLatch.await(5, TimeUnit.SECONDS);
            assertThat(ready).as("All worker threads must be ready within timeout").isTrue();

            startLatch.countDown();

            boolean done = doneLatch.await(10, TimeUnit.SECONDS);
            assertThat(done).as("All worker threads must complete within timeout").isTrue();

            assertThat(executionErrors).as("Worker threads should not encounter unexpected exceptions").isEmpty();

            int successCount = 0;
            int conflictCount = 0;

            for (Future<MvcResult> future : futures) {
                MvcResult res = future.get(1, TimeUnit.SECONDS);
                int statusCode = res.getResponse().getStatus();
                if (statusCode == 201) {
                    successCount++;
                } else if (statusCode == 409) {
                    conflictCount++;
                    Map<String, Object> errorMap = objectMapper.readValue(res.getResponse().getContentAsString(), Map.class);
                    assertThat(errorMap.get("errorCode")).isEqualTo("ACTIVE_FITNESS_GOAL_ALREADY_EXISTS");
                } else {
                    org.junit.jupiter.api.Assertions.fail("Unexpected HTTP status: " + statusCode + ", body: " + res.getResponse().getContentAsString());
                }
            }

            assertThat(successCount).as("Exactly one creation request should succeed").isEqualTo(1);
            assertThat(conflictCount).as("Exactly one creation request should conflict").isEqualTo(1);

            Integer activeGoalsInDb = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.fitness_goals WHERE student_id = ? AND status = 'ACTIVE'",
                    Integer.class, studentId);
            assertThat(activeGoalsInDb).isEqualTo(1);
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("Data invariants: nullable/missing target fields are preserved as null and never coerced to 0.0")
    void createGoal_nullableTargetValues_preservedAsNullAndNeverZero() throws Exception {
        UUID studentId = createActiveStudentUser("student.nulltargets@example.com");
        String token = createAccessToken(studentId, "student.nulltargets@example.com", List.of("STUDENT"));

        String payload = """
                {
                  "title": "Nullable Target Goal",
                  "startDate": "2026-10-01",
                  "activateImmediately": false,
                  "objectives": [
                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                  ],
                  "targets": [
                    {
                      "metricCode": "WEIGHT",
                      "startValue": null,
                      "targetValue": 75.0,
                      "targetMinValue": null,
                      "targetMaxValue": null,
                      "unitCode": "KG",
                      "notes": null
                    }
                  ]
                }
                """;

        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentVersion.targets[0].startValue", nullValue()))
                .andExpect(jsonPath("$.currentVersion.targets[0].targetMinValue", nullValue()))
                .andExpect(jsonPath("$.currentVersion.targets[0].targetMaxValue", nullValue()))
                .andExpect(jsonPath("$.currentVersion.targets[0].notes", nullValue()))
                .andReturn();

        UUID goalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        Map<String, Object> targetRow = jdbcTemplate.queryForMap(
                "SELECT start_value, target_min_value, target_max_value, notes FROM fitness.goal_targets gt " +
                "JOIN fitness.fitness_goal_versions gv ON gt.goal_version_id = gv.id " +
                "WHERE gv.fitness_goal_id = ?", goalId);

        assertThat(targetRow.get("start_value")).isNull();
        assertThat(targetRow.get("target_min_value")).isNull();
        assertThat(targetRow.get("target_max_value")).isNull();
        assertThat(targetRow.get("notes")).isNull();
    }

    @Test
    @DisplayName("Security check: unauthenticated requests to goal endpoints return 401 UNAUTHORIZED")
    void unauthenticatedRequests_goalEndpoints_return401() throws Exception {
        UUID randomGoalId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/fitness-goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/fitness-goals/me/current"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/fitness-goals/" + randomGoalId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/fitness-goals/" + randomGoalId + "/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authority check: Trainer and Admin cannot activate or directly create student fitness goals (403)")
    void activateAndCreateGoal_trainerAndAdmin_rejectedWith403() throws Exception {
        UUID studentId = createActiveStudentUser("student.authguard@example.com");
        String studentToken = createAccessToken(studentId, "student.authguard@example.com", List.of("STUDENT"));

        // Create draft goal as student
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Draft for Auth Guard",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        UUID trainerId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at) " +
                "VALUES (?, 'coach.guard@example.com', 'hash', 'Coach Guard', '+84909000111', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())", trainerId);
        jdbcTemplate.update("INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at) SELECT ?, r.id, ?, now() FROM fitness.roles r WHERE r.code = 'TRAINER'", trainerId, trainerId);
        String trainerToken = createAccessToken(trainerId, "coach.guard@example.com", List.of("TRAINER"));

        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at) " +
                "VALUES (?, 'admin.guard@example.com', 'hash', 'Admin Guard', '+84909000222', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())", adminId);
        jdbcTemplate.update("INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at) SELECT ?, r.id, ?, now() FROM fitness.roles r WHERE r.code = 'ADMIN'", adminId, adminId);
        String adminToken = createAccessToken(adminId, "admin.guard@example.com", List.of("ADMIN"));

        // Trainer cannot activate goal
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Trainer trying to activate\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));

        // Admin cannot activate goal
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Admin trying to activate\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));

        // Trainer cannot create goal
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Trainer Direct Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));

        // Admin cannot create goal
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Admin Direct Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));
    }

    @Test
    @DisplayName("Replay safety: Duplicate lifecycle mutation requests (activate, pause, resume) result in 409 conflict with zero duplicate history or audit entries")
    void duplicateLifecycleReplaySafety_resultsInConflictAndNoDuplicateHistory() throws Exception {
        UUID studentId = createActiveStudentUser("student.replay@example.com");
        String token = createAccessToken(studentId, "student.replay@example.com", List.of("STUDENT"));

        // Create draft goal
        MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Replay Test Goal",
                                  "startDate": "2026-10-01",
                                  "activateImmediately": false,
                                  "objectives": [{ "goalTypeCode": "STRENGTH", "priority": "PRIMARY" }]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goalId = UUID.fromString((String) objectMapper.readValue(res.getResponse().getContentAsString(), Map.class).get("id"));

        // 1. Activate
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Activation 1\"}"))
                .andExpect(status().isOk());

        int historyAfterAct = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ?", Integer.class, goalId);
        int auditAfterAct = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE target_id = ?", Integer.class, goalId);

        // Replay Activate -> 409 Conflict, counts unchanged
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Activation 1 replay\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ?", Integer.class, goalId)).isEqualTo(historyAfterAct);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE target_id = ?", Integer.class, goalId)).isEqualTo(auditAfterAct);

        // 2. Pause
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Pause 1\"}"))
                .andExpect(status().isOk());

        int historyAfterPause = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ?", Integer.class, goalId);
        int auditAfterPause = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE target_id = ?", Integer.class, goalId);

        // Replay Pause -> 409 Conflict, counts unchanged
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Pause 1 replay\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_LIFECYCLE_CONFLICT")));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ?", Integer.class, goalId)).isEqualTo(historyAfterPause);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE target_id = ?", Integer.class, goalId)).isEqualTo(auditAfterPause);

        // 3. Resume
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Resume 1\"}"))
                .andExpect(status().isOk());

        int historyAfterResume = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ?", Integer.class, goalId);
        int auditAfterResume = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE target_id = ?", Integer.class, goalId);

        // Replay Resume -> 409 Conflict, counts unchanged
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Resume 1 replay\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_LIFECYCLE_CONFLICT")));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.fitness_goal_status_history WHERE fitness_goal_id = ?", Integer.class, goalId)).isEqualTo(historyAfterResume);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE target_id = ?", Integer.class, goalId)).isEqualTo(auditAfterResume);
    }
}
