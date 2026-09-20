package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.ConfirmEmailRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.LoginRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.LogoutRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RefreshTokenRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RegisterRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.out.email.TestVerificationEmailSender;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.auth.domain.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthSessionIntegrationTest.TestAdminEndpointConfig.class)
class AuthSessionIntegrationTest {

    // Isolated Testcontainers instance using the project standard pgvector/pgvector:pg18 image
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
        // Exclusively configure from the isolated test container. No localhost:5433 or DATABASE_URL fallbacks.
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
    private TestVerificationEmailSender emailSender;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private com.fitnesscoaching.platform.common.config.JwtProperties jwtProperties;

    @BeforeEach
    void setUp() throws SQLException {
        // CRITICAL SAFETY ASSERTION: Prove JDBC connection URL belongs to the isolated test container
        // and NEVER touches the local development database (port 5433) or external databases.
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
        emailSender.clear();
        jdbcTemplate.execute("TRUNCATE TABLE fitness.audit_logs, fitness.security_events, fitness.refresh_tokens, " +
                "fitness.user_roles, fitness.user_settings, fitness.student_profiles, fitness.trainer_profiles, " +
                "fitness.one_time_tokens, fitness.users CASCADE");
    }

    @Test
    @DisplayName("M1B: Verified user logs in, receives signed access token with active roles and hashed refresh token")
    void testLoginIssuesSignedAccessAndHashedRefreshToken() throws Exception {
        String email = "athlete.login@example.com";
        String password = "StrongPassword123!";
        UUID userId = registerAndConfirm(email, password);

        // Assign STUDENT role in database
        assignRole(userId, "STUDENT");

        Map<String, Object> response = login(email, password, "Integration Test Device");
        String accessToken = (String) response.get("accessToken");
        String refreshToken = (String) response.get("refreshToken");

        // 1. Verify access token claims
        Jwt decoded = jwtDecoder.decode(accessToken);
        assertThat(decoded.getSubject()).isEqualTo(userId.toString());
        assertThat(decoded.getClaimAsString("email")).isEqualTo(email);
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("STUDENT");
        assertThat(decoded.getExpiresAt()).isAfter(decoded.getIssuedAt());

        // 2. Verify response current-user projection
        Map<String, Object> user = (Map<String, Object>) response.get("user");
        assertThat(user.get("id")).isEqualTo(userId.toString());
        assertThat(user.get("email")).isEqualTo(email);
        assertThat(user.get("status")).isEqualTo("ACTIVE");
        List<String> roles = (List<String>) user.get("roles");
        assertThat(roles).containsExactly("STUDENT");

        // 3. Verify refresh token storage: hashed, never raw plaintext
        Map<String, Object> storedRefresh = jdbcTemplate.queryForMap(
                "SELECT token_hash, device_name, rotated_from_id, revoked_at FROM fitness.refresh_tokens WHERE user_id = ?",
                userId);
        assertThat(storedRefresh.get("token_hash")).isEqualTo(TokenHasher.sha256Hex(refreshToken));
        assertThat(storedRefresh.get("token_hash")).isNotEqualTo(refreshToken);
        assertThat(storedRefresh.get("device_name")).isEqualTo("Integration Test Device");
        assertThat(storedRefresh.get("rotated_from_id")).isNull();
        assertThat(storedRefresh.get("revoked_at")).isNull();

        // 4. Verify last_login_at was updated
        Instant lastLoginAt = jdbcTemplate.queryForObject(
                "SELECT last_login_at FROM fitness.users WHERE id = ?", Instant.class, userId);
        assertThat(lastLoginAt).isNotNull();

        // 5. Verify security event & audit log emitted
        Integer loginEventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.security_events WHERE user_id = ? AND event_type = 'LOGIN_SUCCESS'",
                Integer.class, userId);
        assertThat(loginEventCount).isEqualTo(1);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'SESSION_CREATED'",
                Integer.class, userId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("M1B: Login projects real database state (user_settings, active roles only, profile existence, derived canCoach=false)")
    void testLoginUserProjectionMatchesDatabaseRows() throws Exception {
        String email = "projection.test@example.com";
        String password = "StrongPassword123!";
        UUID userId = registerAndConfirm(email, password);

        // Update phone_number in users table
        jdbcTemplate.update("UPDATE fitness.users SET phone_number = '+84901234567' WHERE id = ?", userId);

        // Insert custom user_settings row
        jdbcTemplate.update(
                "INSERT INTO fitness.user_settings(user_id, week_starts_on, measurement_system, accessibility_preferences, privacy_preferences) " +
                        "VALUES (?, 0, 'IMPERIAL', '{\"highContrast\": true}'::jsonb, '{\"profileVisibility\": \"PRIVATE\"}'::jsonb)",
                userId
        );

        // Assign active role STUDENT and active role TRAINER
        assignRole(userId, "STUDENT");
        assignRole(userId, "TRAINER");

        // Assign a REVOKED role (revoked_at is NOT NULL) - must NOT be included in roles!
        assignRevokedRole(userId, "ADMIN");

        // Create student_profiles row
        jdbcTemplate.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", userId);

        // Perform login
        Map<String, Object> response = login(email, password, "Settings Device");
        Map<String, Object> user = (Map<String, Object>) response.get("user");

        assertThat(user.get("phoneNumber")).isEqualTo("+84901234567");

        List<String> roles = (List<String>) user.get("roles");
        assertThat(roles).containsExactly("STUDENT", "TRAINER");
        assertThat(roles).doesNotContain("ADMIN");

        Map<String, Object> capabilities = (Map<String, Object>) user.get("capabilities");
        assertThat(capabilities.get("hasStudentProfile")).isEqualTo(true);
        assertThat(capabilities.get("hasTrainerProfile")).isEqualTo(false);
        assertThat(capabilities.get("canCoach")).isEqualTo(false);

        Map<String, Object> settings = (Map<String, Object>) user.get("settings");
        assertThat(settings.get("weekStartsOn")).isEqualTo(0);
        assertThat(settings.get("measurementSystem")).isEqualTo("IMPERIAL");

        Map<String, Object> accessibility = (Map<String, Object>) settings.get("accessibilityPreferences");
        assertThat(accessibility.get("highContrast")).isEqualTo(true);

        Map<String, Object> privacy = (Map<String, Object>) settings.get("privacyPreferences");
        assertThat(privacy.get("profileVisibility")).isEqualTo("PRIVATE");
    }

