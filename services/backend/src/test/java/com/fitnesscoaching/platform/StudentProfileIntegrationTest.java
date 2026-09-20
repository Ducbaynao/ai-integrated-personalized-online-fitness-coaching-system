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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentProfileIntegrationTest {

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

    @BeforeEach
    void setUp() throws SQLException {
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
        jdbcTemplate.execute("TRUNCATE TABLE fitness.audit_logs, fitness.security_events, fitness.refresh_tokens, " +
                "fitness.one_time_tokens, fitness.student_availability_windows, fitness.student_profiles, " +
                "fitness.trainer_profiles, fitness.user_settings, fitness.user_roles, fitness.users CASCADE");
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

    private UUID insertUser(String email, String displayName, AccountStatus status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """,
                id, email, passwordEncoder.encode("SecretPassword123!"), displayName, "+84901234567", status.name());
        return id;
    }

    private void assignRoleDirectly(UUID userId, String roleCode, boolean revoked) {
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at, revoked_at)
                SELECT ?, r.id, ?, now() - interval '1 hour', CASE WHEN ? THEN now() ELSE NULL END
                FROM fitness.roles r
                WHERE r.code = ?
                """,
                userId, userId, revoked, roleCode);
    }

    @Test
    @DisplayName("1 & 2 & 3: Successfully create Student Profile, activates STUDENT role, logs audit, and reflects in /users/me")
    void createStudentProfile_success() throws Exception {
        UUID userId = insertUser("student1@example.com", "Student One", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "student1@example.com", Collections.emptyList());

        String requestJson = """
                {
                    "dateOfBirth": "1998-05-15",
                    "gender": "MALE",
                    "trainingExperienceLevel": "BEGINNER",
                    "trainingExperienceMonths": 6.5,
                    "availableDaysPerWeek": 4,
                    "preferredSessionMinutes": 60,
                    "onboardingCompleted": true
                }
                """;

        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/student-profiles/me"))
                .andExpect(jsonPath("$.userId", is(userId.toString())))
                .andExpect(jsonPath("$.dateOfBirth", is("1998-05-15")))
                .andExpect(jsonPath("$.gender", is("MALE")))
                .andExpect(jsonPath("$.trainingExperienceLevel", is("BEGINNER")))
                .andExpect(jsonPath("$.trainingExperienceMonths", is(6.5)))
                .andExpect(jsonPath("$.availableDaysPerWeek", is(4)))
                .andExpect(jsonPath("$.preferredSessionMinutes", is(60)))
                .andExpect(jsonPath("$.onboardingCompleted", is(true)))
                .andExpect(jsonPath("$.onboardingCompletedAt", notNullValue()))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()));

        // Verify Database State
        Integer profileCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.student_profiles WHERE user_id = ?", Integer.class, userId);
        assertThat(profileCount).isEqualTo(1);

        // Verify Role Assignment
        List<String> roles = jdbcTemplate.query(
                "SELECT r.code FROM fitness.user_roles ur JOIN fitness.roles r ON r.id = ur.role_id WHERE ur.user_id = ? AND ur.revoked_at IS NULL",
                (rs, rowNum) -> rs.getString("code"),
                userId
        );
        assertThat(roles).containsExactly("STUDENT");

        // Verify Audit Log
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'STUDENT_PROFILE_CREATED'",
                Integer.class, userId);
        assertThat(auditCount).isEqualTo(1);

        String actorRole = jdbcTemplate.queryForObject(
                "SELECT actor_role FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'STUDENT_PROFILE_CREATED'",
                String.class, userId);
        assertThat(actorRole).isEqualTo("USER");

        // Verify /users/me projection reflects capabilities and active roles
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("STUDENT")))
                .andExpect(jsonPath("$.capabilities.hasStudentProfile", is(true)))
                .andExpect(jsonPath("$.capabilities.hasTrainerProfile", is(false)))
                .andExpect(jsonPath("$.capabilities.canCoach", is(false)));
    }

    @Test
    @DisplayName("4: Unauthenticated request returns 401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/student-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/student-profiles/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 3}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("5: Creating profile a second time returns 409 STUDENT_PROFILE_ALREADY_EXISTS")
    void createProfile_duplicate_returns409() throws Exception {
        UUID userId = insertUser("duplicate@example.com", "Duplicate User", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "duplicate@example.com", Collections.emptyList());

        String json = "{\"gender\": \"FEMALE\"}";

        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_PROFILE_ALREADY_EXISTS")));

        // Profile and audit count remain exactly 1
        Integer profileCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.student_profiles WHERE user_id = ?", Integer.class, userId);
        assertThat(profileCount).isEqualTo(1);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'STUDENT_PROFILE_CREATED'",
                Integer.class, userId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("6 & 27: Concurrent profile creation for same user results in exactly 1 success (201) and 1 conflict (409)")
    void createProfile_concurrency_exactlyOneSucceeds() throws Exception {
        UUID userId = insertUser("concurrent@example.com", "Concurrent User", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "concurrent@example.com", Collections.emptyList());
        String json = "{\"availableDaysPerWeek\": 5, \"onboardingCompleted\": false}";

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                MvcResult result = mockMvc.perform(post("/api/v1/student-profiles")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andReturn();
                return result.getResponse().getStatus();
            });
        }

        List<Future<Integer>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);

        // Verify exactly 1 profile, 1 active STUDENT role, 1 audit record
        Integer profileCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.student_profiles WHERE user_id = ?", Integer.class, userId);
        assertThat(profileCount).isEqualTo(1);

        Integer roleCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.user_roles WHERE user_id = ?", Integer.class, userId);
        assertThat(roleCount).isEqualTo(1);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'STUDENT_PROFILE_CREATED'",
                Integer.class, userId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("CORRECTION 1: User with REVOKED STUDENT role receives 409 STUDENT_CAPABILITY_REVOKED and no profile is created")
    void createProfile_revokedRole_returns409AndRollsBack() throws Exception {
        UUID userId = insertUser("revoked@example.com", "Revoked User", AccountStatus.ACTIVE);
        assignRoleDirectly(userId, "STUDENT", true); // revoked_at is NOT NULL

        String token = createAccessToken(userId, "revoked@example.com", Collections.emptyList());

        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_REVOKED")));

        // Verify no profile created
        Integer profileCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.student_profiles WHERE user_id = ?", Integer.class, userId);
        assertThat(profileCount).isEqualTo(0);

        // Verify role is STILL revoked
        Boolean isRevoked = jdbcTemplate.queryForObject(
                "SELECT (revoked_at IS NOT NULL) FROM fitness.user_roles ur JOIN fitness.roles r ON r.id = ur.role_id WHERE ur.user_id = ? AND r.code = 'STUDENT'",
                Boolean.class, userId);
        assertThat(isRevoked).isTrue();

        // Verify no audit record
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ?", Integer.class, userId);
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("CORRECTION 2: Token is valid but account is SUSPENDED -> 403 ACCOUNT_UNAVAILABLE")
    void suspendedAccount_returns403() throws Exception {
        UUID userId = insertUser("suspended@example.com", "Suspended User", AccountStatus.SUSPENDED);
        String token = createAccessToken(userId, "suspended@example.com", Collections.emptyList());

        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));

        mockMvc.perform(get("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));

        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 4}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }

    @Test
    @DisplayName("7 & 8 & 9: GET /student-profiles/me returns 404 when absent, 200 when exists, and isolates user profiles")
    void getStudentProfile_behavior() throws Exception {
        UUID userA = insertUser("usera@example.com", "User A", AccountStatus.ACTIVE);
        UUID userB = insertUser("userb@example.com", "User B", AccountStatus.ACTIVE);

        String tokenA = createAccessToken(userA, "usera@example.com", Collections.emptyList());
        String tokenB = createAccessToken(userB, "userb@example.com", Collections.emptyList());

        // 7: GET when not created -> 404
        mockMvc.perform(get("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_PROFILE_NOT_FOUND")));

        // Create for user A
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gender\": \"FEMALE\", \"preferredSessionMinutes\": 45}"))
                .andExpect(status().isCreated());

        // 8: GET for user A returns user A's profile
        mockMvc.perform(get("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(userA.toString())))
                .andExpect(jsonPath("$.gender", is("FEMALE")))
                .andExpect(jsonPath("$.preferredSessionMinutes", is(45)));

        // 9: User B still gets 404 (does not see user A's profile)
        mockMvc.perform(get("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_PROFILE_NOT_FOUND")));
    }

    @Test
    @DisplayName("10 & 11 & 12: PATCH /student-profiles/me partial update semantics")
    void patchStudentProfile_semantics() throws Exception {
        UUID userId = insertUser("patchuser@example.com", "Patch User", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "patchuser@example.com", Collections.emptyList());

        // 12: PATCH when not found -> 404
        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 4}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_PROFILE_NOT_FOUND")));

        // Create profile initial state
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "dateOfBirth": "1992-08-10",
                                    "gender": "MALE",
                                    "trainingExperienceLevel": "INTERMEDIATE",
                                    "trainingExperienceMonths": 18.0,
                                    "availableDaysPerWeek": 3,
                                    "preferredSessionMinutes": 60,
                                    "onboardingCompleted": false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onboardingCompleted", is(false)))
                .andExpect(jsonPath("$.onboardingCompletedAt", nullValue()));

        // Test empty PATCH body -> 400
        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Test onboardingCompleted: null -> 400
        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Test false -> true onboarding transition: sets timestamp
        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted", is(true)))
                .andExpect(jsonPath("$.onboardingCompletedAt", notNullValue()))
                .andExpect(jsonPath("$.dateOfBirth", is("1992-08-10"))) // preserved
                .andExpect(jsonPath("$.gender", is("MALE"))); // preserved

        // Test true -> true onboarding: timestamp preserved
        String completedAtStr = jdbcTemplate.queryForObject(
                "SELECT onboarding_completed_at FROM fitness.student_profiles WHERE user_id = ?",
                String.class, userId);

        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 5, \"onboardingCompleted\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableDaysPerWeek", is(5)))
                .andExpect(jsonPath("$.onboardingCompleted", is(true)));

        String newCompletedAtStr = jdbcTemplate.queryForObject(
                "SELECT onboarding_completed_at FROM fitness.student_profiles WHERE user_id = ?",
                String.class, userId);
        assertThat(newCompletedAtStr).isEqualTo(completedAtStr);

        // Test explicit null resets nullable fields to null while omitted fields are preserved
        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "dateOfBirth": null,
                                    "gender": null,
                                    "trainingExperienceMonths": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateOfBirth", nullValue()))
                .andExpect(jsonPath("$.gender", nullValue()))
                .andExpect(jsonPath("$.trainingExperienceMonths", nullValue()))
                .andExpect(jsonPath("$.trainingExperienceLevel", is("INTERMEDIATE"))) // preserved
                .andExpect(jsonPath("$.availableDaysPerWeek", is(5))) // preserved
                .andExpect(jsonPath("$.preferredSessionMinutes", is(60))); // preserved

        // Test true -> false onboarding transition: clears timestamp
        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted", is(false)))
                .andExpect(jsonPath("$.onboardingCompletedAt", nullValue()));
    }

    @Test
    @DisplayName("13-18: Validation errors reject invalid input without converting unknown to zero")
    void validationErrors() throws Exception {
        UUID userId = insertUser("valuser@example.com", "Val User", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "valuser@example.com", Collections.emptyList());

        // 13: Future dateOfBirth -> 400
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateOfBirth\": \"2099-12-31\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 14: Negative trainingExperienceMonths -> 400
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trainingExperienceMonths\": -5.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 15: availableDaysPerWeek outside 1..7 -> 400
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 16: preferredSessionMinutes outside 5..480 -> 400
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredSessionMinutes\": 4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredSessionMinutes\": 500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 17: Invalid enum -> 400
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gender\": \"UNKNOWN_VALUE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 18: Missing/unknown nullable values stay null, NEVER 0
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trainingExperienceMonths", nullValue()))
                .andExpect(jsonPath("$.availableDaysPerWeek", nullValue()))
                .andExpect(jsonPath("$.preferredSessionMinutes", nullValue()));
    }

    @Test
    @DisplayName("21 & 22: Pre-existing TRAINER or ADMIN roles remain untouched when STUDENT capability is activated")
    void multiRole_preservesExistingRoles() throws Exception {
        UUID userId = insertUser("multirole@example.com", "Multi Role User", AccountStatus.ACTIVE);
        assignRoleDirectly(userId, "TRAINER", false);
        assignRoleDirectly(userId, "ADMIN", false);

        String token = createAccessToken(userId, "multirole@example.com", List.of("TRAINER", "ADMIN"));

        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 3}"))
                .andExpect(status().isCreated());

        // Verify roles in user_roles
        List<String> roles = jdbcTemplate.query(
                "SELECT r.code FROM fitness.user_roles ur JOIN fitness.roles r ON r.id = ur.role_id WHERE ur.user_id = ? AND ur.revoked_at IS NULL ORDER BY r.code",
                (rs, rowNum) -> rs.getString("code"),
                userId
        );
        assertThat(roles).containsExactly("ADMIN", "STUDENT", "TRAINER");

        // Verify /users/me
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", is(List.of("ADMIN", "STUDENT", "TRAINER"))))
                .andExpect(jsonPath("$.capabilities.hasStudentProfile", is(true)))
                .andExpect(jsonPath("$.capabilities.hasTrainerProfile", is(false)))
                .andExpect(jsonPath("$.capabilities.canCoach", is(false)));
    }

    @Test
    @DisplayName("POST /student-profiles when system role STUDENT is missing rolls back and returns 500")
    void createStudentProfile_whenSystemRoleMissing_rollsBack() throws Exception {
        UUID userId = insertUser("norole@example.com", "No Role", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "norole@example.com", Collections.emptyList());

        // Temporarily change code for STUDENT role to simulate missing system role
        jdbcTemplate.update("UPDATE fitness.roles SET code = 'STUDENT_DISABLED' WHERE code = 'STUDENT'");

        try {
            mockMvc.perform(post("/api/v1/student-profiles")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"onboardingCompleted\": false}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errorCode", is("INTERNAL_SERVER_ERROR")));

            // Assert no student_profiles created
            Integer profileCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.student_profiles WHERE user_id = ?", Integer.class, userId);
            assertThat(profileCount).isEqualTo(0);

            // Assert no user_roles created
            Integer roleCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.user_roles WHERE user_id = ?", Integer.class, userId);
            assertThat(roleCount).isEqualTo(0);

            // Assert no audit log created
            Integer auditCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'STUDENT_PROFILE_CREATED'",
                    Integer.class, userId);
            assertThat(auditCount).isEqualTo(0);
        } finally {
            // Restore STUDENT role
            jdbcTemplate.update("UPDATE fitness.roles SET code = 'STUDENT' WHERE code = 'STUDENT_DISABLED'");
        }
    }

    @Test
    @DisplayName("POST /student-profiles onboardingCompleted 4-case integration test (omitted, false, true, explicit null)")
    void createStudentProfile_onboardingCompleted_allCases() throws Exception {
        // Case 1: omitted -> defaults to false
        UUID user1 = insertUser("case1@example.com", "Case One", AccountStatus.ACTIVE);
        String token1 = createAccessToken(user1, "case1@example.com", Collections.emptyList());
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onboardingCompleted", is(false)))
                .andExpect(jsonPath("$.onboardingCompletedAt", nullValue()));

        // Case 2: false -> false
        UUID user2 = insertUser("case2@example.com", "Case Two", AccountStatus.ACTIVE);
        String token2 = createAccessToken(user2, "case2@example.com", Collections.emptyList());
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onboardingCompleted", is(false)))
                .andExpect(jsonPath("$.onboardingCompletedAt", nullValue()));

        // Case 3: true -> true
        UUID user3 = insertUser("case3@example.com", "Case Three", AccountStatus.ACTIVE);
        String token3 = createAccessToken(user3, "case3@example.com", Collections.emptyList());
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token3)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onboardingCompleted", is(true)))
                .andExpect(jsonPath("$.onboardingCompletedAt", notNullValue()));

        // Case 4: explicit null -> 400 VALIDATION_FAILED
        UUID user4 = insertUser("case4@example.com", "Case Four", AccountStatus.ACTIVE);
        String token4 = createAccessToken(user4, "case4@example.com", Collections.emptyList());
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("GET and PATCH /student-profiles/me enforce active capability in PostgreSQL; reject revoked and absent roles with 403")
    void getAndPatch_capabilityEnforcement_activeRevokedAbsent() throws Exception {
        UUID userId = insertUser("captest@example.com", "Cap User", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "captest@example.com", Collections.emptyList());

        // 1. Create profile + active STUDENT role via POST
        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "dateOfBirth": "1995-06-15",
                                    "gender": "MALE",
                                    "trainingExperienceLevel": "BEGINNER",
                                    "trainingExperienceMonths": 12.0,
                                    "availableDaysPerWeek": 4,
                                    "preferredSessionMinutes": 60,
                                    "onboardingCompleted": false
                                }
                                """))
                .andExpect(status().isCreated());

        // Part A: Profile exists + active STUDENT role -> GET & PATCH succeed
        mockMvc.perform(get("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gender", is("MALE")))
                .andExpect(jsonPath("$.availableDaysPerWeek", is(4)));

        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableDaysPerWeek", is(5)));

        // Record updated_at timestamp and profile state before revocation
        String updatedAtBeforeRevoke = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM fitness.student_profiles WHERE user_id = ?",
                String.class, userId);
        Integer daysBeforeRevoke = jdbcTemplate.queryForObject(
                "SELECT available_days_per_week FROM fitness.student_profiles WHERE user_id = ?",
                Integer.class, userId);
        assertThat(daysBeforeRevoke).isEqualTo(5);

        // Part B: Revoke STUDENT role in database
        jdbcTemplate.update("UPDATE fitness.user_roles SET revoked_at = now() WHERE user_id = ?", userId);

        // GET returns 403 STUDENT_CAPABILITY_UNAVAILABLE
        mockMvc.perform(get("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));

        // PATCH returns 403 STUDENT_CAPABILITY_UNAVAILABLE
        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 6}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));

        // Verify profile still exists, data and updated_at NOT changed
        Integer profileCountAfterRevoke = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.student_profiles WHERE user_id = ?",
                Integer.class, userId);
        assertThat(profileCountAfterRevoke).isEqualTo(1);

        String updatedAtAfterRevokedPatch = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM fitness.student_profiles WHERE user_id = ?",
                String.class, userId);
        Integer daysAfterRevokedPatch = jdbcTemplate.queryForObject(
                "SELECT available_days_per_week FROM fitness.student_profiles WHERE user_id = ?",
                Integer.class, userId);
        assertThat(updatedAtAfterRevokedPatch).isEqualTo(updatedAtBeforeRevoke);
        assertThat(daysAfterRevokedPatch).isEqualTo(5);

        // Part C: Absent STUDENT role (role assignment deleted)
        jdbcTemplate.update("DELETE FROM fitness.user_roles WHERE user_id = ?", userId);

        // GET returns 403 STUDENT_CAPABILITY_UNAVAILABLE
        mockMvc.perform(get("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));

        // PATCH returns 403 STUDENT_CAPABILITY_UNAVAILABLE
        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 7}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));

        // Verify profile still exists, data and updated_at NOT changed
        Integer profileCountAfterAbsent = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.student_profiles WHERE user_id = ?",
                Integer.class, userId);
        assertThat(profileCountAfterAbsent).isEqualTo(1);

        String updatedAtAfterAbsentPatch = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM fitness.student_profiles WHERE user_id = ?",
                String.class, userId);
        Integer daysAfterAbsentPatch = jdbcTemplate.queryForObject(
                "SELECT available_days_per_week FROM fitness.student_profiles WHERE user_id = ?",
                Integer.class, userId);
        assertThat(updatedAtAfterAbsentPatch).isEqualTo(updatedAtBeforeRevoke);
        assertThat(daysAfterAbsentPatch).isEqualTo(5);
    }
}
