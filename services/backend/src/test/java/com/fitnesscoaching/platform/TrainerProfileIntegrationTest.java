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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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
class TrainerProfileIntegrationTest {

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

    // ==========================================
    // 1. Success Flow & Business Invariants
    // ==========================================

    @Test
    @DisplayName("Create Trainer Profile: activates TRAINER role, logs audit, sets verificationStatus to NOT_SUBMITTED, cannot coach")
    void createTrainerProfile_success() throws Exception {
        UUID userId = insertUser("trainer1@example.com", "Alex Coach", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "trainer1@example.com", Collections.emptyList());

        String requestJson = """
                {
                    "publicSlug": "alex-coach",
                    "bio": "Certified fitness coach with 5 years experience in strength training.",
                    "yearsExperience": 5.5,
                    "acceptingStudents": true
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/trainer-profiles/me"))
                .andExpect(jsonPath("$.userId", is(userId.toString())))
                .andExpect(jsonPath("$.publicSlug", is("alex-coach")))
                .andExpect(jsonPath("$.bio", is("Certified fitness coach with 5 years experience in strength training.")))
                .andExpect(jsonPath("$.yearsExperience", is(5.5)))
                .andExpect(jsonPath("$.acceptingStudents", is(true)))
                .andExpect(jsonPath("$.verificationStatus", is("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.activityStatus", is("ACTIVE")))
                .andExpect(jsonPath("$.verifiedAt", nullValue()))
                .andExpect(jsonPath("$.coachingEligibility.eligible", is(false)))
                .andExpect(jsonPath("$.coachingEligibility.blockingReasons", hasItem("APPLICATION_NOT_SUBMITTED")))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()));

        // Verify Database State
        Integer profileCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_profiles WHERE user_id = ?", Integer.class, userId);
        assertThat(profileCount).isEqualTo(1);

        String dbSlug = jdbcTemplate.queryForObject(
                "SELECT public_slug FROM fitness.trainer_profiles WHERE user_id = ?", String.class, userId);
        assertThat(dbSlug).isEqualTo("alex-coach");

        String dbVerification = jdbcTemplate.queryForObject(
                "SELECT verification_status::text FROM fitness.trainer_profiles WHERE user_id = ?", String.class, userId);
        assertThat(dbVerification).isEqualTo("NOT_SUBMITTED");

        // Verify Role Assignment: TRAINER role is active
        List<String> roles = jdbcTemplate.query(
                "SELECT r.code FROM fitness.user_roles ur JOIN fitness.roles r ON r.id = ur.role_id WHERE ur.user_id = ? AND ur.revoked_at IS NULL",
                (rs, rowNum) -> rs.getString("code"),
                userId
        );
        assertThat(roles).containsExactly("TRAINER");

        // Verify Audit Log
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'TRAINER_PROFILE_CREATED'",
                Integer.class, userId);
        assertThat(auditCount).isEqualTo(1);

        // Verify Current User Projection: hasTrainerProfile = true, but canCoach = false!
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("TRAINER")))
                .andExpect(jsonPath("$.capabilities.hasTrainerProfile", is(true)))
                .andExpect(jsonPath("$.capabilities.canCoach", is(false)));

        // Verify GET /trainer-profiles/me returns profile with coachingEligibility.eligible = false
        mockMvc.perform(get("/api/v1/trainer-profiles/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(userId.toString())))
                .andExpect(jsonPath("$.publicSlug", is("alex-coach")))
                .andExpect(jsonPath("$.verificationStatus", is("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.coachingEligibility.eligible", is(false)))
                .andExpect(jsonPath("$.coachingEligibility.blockingReasons", hasItem("APPLICATION_NOT_SUBMITTED")));
    }

    // ==========================================
    // 2. Multi-Role Capability Integration
    // ==========================================

    @Test
    @DisplayName("Multi-role: Student user creates Trainer Profile; both profiles and roles are preserved, canCoach remains false")
    void multiRole_studentUser_createsTrainerProfile_preservesBoth() throws Exception {
        UUID userId = insertUser("dualrole@example.com", "Dual Role User", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "dualrole@example.com", Collections.emptyList());

        // Step 1: Create Student Profile
        String studentJson = """
                {
                    "dateOfBirth": "1992-03-10",
                    "gender": "FEMALE",
                    "trainingExperienceLevel": "INTERMEDIATE",
                    "availableDaysPerWeek": 3,
                    "preferredSessionMinutes": 45
                }
                """;

        mockMvc.perform(post("/api/v1/student-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(studentJson))
                .andExpect(status().isCreated());

        // Step 2: Create Trainer Profile
        String trainerJson = """
                {
                    "publicSlug": "coach-dual",
                    "bio": "Certified trainer & active trainee.",
                    "yearsExperience": 3.0,
                    "acceptingStudents": true
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainerJson))
                .andExpect(status().isCreated());