    @Test
    @DisplayName("M1B: Login projects default settings when user_settings row is absent")
    void testLoginSettingsFallbackToDefaultsWhenSettingsRowMissing() throws Exception {
        String email = "defaults.test@example.com";
        String password = "StrongPassword123!";
        UUID userId = registerAndConfirm(email, password);

        // Ensure no user_settings row exists
        jdbcTemplate.update("DELETE FROM fitness.user_settings WHERE user_id = ?", userId);

        Map<String, Object> response = login(email, password, null);
        Map<String, Object> user = (Map<String, Object>) response.get("user");
        Map<String, Object> settings = (Map<String, Object>) user.get("settings");

        assertThat(settings.get("weekStartsOn")).isEqualTo(1);
        assertThat(settings.get("measurementSystem")).isEqualTo("METRIC");
        assertThat((Map<?, ?>) settings.get("accessibilityPreferences")).isEmpty();
        assertThat((Map<?, ?>) settings.get("privacyPreferences")).isEmpty();
    }

    @Test
    @DisplayName("M1B: JWT roles claim converts to Spring Security ROLE_<CODE> authorities (403 for insufficient authority)")
    void testJwtRolesAuthorityMappingAndSecurityFilterChainAccess() throws Exception {
        String studentEmail = "student.auth@example.com";
        String adminEmail = "admin.auth@example.com";
        String password = "StrongPassword123!";

        UUID studentId = registerAndConfirm(studentEmail, password);
        assignRole(studentId, "STUDENT");

        UUID adminId = registerAndConfirm(adminEmail, password);
        assignRole(adminId, "ADMIN");

        // 1. Student login -> token has roles: ["STUDENT"]
        Map<String, Object> studentLogin = login(studentEmail, password, "Student Device");
        String studentToken = (String) studentLogin.get("accessToken");

        // Accessing /api/v1/admin/ping with student token yields 403 ACCESS_DENIED
        mockMvc.perform(get("/api/v1/admin/ping")
                        .header("Authorization", "Bearer " + studentToken)
                        .header("X-Request-ID", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")))
                .andExpect(jsonPath("$.message", is("Access is denied.")))
                .andExpect(jsonPath("$.fieldErrors", empty()));

        // 2. Admin login -> token has roles: ["ADMIN"]
        Map<String, Object> adminLogin = login(adminEmail, password, "Admin Device");
        String adminToken = (String) adminLogin.get("accessToken");

        // Accessing /api/v1/admin/ping with admin token is authorized (200 OK)
        mockMvc.perform(get("/api/v1/admin/ping")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));
    }

    @Test
    @DisplayName("M1B: Standardized 401 & 403 Security Filter Error Responses match ErrorResponse schema")
    void testStandardizedSecurityFilterErrorResponses() throws Exception {
        String customRequestId = "test-custom-request-id-401";

        // 1. Unauthenticated request to protected endpoint -> 401 UNAUTHORIZED
        mockMvc.perform(get("/actuator/info")
                        .header("X-Request-ID", customRequestId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", is("application/json;charset=UTF-8")))
                .andExpect(header().string("X-Request-ID", is(customRequestId)))
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")))
                .andExpect(jsonPath("$.message", is("Authentication is required to access this resource.")))
                .andExpect(jsonPath("$.requestId", is(customRequestId)))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors", empty()));

        // 2. Malformed/invalid token -> 401 UNAUTHORIZED
        mockMvc.perform(get("/actuator/info")
                        .header("Authorization", "Bearer invalid-tampered-token-value")
                        .header("X-Request-ID", customRequestId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", is("application/json;charset=UTF-8")))
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")))
                .andExpect(jsonPath("$.message", is("Authentication token is invalid.")));

        // 3. Expired token -> 401 AUTH_TOKEN_EXPIRED
        Instant now = Instant.now();
        JwtClaimsSet expiredClaims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(UUID.randomUUID().toString())
                .issuedAt(now.minusSeconds(3600))
                .expiresAt(now.minusSeconds(1800))
                .claim("email", "expired@example.com")
                .claim("roles", List.of("STUDENT"))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(header, expiredClaims)).getTokenValue();

        mockMvc.perform(get("/actuator/info")
                        .header("Authorization", "Bearer " + expiredToken)
                        .header("X-Request-ID", customRequestId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("AUTH_TOKEN_EXPIRED")))
                .andExpect(jsonPath("$.message", is("Authentication token has expired.")));
    }

    @Test
    @DisplayName("M1B: Unknown email and wrong password share the same failure and emit LOGIN_FAILURE without leaking credentials")
    void testInvalidCredentialsDoNotRevealAccountExistence() throws Exception {
        String email = "credentials.test@example.com";
        String password = "StrongPassword123!";
        registerAndConfirm(email, password);

        LoginRequest wrongPassword = new LoginRequest(email, "WrongPassword123!", null);
        LoginRequest unknownEmail = new LoginRequest("nonexistent@example.com", "WrongPassword123!", null);

        for (LoginRequest request : List.of(wrongPassword, unknownEmail)) {
            mockMvc.perform(post("/api/v1/auth/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode", is("INVALID_CREDENTIALS")))
                    .andExpect(jsonPath("$.message", is("Email or password is invalid.")))
                    .andExpect(jsonPath("$.fieldErrors", empty()));
        }

        // Verify LOGIN_FAILURE security events were recorded
        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT event_type, severity, details FROM fitness.security_events WHERE event_type = 'LOGIN_FAILURE'");
        assertThat(events).hasSize(2);
        for (Map<String, Object> ev : events) {
            assertThat(ev.get("severity")).isEqualTo("MEDIUM");
            assertThat(String.valueOf(ev.get("details"))).doesNotContain("WrongPassword123!");
        }
    }

    @Test
    @DisplayName("M1B: Pending and suspended accounts cannot log in even with correct credentials")
    void testAccountUnavailableStatesCannotLogin() throws Exception {
        String pendingEmail = "pending.account@example.com";
        String password = "StrongPassword123!";
        register(pendingEmail, password);

        // Pending verification
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(pendingEmail, password, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));

        Integer tokenCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.refresh_tokens", Integer.class);
        assertThat(tokenCount).isZero();

        // Suspended account
        String suspendedEmail = "suspended.account@example.com";
        UUID userId = registerAndConfirm(suspendedEmail, password);
        jdbcTemplate.update("UPDATE fitness.users SET status = 'SUSPENDED'::fitness.account_status WHERE id = ?", userId);

        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(suspendedEmail, password, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }

    @Test
    @DisplayName("M1B: Refresh token rotates once; replay revokes entire session family and emits REFRESH_TOKEN_REUSE")
    void testRefreshRotationAndReuseDetection() throws Exception {
        String email = "rotation.test@example.com";
        String password = "StrongPassword123!";
        UUID userId = registerAndConfirm(email, password);
        Map<String, Object> login = login(email, password, "Original Device");
        String originalRefreshToken = (String) login.get("refreshToken");

        // 1. First refresh rotates successfully
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(originalRefreshToken, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andReturn();

        Map<String, Object> refreshed = objectMapper.readValue(
                refreshResult.getResponse().getContentAsString(), Map.class);
        String replacementToken = (String) refreshed.get("refreshToken");
        assertThat(replacementToken).isNotEqualTo(originalRefreshToken);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT token_hash, rotated_from_id, revoked_at, revoke_reason FROM fitness.refresh_tokens " +
                        "WHERE user_id = ? ORDER BY issued_at", userId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("revoked_at")).isNotNull();
        assertThat(rows.get(0).get("revoke_reason")).isEqualTo("ROTATED");
        assertThat(rows.get(1).get("rotated_from_id")).isNotNull();
        assertThat(rows.get(1).get("revoked_at")).isNull();

        // 2. Replay attack: attempt to refresh using the old already-rotated refresh token
        mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(originalRefreshToken, null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("INVALID_REFRESH_TOKEN")));

        // 3. Verify that the replacement token was also revoked due to REUSE_DETECTED
        String replacementReason = jdbcTemplate.queryForObject(
                "SELECT revoke_reason FROM fitness.refresh_tokens WHERE token_hash = ?",
                String.class, TokenHasher.sha256Hex(replacementToken));
        assertThat(replacementReason).isEqualTo("REUSE_DETECTED");

        // 4. Verify REFRESH_TOKEN_REUSE security event was recorded
        Integer reuseEvents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.security_events WHERE user_id = ? AND event_type = 'REFRESH_TOKEN_REUSE' AND severity = 'CRITICAL'",
                Integer.class, userId);
        assertThat(reuseEvents).isGreaterThanOrEqualTo(1);

        // 5. Subsequent attempt using the replacement token fails because it is now revoked
        mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(replacementToken, null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("INVALID_REFRESH_TOKEN")));
    }

    @Test
    @DisplayName("M1B: Concurrent token refresh race condition on PostgreSQL Testcontainers: exactly one succeeds, loser triggers reuse revocation")
    void testConcurrentRefreshTokenRotationRace() throws Exception {
        String email = "race.test@example.com";
        String password = "StrongPassword123!";
        UUID userId = registerAndConfirm(email, password);
        Map<String, Object> login = login(email, password, "Race Device");
        String sharedRefreshToken = (String) login.get("refreshToken");

        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            tasks.add(() -> {
                MvcResult result = mockMvc.perform(post("/api/v1/auth/token-refreshes")
                                .header("X-Request-ID", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new RefreshTokenRequest(sharedRefreshToken, null))))
                        .andReturn();
                return result.getResponse().getStatus();
            });
        }

