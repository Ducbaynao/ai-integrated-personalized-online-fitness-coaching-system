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
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FitnessGoalVersionIntegrationTest {

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
                INSERT INTO fitness.metric_definitions (code, display_name, description, value_type, collection_type, default_unit_id, valid_min, valid_max, is_active)
                SELECT 'INACTIVE_METRIC', 'Inactive Metric', 'Inactive', 'NUMERIC'::fitness.measurement_value_type, 'DIRECT'::fitness.metric_collection_type, u.id, 1.0, 100.0, false
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
                INSERT INTO fitness.trainer_profiles (user_id, bio, years_experience, verification_status, verified_at, is_accepting_students, is_active, created_at, updated_at)
                VALUES (?, 'Expert Coach', 5.0, 'VERIFIED'::fitness.trainer_verification_state, now(), true, true, now(), now())
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

    private UUID createDraftGoal(UUID studentId, String token, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2026-12-31",
                                  "durationDays": 91,
                                  "activateImmediately": false,
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """.formatted(title)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString((String) objectMapper.readValue(result.getResponse().getContentAsString(), Map.class).get("id"));
    }

    @Test
    @DisplayName("Create goal version: successfully creates same-journey version on ACTIVE goal")
    void createGoalVersion_activeGoal_createsNewVersionAndClosesPrevious() throws Exception {
        UUID studentId = createActiveStudentUser("student.version@example.com");
        String token = createAccessToken(studentId, "student.version@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Initial Muscle Gain");

        // Create version 2 extending duration
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Extended Muscle Gain",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Extending journey for greater hypertrophy",
                                  "changeSummary": "Increased timeline from 91 to 122 days",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY", "sortOrder": 0 }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetValue": 78.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.versionNumber", is(2)))
                .andExpect(jsonPath("$.title", is("Extended Muscle Gain")))
                .andExpect(jsonPath("$.startDate", is("2026-10-01")))
                .andExpect(jsonPath("$.targetDate", is("2027-01-31")))
                .andExpect(jsonPath("$.durationDays", is(122)))
                .andExpect(jsonPath("$.isCurrent", is(true)))
                .andExpect(jsonPath("$.effectiveUntil", nullValue()))
                .andExpect(jsonPath("$.lockReason", is("APPROVED")))
                .andExpect(jsonPath("$.lockedBy", is(studentId.toString())))
                .andExpect(jsonPath("$.changeReason", is("Extending journey for greater hypertrophy")))
                .andExpect(jsonPath("$.changeSummary", is("Increased timeline from 91 to 122 days")))
                .andExpect(jsonPath("$.objectives", hasSize(1)))
                .andExpect(jsonPath("$.objectives[0].goalTypeCode", is("MUSCLE_GAIN")))
                .andExpect(jsonPath("$.objectives[0].priority", is("PRIMARY")))
                .andExpect(jsonPath("$.targets", hasSize(1)))
                .andExpect(jsonPath("$.targets[0].metricCode", is("WEIGHT")))
                .andExpect(jsonPath("$.targets[0].targetValue", is(78.0)));

        // Verify goal title was updated in database
        String updatedTitle = jdbcTemplate.queryForObject(
                "SELECT title FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(updatedTitle).isEqualTo("Extended Muscle Gain");

        // Verify version 1 has effective_until set (closed)
        Instant v1EffectiveUntil = jdbcTemplate.queryForObject(
                "SELECT effective_until FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 1",
                Instant.class, goalId);
        assertThat(v1EffectiveUntil).isNotNull();

        // Verify version 2 has effective_until null (open/current)
        Instant v2EffectiveUntil = jdbcTemplate.queryForObject(
                "SELECT effective_until FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 2",
                Instant.class, goalId);
        assertThat(v2EffectiveUntil).isNull();

        // Verify audit log recorded
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE action = 'FITNESS_GOAL_VERSION_CREATED' AND actor_user_id = ?",
                Integer.class, studentId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Get versions list: returns versions ordered descending with correct isCurrent flag")
    void getGoalVersions_returnsOrderedListWithPagination() throws Exception {
        UUID studentId = createActiveStudentUser("student.versionslist@example.com");
        String token = createAccessToken(studentId, "student.versionslist@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Journey V1");

        // Create V2
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Journey V2",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Revision to V2",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());

        // Create V3
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Journey V3",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-02-28",
                                  "durationDays": 150,
                                  "changeReason": "Revision to V3",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());

        // Get versions list
        mockMvc.perform(get("/api/v1/fitness-goals/" + goalId + "/versions?page=0&size=10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(1)))
                .andExpect(jsonPath("$.items", hasSize(3)))
                // First item is V3 (current)
                .andExpect(jsonPath("$.items[0].versionNumber", is(3)))
                .andExpect(jsonPath("$.items[0].title", is("Journey V3")))
                .andExpect(jsonPath("$.items[0].isCurrent", is(true)))
                .andExpect(jsonPath("$.items[0].effectiveUntil", nullValue()))
                .andExpect(jsonPath("$.items[0].objectives", hasSize(1)))
                // Second item is V2 (historical)
                .andExpect(jsonPath("$.items[1].versionNumber", is(2)))
                .andExpect(jsonPath("$.items[1].title", is("Journey V2")))
                .andExpect(jsonPath("$.items[1].isCurrent", is(false)))
                .andExpect(jsonPath("$.items[1].effectiveUntil", notNullValue()))
                // Third item is V1 (historical)
                .andExpect(jsonPath("$.items[2].versionNumber", is(1)))
                .andExpect(jsonPath("$.items[2].title", is("Journey V1")))
                .andExpect(jsonPath("$.items[2].isCurrent", is(false)))
                .andExpect(jsonPath("$.items[2].effectiveUntil", notNullValue()));
    }

    @Test
    @DisplayName("Get version detail: returns specific version detail and enforces cross-goal isolation")
    void getGoalVersionDetail_successAndCrossGoalIsolation() throws Exception {
        UUID studentId = createActiveStudentUser("student.detail@example.com");
        String token = createAccessToken(studentId, "student.detail@example.com", List.of("STUDENT"));
        UUID goal1Id = createActiveGoal(studentId, token, "Goal 1");

        // Create V2 on Goal 1
        MvcResult v2Result = mockMvc.perform(post("/api/v1/fitness-goals/" + goal1Id + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Goal 1 V2",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Revision 2",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetValue": 80.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID v2Id = UUID.fromString((String) objectMapper.readValue(v2Result.getResponse().getContentAsString(), Map.class).get("id"));

        // Get V2 detail on Goal 1 -> 200 OK
        mockMvc.perform(get("/api/v1/fitness-goals/" + goal1Id + "/versions/" + v2Id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(v2Id.toString())))
                .andExpect(jsonPath("$.versionNumber", is(2)))
                .andExpect(jsonPath("$.title", is("Goal 1 V2")))
                .andExpect(jsonPath("$.isCurrent", is(true)))
                .andExpect(jsonPath("$.objectives", hasSize(1)))
                .andExpect(jsonPath("$.targets", hasSize(1)));

        // Create a separate goal (DRAFT)
        UUID goal2Id = createDraftGoal(studentId, token, "Goal 2");

        // Attempt to query v2Id using goal2Id -> 404 GOAL_VERSION_NOT_FOUND
        mockMvc.perform(get("/api/v1/fitness-goals/" + goal2Id + "/versions/" + v2Id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("GOAL_VERSION_NOT_FOUND")));
    }

    @Test
    @DisplayName("Authorization: non-owner student cannot view or create versions (403 ACCESS_DENIED)")
    void authorization_nonOwnerStudent_forbidden() throws Exception {
        UUID ownerId = createActiveStudentUser("student.owner@example.com");
        UUID otherStudentId = createActiveStudentUser("student.other@example.com");
        String ownerToken = createAccessToken(ownerId, "student.owner@example.com", List.of("STUDENT"));
        String otherToken = createAccessToken(otherStudentId, "student.other@example.com", List.of("STUDENT"));

        UUID goalId = createActiveGoal(ownerId, ownerToken, "Owner Goal");

        // Other student attempts to list versions -> 403
        mockMvc.perform(get("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));

        // Other student attempts to create version -> 403
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Unauthorized Version",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Hack",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
    }

    @Test
    @DisplayName("Authorization: Trainer cannot create version directly via POST endpoint (403 FORBIDDEN)")
    void authorization_trainerCannotDirectlyCreateVersion() throws Exception {
        UUID studentId = createActiveStudentUser("student.auth@example.com");
        UUID trainerId = createActiveTrainerUser("trainer.auth@example.com");
        String studentToken = createAccessToken(studentId, "student.auth@example.com", List.of("STUDENT"));
        String trainerToken = createAccessToken(trainerId, "trainer.auth@example.com", List.of("TRAINER"));

        UUID goalId = createActiveGoal(studentId, studentToken, "Student Goal");

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Trainer Direct Version",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Trainer direct update",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Student authority: inactive student account cannot create version even with valid JWT")
    void studentAuthority_suspendedStudent_returnsForbidden() throws Exception {
        UUID studentId = createActiveStudentUser("student.suspended@example.com");
        String token = createAccessToken(studentId, "student.suspended@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Active Goal");

        // Suspend the student in DB
        jdbcTemplate.update("UPDATE fitness.users SET status = 'SUSPENDED'::fitness.account_status WHERE id = ?", studentId);

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Suspended Student Version",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Suspended attempt",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }

    @Test
    @DisplayName("Lifecycle validation: cannot create version on non-ACTIVE goal (409 INVALID_LIFECYCLE_TRANSITION)")
    void createGoalVersion_draftGoal_returns409() throws Exception {
        UUID studentId = createActiveStudentUser("student.draftversion@example.com");
        String token = createAccessToken(studentId, "student.draftversion@example.com", List.of("STUDENT"));
        UUID draftGoalId = createDraftGoal(studentId, token, "Draft Goal");

        mockMvc.perform(post("/api/v1/fitness-goals/" + draftGoalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Version on Draft",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Attempt on draft",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));
    }

    @Test
    @DisplayName("Validation: inactive goal type or inactive metric definition returns 400 VALIDATION_FAILED")
    void createGoalVersion_inactiveCatalogItems_returns400() throws Exception {
        UUID studentId = createActiveStudentUser("student.valcat@example.com");
        String token = createAccessToken(studentId, "student.valcat@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Valid Goal");

        // Inactive goal type
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Version Inactive Type",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Testing inactive type",
                                  "objectives": [
                                    { "goalTypeCode": "INACTIVE_GOAL_TYPE", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Inactive metric definition
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Version Inactive Metric",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Testing inactive metric",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "INACTIVE_METRIC",
                                      "targetValue": 50.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Validation: multiple PRIMARY objectives return 400 VALIDATION_FAILED")
    void createGoalVersion_multiplePrimaryObjectives_returns400() throws Exception {
        UUID studentId = createActiveStudentUser("student.multpri@example.com");
        String token = createAccessToken(studentId, "student.multpri@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Valid Goal");

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Multiple Primary",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Multiple primary",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" },
                                    { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Concurrency: concurrent version creation on same active goal leads to exactly 1 success and 1 409 CONFLICT")
    void createGoalVersion_concurrentRequests_oneWinsAndOneConflicts() throws Exception {
        UUID studentId = createActiveStudentUser("student.concurrent@example.com");
        String token = createAccessToken(studentId, "student.concurrent@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Concurrent Base Goal");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    MvcResult res = mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {
                                              "title": "Concurrent Revision %d",
                                              "startDate": "2026-10-01",
                                              "targetDate": "2027-01-31",
                                              "durationDays": 122,
                                              "changeReason": "Concurrent attempt %d",
                                              "objectives": [
                                                { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                              ]
                                            }
                                            """.formatted(index, index)))
                            .andReturn();

                    int statusCode = res.getResponse().getStatus();
                    if (statusCode == 201) {
                        successCount.incrementAndGet();
                    } else if (statusCode == 409) {
                        conflictCount.incrementAndGet();
                    } else {
                        otherCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
        assertThat(otherCount.get()).isEqualTo(0);

        // Verify database has exactly 2 versions (V1 and V2)
        Integer totalVersions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?",
                Integer.class, goalId);
        assertThat(totalVersions).isEqualTo(2);

        // Verify exactly one active version (effective_until IS NULL)
        Integer currentVersions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND effective_until IS NULL",
                Integer.class, goalId);
        assertThat(currentVersions).isEqualTo(1);
    }

    @Test
    @DisplayName("Audit rollback: failure during audit rolls back CAS and version insert completely")
    void createGoalVersion_auditFailure_rollsBackEntireVersionAggregate() throws Exception {
        UUID studentId = createActiveStudentUser("student.auditfail@example.com");
        String token = createAccessToken(studentId, "student.auditfail@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Audit Test Goal");

        // Mock audit failure
        doThrow(new RuntimeException("Simulated audit service crash during version creation"))
                .when(auditService).recordAudit(any());

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Audit Fail Version",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Audit will fail",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isInternalServerError());

        // Verify only 1 version exists (V1)
        Integer totalVersions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?",
                Integer.class, goalId);
        assertThat(totalVersions).isEqualTo(1);

        // Verify V1 is still active (effective_until IS NULL)
        Instant v1EffectiveUntil = jdbcTemplate.queryForObject(
                "SELECT effective_until FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ? AND version_number = 1",
                Instant.class, goalId);
        assertThat(v1EffectiveUntil).isNull();

        // Verify title did not change
        String title = jdbcTemplate.queryForObject(
                "SELECT title FROM fitness.fitness_goals WHERE id = ?", String.class, goalId);
        assertThat(title).isEqualTo("Audit Test Goal");
    }

    @Test
    @DisplayName("Same journey boundary: changing PRIMARY objective goal type returns 409 NEW_GOAL_JOURNEY_REQUIRED")
    void createGoalVersion_changingPrimaryGoalType_returns409NewGoalJourneyRequired() throws Exception {
        UUID studentId = createActiveStudentUser("student.journey@example.com");
        String token = createAccessToken(studentId, "student.journey@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Muscle Gain Journey");

        // Attempt to create version with PRIMARY FAT_LOSS (different journey)
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Shift to Fat Loss",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Switching to cutting phase",
                                  "objectives": [
                                    { "goalTypeCode": "FAT_LOSS", "priority": "PRIMARY", "sortOrder": 0 }
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("NEW_GOAL_JOURNEY_REQUIRED")))
                .andExpect(jsonPath("$.message", notNullValue()));

        // Verify version count is still 1
        Integer totalVersions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?",
                Integer.class, goalId);
        assertThat(totalVersions).isEqualTo(1);
    }

    @Test
    @DisplayName("Same journey: keeping PRIMARY objective and adding/modifying SECONDARY objectives succeeds (201)")
    void createGoalVersion_changingSecondaryObjectivesOnly_succeeds() throws Exception {
        UUID studentId = createActiveStudentUser("student.secondary@example.com");
        String token = createAccessToken(studentId, "student.secondary@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Hypertrophy Program");

        // Keep PRIMARY MUSCLE_GAIN, add SECONDARY FAT_LOSS
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Hypertrophy with Fat Loss Secondary",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "Adding secondary fat loss objective",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY", "sortOrder": 0 },
                                    { "goalTypeCode": "FAT_LOSS", "priority": "SECONDARY", "sortOrder": 1, "notes": "Keep body fat in check" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber", is(2)))
                .andExpect(jsonPath("$.objectives", hasSize(2)));

        Integer totalVersions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?",
                Integer.class, goalId);
        assertThat(totalVersions).isEqualTo(2);
    }

    @Test
    @DisplayName("No-op version: duplicate version with identical snapshot returns 409 GOAL_VERSION_NO_CHANGES")
    void createGoalVersion_noOpDuplicateVersion_returns409GoalVersionNoChanges() throws Exception {
        UUID studentId = createActiveStudentUser("student.noop@example.com");
        String token = createAccessToken(studentId, "student.noop@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Initial Muscle Gain");

        // Submit exact same values as V1 created in createActiveGoal
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Initial Muscle Gain",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2026-12-31",
                                  "durationDays": 91,
                                  "changeReason": "Trying to recreate identical version",
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
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_VERSION_NO_CHANGES")))
                .andExpect(jsonPath("$.message", notNullValue()));

        // Verify only 1 version exists
        Integer totalVersions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?",
                Integer.class, goalId);
        assertThat(totalVersions).isEqualTo(1);
    }

    @Test
    @DisplayName("changeReason length: exactly 100 characters succeeds (201), 101 characters returns 400")
    void createGoalVersion_changeReasonBoundary_100CharsSuccess_101CharsFails() throws Exception {
        UUID studentId = createActiveStudentUser("student.reasonlen@example.com");
        String token = createAccessToken(studentId, "student.reasonlen@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Reason Length Test");

        String reason101 = "R".repeat(101);
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Reason 101 Title",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "%s",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """.formatted(reason101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        String reason100 = "R".repeat(100);
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Reason 100 Title",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2027-01-31",
                                  "durationDays": 122,
                                  "changeReason": "%s",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """.formatted(reason100)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber", is(2)))
                .andExpect(jsonPath("$.changeReason", is(reason100)));
    }

    @Test
    @DisplayName("Target validations: zero/negative values or invalid ranges return 400 VALIDATION_FAILED")
    void createGoalVersion_targetValidations_rejectsInvalidTargetValues() throws Exception {
        UUID studentId = createActiveStudentUser("student.tgtval@example.com");
        String token = createAccessToken(studentId, "student.tgtval@example.com", List.of("STUDENT"));
        UUID goalId = createActiveGoal(studentId, token, "Target Validation Test");

        // 1. targetValue <= 0
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "durationDays": 122,
                                  "changeReason": "Zero target value",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetValue": 0.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 2. targetValue < 0
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "durationDays": 122,
                                  "changeReason": "Negative target value",
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

        // 3. startValue <= 0
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "durationDays": 122,
                                  "changeReason": "Zero start value",
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

        // 4. targetMinValue <= 0
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "durationDays": 122,
                                  "changeReason": "Zero target min value",
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

        // 5. targetMaxValue <= 0
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "durationDays": 122,
                                  "changeReason": "Negative target max value",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetMinValue": 10.0,
                                      "targetMaxValue": -1.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 6. targetMinValue > targetMaxValue
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "durationDays": 122,
                                  "changeReason": "Min greater than max",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ],
                                  "targets": [
                                    {
                                      "metricCode": "WEIGHT",
                                      "targetMinValue": 80.0,
                                      "targetMaxValue": 70.0,
                                      "unitCode": "KG"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }
}