        // Step 3: Verify both profiles exist in database
        Integer studentCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.student_profiles WHERE user_id = ?", Integer.class, userId);
        Integer trainerCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_profiles WHERE user_id = ?", Integer.class, userId);
        assertThat(studentCount).isEqualTo(1);
        assertThat(trainerCount).isEqualTo(1);

        // Step 4: Verify both active roles exist
        List<String> activeRoles = jdbcTemplate.query(
                "SELECT r.code FROM fitness.user_roles ur JOIN fitness.roles r ON r.id = ur.role_id WHERE ur.user_id = ? AND ur.revoked_at IS NULL ORDER BY r.code",
                (rs, rowNum) -> rs.getString("code"),
                userId
        );
        assertThat(activeRoles).containsExactly("STUDENT", "TRAINER");

        // Step 5: Verify both endpoints return 200 OK
        mockMvc.perform(get("/api/v1/student-profiles/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(userId.toString())));

        mockMvc.perform(get("/api/v1/trainer-profiles/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(userId.toString())))
                .andExpect(jsonPath("$.coachingEligibility.eligible", is(false)));

        // Step 6: Verify /users/me reflects both capabilities, but canCoach is false
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("STUDENT")))
                .andExpect(jsonPath("$.roles", hasItem("TRAINER")))
                .andExpect(jsonPath("$.capabilities.hasStudentProfile", is(true)))
                .andExpect(jsonPath("$.capabilities.hasTrainerProfile", is(true)))
                .andExpect(jsonPath("$.capabilities.canCoach", is(false)));
    }

    // ==========================================
    // 3. Idempotent Role Activation
    // ==========================================

    @Test
    @DisplayName("Idempotent: User already has TRAINER role; profile creation succeeds without duplicate role row")
    void createTrainerProfile_idempotentRoleActivation() throws Exception {
        UUID userId = insertUser("alreadytrainer@example.com", "Trainer Direct", AccountStatus.ACTIVE);
        assignRoleDirectly(userId, "TRAINER", false);
        String token = createAccessToken(userId, "alreadytrainer@example.com", List.of("TRAINER"));

        String requestJson = """
                {
                    "publicSlug": "trainer-direct",
                    "yearsExperience": 2.0
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated());

        // Verify only one role row exists
        Integer roleCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.user_roles ur JOIN fitness.roles r ON r.id = ur.role_id WHERE ur.user_id = ? AND r.code = 'TRAINER'",
                Integer.class, userId);
        assertThat(roleCount).isEqualTo(1);
    }

    // ==========================================
    // 4. Revoked Capability Protection
    // ==========================================

    @Test
    @DisplayName("Revoked role: User whose TRAINER role was revoked cannot create profile; returns 409 and rolls back")
    void createTrainerProfile_revokedRole_returns409AndRollsBack() throws Exception {
        UUID userId = insertUser("revokedtrainer@example.com", "Revoked Trainer", AccountStatus.ACTIVE);
        assignRoleDirectly(userId, "TRAINER", true);
        String token = createAccessToken(userId, "revokedtrainer@example.com", Collections.emptyList());

        String requestJson = """
                {
                    "publicSlug": "revoked-trainer"
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_CAPABILITY_REVOKED")));

        // Verify no profile was created
        Integer profileCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_profiles WHERE user_id = ?", Integer.class, userId);
        assertThat(profileCount).isEqualTo(0);

        // Verify revoked_at was not cleared
        Integer revokedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.user_roles ur JOIN fitness.roles r ON r.id = ur.role_id WHERE ur.user_id = ? AND r.code = 'TRAINER' AND ur.revoked_at IS NOT NULL",
                Integer.class, userId);
        assertThat(revokedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /trainer-profiles/me when role revoked returns 403 TRAINER_CAPABILITY_UNAVAILABLE")
    void getTrainerProfile_revokedCapability_returns403() throws Exception {
        UUID userId = insertUser("revokedafter@example.com", "Revoked After", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "revokedafter@example.com", Collections.emptyList());

        // Create profile
        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicSlug\": \"revoked-after\"}"))
                .andExpect(status().isCreated());

        // Revoke role directly in DB (simulating admin action)
        jdbcTemplate.update("UPDATE fitness.user_roles SET revoked_at = now() WHERE user_id = ?", userId);

        // Accessing /trainer-profiles/me must return 403
        mockMvc.perform(get("/api/v1/trainer-profiles/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_CAPABILITY_UNAVAILABLE")));
    }