        List<Future<Integer>> results = executor.invokeAll(tasks);
        executor.shutdown();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger unauthorizedCount = new AtomicInteger(0);

        for (Future<Integer> f : results) {
            int status = f.get();
            if (status == 200) {
                successCount.incrementAndGet();
            } else if (status == 401) {
                unauthorizedCount.incrementAndGet();
            }
        }

        // Exactly one thread succeeds in atomic rotation; the others fail with 401
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(unauthorizedCount.get()).isEqualTo(concurrency - 1);

        // At least one REFRESH_TOKEN_REUSE event must have been recorded by the losing threads
        Integer reuseEvents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.security_events WHERE user_id = ? AND event_type = 'REFRESH_TOKEN_REUSE'",
                Integer.class, userId);
        assertThat(reuseEvents).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("M1B: Persisted settings with malformed or non-object JSON fails explicitly instead of silently returning empty settings")
    void testMalformedPersistedSettingsFailsExplicitly() throws Exception {
        String email = "malformed.settings@example.com";
        String password = "StrongPassword123!";
        UUID userId = registerAndConfirm(email, password);

        // 1. Insert non-object JSON array into accessibility_preferences (valid PostgreSQL JSON, but invalid settings object)
        jdbcTemplate.update(
                "INSERT INTO fitness.user_settings(user_id, week_starts_on, measurement_system, accessibility_preferences, privacy_preferences) " +
                        "VALUES (?, 1, 'METRIC', '[1, 2, 3]', '{}')",
                userId
        );

        // Login fails with 500 INTERNAL_SERVER_ERROR instead of silently succeeding with empty settings
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password, "Test Device"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode", is("INTERNAL_SERVER_ERROR")));

        // 2. Update to non-object JSON string in privacy_preferences
        jdbcTemplate.update(
                "UPDATE fitness.user_settings SET accessibility_preferences = '{}', privacy_preferences = '\"raw-string-not-object\"' WHERE user_id = ?",
                userId
        );

        // Login fails with 500 INTERNAL_SERVER_ERROR
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password, "Test Device"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode", is("INTERNAL_SERVER_ERROR")));
    }

    @Test
    @DisplayName("M1C: DELETE /api/v1/auth/sessions returns 401 UNAUTHORIZED when unauthenticated")
    void testLogoutUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest("some-valid-length-refresh-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));
    }

    @Test
    @DisplayName("M1C: DELETE /api/v1/auth/sessions returns 401 for invalid or expired Bearer JWT")
    void testLogoutInvalidOrExpiredJwtReturns401() throws Exception {
        // 1. Invalid JWT
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer invalid-tampered-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest("some-valid-length-refresh-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));

        // 2. Expired JWT
        Instant now = Instant.now();
        JwtClaimsSet expiredClaims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(UUID.randomUUID().toString())
                .issuedAt(now.minusSeconds(3600))
                .expiresAt(now.minusSeconds(1800))
                .claim("email", "expired@example.com")
                .claim("roles", List.of("STUDENT"))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(header, expiredClaims)).getTokenValue();

        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + expiredToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest("some-valid-length-refresh-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("AUTH_TOKEN_EXPIRED")));
    }

    @Test
    @DisplayName("M1C: DELETE /api/v1/auth/sessions returns 400 for blank, invalid, or unexpected payload")
    void testLogoutValidationErrorsReturn400() throws Exception {
        String email = "logout.validation@example.com";
        String password = "StrongPassword123!";
        registerAndConfirm(email, password);
        Map<String, Object> session = login(email, password, "Validation Device");
        String accessToken = (String) session.get("accessToken");

        // Blank refreshToken
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Too short refreshToken
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"too-short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Unknown property
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"some-valid-length-refresh-token","unknownField":"test"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("M1C: Active refresh token is revoked with LOGOUT, returns 204, emits audit/security events, while other user sessions remain active")
    void testLogoutRevokesOnlyTargetSession() throws Exception {
        String email = "logout.active@example.com";
        String password = "StrongPassword123!";
        UUID userId = registerAndConfirm(email, password);

        // Login twice to create 2 distinct sessions/devices
        Map<String, Object> session1 = login(email, password, "Phone Device");
        String accessToken1 = (String) session1.get("accessToken");
        String refreshToken1 = (String) session1.get("refreshToken");

        Map<String, Object> session2 = login(email, password, "Tablet Device");
        String refreshToken2 = (String) session2.get("refreshToken");

        // Logout session 1 using session 1's access token and refresh token
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + accessToken1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(refreshToken1))))
                .andExpect(status().isNoContent());

        // Verify session 1 is revoked with reason LOGOUT
        Map<String, Object> storedToken1 = jdbcTemplate.queryForMap(
                "SELECT revoked_at, revoke_reason FROM fitness.refresh_tokens WHERE token_hash = ?",
                TokenHasher.sha256Hex(refreshToken1));
        assertThat(storedToken1.get("revoked_at")).isNotNull();
        assertThat(storedToken1.get("revoke_reason")).isEqualTo("LOGOUT");

        // Verify session 2 remains ACTIVE
        Map<String, Object> storedToken2 = jdbcTemplate.queryForMap(
                "SELECT revoked_at, revoke_reason FROM fitness.refresh_tokens WHERE token_hash = ?",
                TokenHasher.sha256Hex(refreshToken2));
        assertThat(storedToken2.get("revoked_at")).isNull();
        assertThat(storedToken2.get("revoke_reason")).isNull();

        // Verify session 2 can still be refreshed successfully
        mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken2, "Tablet Device"))))
                .andExpect(status().isOk());

        // Verify audit log and security event emitted for logout
        Integer logoutEventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.security_events WHERE user_id = ? AND event_type = 'SESSION_LOGOUT'",
                Integer.class, userId);
        assertThat(logoutEventCount).isEqualTo(1);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'SESSION_REVOKED'",
                Integer.class, userId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("M1C: Repeated idempotent logout returns 204, does not change row, and avoids duplicate audit records")
    void testRepeatedLogoutIsIdempotent() throws Exception {
        String email = "logout.idempotent@example.com";
        String password = "StrongPassword123!";
        UUID userId = registerAndConfirm(email, password);

        Map<String, Object> session = login(email, password, "Phone Device");
        String accessToken = (String) session.get("accessToken");
        String refreshToken = (String) session.get("refreshToken");

        // First logout
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(refreshToken))))
                .andExpect(status().isNoContent());

        Map<String, Object> firstRow = jdbcTemplate.queryForMap(
                "SELECT revoked_at, revoke_reason FROM fitness.refresh_tokens WHERE token_hash = ?",
                TokenHasher.sha256Hex(refreshToken));
        Object firstRevokedAt = firstRow.get("revoked_at");
        assertThat(firstRevokedAt).isNotNull();

        // Second (idempotent) logout
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(refreshToken))))
                .andExpect(status().isNoContent());

        Map<String, Object> secondRow = jdbcTemplate.queryForMap(
                "SELECT revoked_at, revoke_reason FROM fitness.refresh_tokens WHERE token_hash = ?",
                TokenHasher.sha256Hex(refreshToken));
        assertThat(secondRow.get("revoked_at")).isEqualTo(firstRevokedAt);

        // Audit logs and security events count must remain 1
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'SESSION_REVOKED'",
                Integer.class, userId);
        assertThat(auditCount).isEqualTo(1);

        Integer logoutEventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.security_events WHERE user_id = ? AND event_type = 'SESSION_LOGOUT'",
                Integer.class, userId);
        assertThat(logoutEventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("M1C: Cross-user logout returns 204, never revokes foreign token, and records owner mismatch event")
    void testCrossUserLogoutSafeHandling() throws Exception {
        String emailA = "user.a@example.com";
        String emailB = "user.b@example.com";
        String password = "StrongPassword123!";
        UUID userAId = registerAndConfirm(emailA, password);
        UUID userBId = registerAndConfirm(emailB, password);

        Map<String, Object> sessionA = login(emailA, password, "Device A");
        String accessTokenA = (String) sessionA.get("accessToken");

        Map<String, Object> sessionB = login(emailB, password, "Device B");
        String refreshTokenB = (String) sessionB.get("refreshToken");

        // User A attempts to logout User B's refresh token
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + accessTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(refreshTokenB))))
                .andExpect(status().isNoContent());

        // User B's token remains ACTIVE
        Map<String, Object> storedB = jdbcTemplate.queryForMap(
                "SELECT revoked_at, revoke_reason FROM fitness.refresh_tokens WHERE token_hash = ?",
                TokenHasher.sha256Hex(refreshTokenB));
        assertThat(storedB.get("revoked_at")).isNull();
        assertThat(storedB.get("revoke_reason")).isNull();

        // User A has a LOGOUT_TOKEN_OWNER_MISMATCH security event
        Integer mismatchEvent = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.security_events WHERE user_id = ? AND event_type = 'LOGOUT_TOKEN_OWNER_MISMATCH'",
                Integer.class, userAId);
        assertThat(mismatchEvent).isEqualTo(1);

        // User B has NO security events recorded
        Integer userBEventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.security_events WHERE user_id = ? AND event_type = 'LOGOUT_TOKEN_OWNER_MISMATCH'",
                Integer.class, userBId);
        assertThat(userBEventCount).isEqualTo(0);

        // Details JSON must not leak raw tokens or token hashes
        String detailsJson = jdbcTemplate.queryForObject(
                "SELECT details::text FROM fitness.security_events WHERE user_id = ? AND event_type = 'LOGOUT_TOKEN_OWNER_MISMATCH'",
                String.class, userAId);
        assertThat(detailsJson).doesNotContain(refreshTokenB);
        assertThat(detailsJson).doesNotContain(TokenHasher.sha256Hex(refreshTokenB));
    }

    @Test
    @DisplayName("M1C: Non-existent refresh token returns 204 No Content without mutation or audit")
    void testNonExistentTokenLogoutReturns204() throws Exception {
        String email = "nonexistent.token@example.com";
        String password = "StrongPassword123!";
        UUID userId = registerAndConfirm(email, password);

        Map<String, Object> session = login(email, password, "Test Device");
        String accessToken = (String) session.get("accessToken");

        String dummyToken = "completely-unknown-token-that-never-existed-in-system";
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(dummyToken))))
                .andExpect(status().isNoContent());

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'SESSION_REVOKED'",
                Integer.class, userId);
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("M1C: Concurrent logouts for the same token all return 204 with exactly one audit record and atomic revocation")
    void testConcurrentLogoutsAtomicity() throws Exception {
        String email = "concurrent.logout@example.com";
        String password = "StrongPassword123!";
        UUID userId = registerAndConfirm(email, password);

        Map<String, Object> session = login(email, password, "Concurrent Device");
        String accessToken = (String) session.get("accessToken");
        String refreshToken = (String) session.get("refreshToken");

        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            tasks.add(() -> {
                MvcResult res = mockMvc.perform(delete("/api/v1/auth/sessions")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new LogoutRequest(refreshToken))))
                        .andReturn();
                return res.getResponse().getStatus();
            });
        }

        List<Future<Integer>> results = executor.invokeAll(tasks);
        executor.shutdown();

        AtomicInteger noContentCount = new AtomicInteger(0);
        for (Future<Integer> f : results) {
            if (f.get() == 204) {
                noContentCount.incrementAndGet();
            }
        }

        assertThat(noContentCount.get()).isEqualTo(concurrency);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'SESSION_REVOKED'",
                Integer.class, userId);
        assertThat(auditCount).isEqualTo(1);

        Integer logoutEventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.security_events WHERE user_id = ? AND event_type = 'SESSION_LOGOUT'",
                Integer.class, userId);
        assertThat(logoutEventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("M1C: Logged-out refresh token cannot be refreshed and triggers replay detection (revoking all user tokens)")
    void testLoggedOutTokenReplayDetection() throws Exception {
        String email = "logout.replay@example.com";
        String password = "StrongPassword123!";
        UUID userId = registerAndConfirm(email, password);

        // Session 1 (will be logged out)
        Map<String, Object> session1 = login(email, password, "Device 1");
        String accessToken1 = (String) session1.get("accessToken");
        String refreshToken1 = (String) session1.get("refreshToken");

        // Session 2 (should be active until replay attack)
        Map<String, Object> session2 = login(email, password, "Device 2");
        String refreshToken2 = (String) session2.get("refreshToken");

        // Logout session 1
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + accessToken1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(refreshToken1))))
                .andExpect(status().isNoContent());

        // Replay attack: try to refresh using the logged-out refresh token
        mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken1, null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("INVALID_REFRESH_TOKEN")));

        // Replay detection should have revoked Session 2 with reason REUSE_DETECTED
        Map<String, Object> storedToken2 = jdbcTemplate.queryForMap(
                "SELECT revoked_at, revoke_reason FROM fitness.refresh_tokens WHERE token_hash = ?",
                TokenHasher.sha256Hex(refreshToken2));
        assertThat(storedToken2.get("revoked_at")).isNotNull();
        assertThat(storedToken2.get("revoke_reason")).isEqualTo("REUSE_DETECTED");

        // Critical security event REFRESH_TOKEN_REUSE emitted
        Integer reuseEventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.security_events WHERE user_id = ? AND event_type = 'REFRESH_TOKEN_REUSE'",
                Integer.class, userId);
        assertThat(reuseEventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("M1C Security: End-to-end authentication flow and database audit/events never expose raw secrets or token hashes")
    void testEndToEndFlowLogsAndAuditDoNotExposeSecrets() throws Exception {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        rootLogger.addAppender(listAppender);

        String email = "security.redaction.e2e@example.com";
        String passwordSentinel = "P@ssword-Secret-Sentinel-999!";

        try {
            // 1. Registration
            UUID userId = register(email, passwordSentinel);
            String verificationToken = emailSender.getLastTokenFor(email);
            assertThat(verificationToken).isNotNull();

            // 2. Email verification
            mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ConfirmEmailRequest(verificationToken))))
                    .andExpect(status().isNoContent());

            // 3. Login
            Map<String, Object> session = login(email, passwordSentinel, "Redaction Audit Device");
            String accessToken = (String) session.get("accessToken");
            String refreshToken = (String) session.get("refreshToken");
            String refreshTokenHash = TokenHasher.sha256Hex(refreshToken);

            // 4. Token Refresh
            MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/token-refreshes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken, "Redaction Audit Device"))))
                    .andExpect(status().isOk())
                    .andReturn();
            Map<String, Object> refreshed = objectMapper.readValue(
                    refreshResult.getResponse().getContentAsString(), Map.class);
            String newAccessToken = (String) refreshed.get("accessToken");
            String newRefreshToken = (String) refreshed.get("refreshToken");
            String newRefreshTokenHash = TokenHasher.sha256Hex(newRefreshToken);

            // 5. Logout
            mockMvc.perform(delete("/api/v1/auth/sessions")
                            .header("Authorization", "Bearer " + newAccessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LogoutRequest(newRefreshToken))))
                    .andExpect(status().isNoContent());

            // 6. Verify Application & Spring Logs do not leak secrets
            for (ILoggingEvent event : listAppender.list) {
                String logMsg = event.getFormattedMessage();
                assertThat(logMsg)
                        .as("Log message should not contain raw password")
                        .doesNotContain(passwordSentinel);
                assertThat(logMsg)
                        .as("Log message should not contain verification token")
                        .doesNotContain(verificationToken);
                assertThat(logMsg)
                        .as("Log message should not contain original refresh token")
                        .doesNotContain(refreshToken);
                assertThat(logMsg)
                        .as("Log message should not contain new refresh token")
                        .doesNotContain(newRefreshToken);
            }

            // 7. Verify Database security_events table does not leak raw tokens or hashes
            List<String> secDetails = jdbcTemplate.queryForList(
                    "SELECT details::text FROM fitness.security_events WHERE user_id = ?",
                    String.class, userId);
            assertThat(secDetails).isNotEmpty();
            for (String detail : secDetails) {
                assertThat(detail).doesNotContain(passwordSentinel);
                assertThat(detail).doesNotContain(verificationToken);
                assertThat(detail).doesNotContain(refreshToken);
                assertThat(detail).doesNotContain(newRefreshToken);
                assertThat(detail).doesNotContain(refreshTokenHash);
                assertThat(detail).doesNotContain(newRefreshTokenHash);
            }

            // 8. Verify Database audit_logs table does not leak raw tokens or hashes
            List<String> auditMetadata = jdbcTemplate.queryForList(
                    "SELECT metadata::text FROM fitness.audit_logs WHERE actor_user_id = ?",
                    String.class, userId);
            assertThat(auditMetadata).isNotEmpty();
            for (String meta : auditMetadata) {
                assertThat(meta).doesNotContain(passwordSentinel);
                assertThat(meta).doesNotContain(verificationToken);
                assertThat(meta).doesNotContain(refreshToken);
                assertThat(meta).doesNotContain(newRefreshToken);
                assertThat(meta).doesNotContain(refreshTokenHash);
                assertThat(meta).doesNotContain(newRefreshTokenHash);
            }
        } finally {
            rootLogger.detachAppender(listAppender);
        }
    }

    private UUID registerAndConfirm(String email, String password) throws Exception {
        UUID userId = register(email, password);
        String verificationToken = emailSender.getLastTokenFor(email);
        mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConfirmEmailRequest(verificationToken))))
                .andExpect(status().isNoContent());
        return userId;
    }

    private UUID register(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, password, "M1B Athlete", "vi-VN", "Asia/Ho_Chi_Minh"))))
                .andExpect(status().isCreated())
                .andReturn();
        Map<String, Object> response = objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> user = (Map<String, Object>) response.get("user");
        return UUID.fromString((String) user.get("id"));
    }

    private Map<String, Object> login(String email, String password, String deviceName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password, deviceName))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.user.email", is(email)))
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private void ensureRole(String roleCode) {
        jdbcTemplate.update(
                "INSERT INTO fitness.roles(code, name, description) VALUES (?, ?, ?) ON CONFLICT (code) DO NOTHING",
                roleCode, roleCode, "Test role: " + roleCode
        );
    }

    private void assignRole(UUID userId, String roleCode) {
        ensureRole(roleCode);
        jdbcTemplate.update(
                "INSERT INTO fitness.user_roles(user_id, role_id) " +
                        "SELECT ?, id FROM fitness.roles WHERE code = ?",
                userId, roleCode
        );
    }

    private void assignRevokedRole(UUID userId, String roleCode) {
        ensureRole(roleCode);
        jdbcTemplate.update(
                "INSERT INTO fitness.user_roles(user_id, role_id, revoked_at) " +
                        "SELECT ?, id, now() FROM fitness.roles WHERE code = ?",
                userId, roleCode
        );
    }

    @TestConfiguration
    static class TestAdminEndpointConfig {
        @RestController
        static class TestAdminController {
            @GetMapping("/api/v1/admin/ping")
            @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
            public Map<String, String> ping() {
                return Map.of("status", "ok");
            }
        }
    }
}