    // ==========================================
    // 5. Conflict Handling (Duplicates & Slugs)
    // ==========================================

    @Test
    @DisplayName("Creating profile a second time returns 409 TRAINER_PROFILE_ALREADY_EXISTS")
    void createTrainerProfile_duplicateProfile_returns409() throws Exception {
        UUID userId = insertUser("duplicate@example.com", "Duplicate User", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "duplicate@example.com", Collections.emptyList());

        String json = "{\"publicSlug\": \"duplicate-slug\"}";

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicSlug\": \"another-slug\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_PROFILE_ALREADY_EXISTS")));
    }

    @Test
    @DisplayName("Creating profile with existing publicSlug returns 409 TRAINER_SLUG_ALREADY_EXISTS")
    void createTrainerProfile_duplicateSlug_returns409() throws Exception {
        UUID user1 = insertUser("user1@example.com", "User One", AccountStatus.ACTIVE);
        UUID user2 = insertUser("user2@example.com", "User Two", AccountStatus.ACTIVE);
        String token1 = createAccessToken(user1, "user1@example.com", Collections.emptyList());
        String token2 = createAccessToken(user2, "user2@example.com", Collections.emptyList());

        // User 1 claims slug
        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicSlug\": \"elite-coach\"}"))
                .andExpect(status().isCreated());

        // User 2 attempts same slug
        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicSlug\": \"elite-coach\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_SLUG_ALREADY_EXISTS")));

        // Verify User 2 does not have a profile
        Integer user2ProfileCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_profiles WHERE user_id = ?", Integer.class, user2);
        assertThat(user2ProfileCount).isEqualTo(0);
    }

    // ==========================================
    // 6. Not Found & Account State Handling
    // ==========================================

    @Test
    @DisplayName("GET /trainer-profiles/me when no profile exists returns 404 TRAINER_PROFILE_NOT_FOUND")
    void getTrainerProfile_notFound_returns404() throws Exception {
        UUID userId = insertUser("noprofile@example.com", "No Profile", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "noprofile@example.com", Collections.emptyList());

        mockMvc.perform(get("/api/v1/trainer-profiles/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_PROFILE_NOT_FOUND")));
    }

    @Test
    @DisplayName("Suspended account returns 403 ACCOUNT_UNAVAILABLE")
    void suspendedAccount_returns403() throws Exception {
        UUID userId = insertUser("suspended@example.com", "Suspended User", AccountStatus.SUSPENDED);
        String token = createAccessToken(userId, "suspended@example.com", Collections.emptyList());

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicSlug\": \"suspended-coach\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));

        mockMvc.perform(get("/api/v1/trainer-profiles/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }

    @Test
    @DisplayName("Unauthenticated requests return 401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/trainer-profiles/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/trainer-profiles/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptingStudents\": false}"))
                .andExpect(status().isUnauthorized());
    }

    // ==========================================
    // 7. Partial Updates (PATCH)
    // ==========================================

    @Test
    @DisplayName("PATCH /trainer-profiles/me updates only specified fields and preserves omitted ones")
    void updateTrainerProfile_partial_preservesOmitted() throws Exception {
        UUID userId = insertUser("patchtrainer@example.com", "Patch Trainer", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "patchtrainer@example.com", Collections.emptyList());

        // Create profile
        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "publicSlug": "original-slug",
                                    "bio": "Original bio",
                                    "yearsExperience": 4.5,
                                    "acceptingStudents": true
                                }
                                """))
                .andExpect(status().isCreated());

        // Patch only acceptingStudents and publicSlug
        mockMvc.perform(patch("/api/v1/trainer-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "publicSlug": "updated-slug",
                                    "acceptingStudents": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicSlug", is("updated-slug")))
                .andExpect(jsonPath("$.acceptingStudents", is(false)))
                .andExpect(jsonPath("$.bio", is("Original bio")))
                .andExpect(jsonPath("$.yearsExperience", is(4.5)));

        // Verify DB values
        Boolean accepting = jdbcTemplate.queryForObject(
                "SELECT is_accepting_students FROM fitness.trainer_profiles WHERE user_id = ?", Boolean.class, userId);
        assertThat(accepting).isFalse();
    }

    @Test
    @DisplayName("PATCH /trainer-profiles/me allows setting bio to null")
    void updateTrainerProfile_clearBio_setsNull() throws Exception {
        UUID userId = insertUser("clearbio@example.com", "Clear Bio Trainer", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "clearbio@example.com", Collections.emptyList());

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "publicSlug": "clear-bio-slug",
                                    "bio": "Some bio to be cleared"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/trainer-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio", nullValue()));

        String dbBio = jdbcTemplate.queryForObject(
                "SELECT bio FROM fitness.trainer_profiles WHERE user_id = ?", String.class, userId);
        assertThat(dbBio).isNull();
    }

    @Test
    @DisplayName("PATCH /trainer-profiles/me with slug conflicting with another user returns 409")
    void updateTrainerProfile_slugConflict_returns409() throws Exception {
        UUID user1 = insertUser("user1patch@example.com", "User One", AccountStatus.ACTIVE);
        UUID user2 = insertUser("user2patch@example.com", "User Two", AccountStatus.ACTIVE);
        String token1 = createAccessToken(user1, "user1patch@example.com", Collections.emptyList());
        String token2 = createAccessToken(user2, "user2patch@example.com", Collections.emptyList());

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicSlug\": \"target-slug\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicSlug\": \"other-slug\"}"))
                .andExpect(status().isCreated());

        // User 2 tries to rename to target-slug
        mockMvc.perform(patch("/api/v1/trainer-profiles/me")
                        .header("Authorization", "Bearer " + token2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicSlug\": \"target-slug\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_SLUG_ALREADY_EXISTS")));
    }

    @Test
    @DisplayName("PATCH /trainer-profiles/me rejects unauthorized governance fields like verificationStatus")
    void updateTrainerProfile_prohibitedFields_returns400() throws Exception {
        UUID userId = insertUser("hacker@example.com", "Hacker Trainer", AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "hacker@example.com", Collections.emptyList());

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicSlug\": \"hacker-slug\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/trainer-profiles/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationStatus\": \"VERIFIED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Verify verification status in DB was NOT changed
        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT verification_status::text FROM fitness.trainer_profiles WHERE user_id = ?", String.class, userId);
        assertThat(dbStatus).isEqualTo("NOT_SUBMITTED");
    }
}
