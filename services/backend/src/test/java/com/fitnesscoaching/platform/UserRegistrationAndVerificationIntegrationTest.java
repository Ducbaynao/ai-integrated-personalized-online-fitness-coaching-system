package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.ConfirmEmailRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RegisterRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.out.email.TestVerificationEmailSender;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.auth.domain.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserRegistrationAndVerificationIntegrationTest {

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
        jdbcTemplate.execute("TRUNCATE TABLE fitness.audit_logs, fitness.security_events, fitness.one_time_tokens, fitness.users CASCADE");
    }

    @Test
    @DisplayName("End-to-end: Successful registration, password hashed, token hashed, email confirmed, authority not granted, audit logged")
    void testSuccessfulRegistrationAndEmailConfirmation() throws Exception {
        String email = "athlete.slice1@example.com";
        String password = "SuperSecretPassword123!";
        String displayName = "Slice One Runner";

        RegisterRequest registerRequest = new RegisterRequest(
                email,
                password,
                displayName,
                "vi-VN",
                "Asia/Ho_Chi_Minh"
        );

        // 1. Register user
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("X-Request-ID", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.verificationRequired", is(true)))
                .andExpect(jsonPath("$.user.id", notNullValue()))
                .andExpect(jsonPath("$.user.email", is(email)))
                .andExpect(jsonPath("$.user.displayName", is(displayName)))
                .andExpect(jsonPath("$.user.status", is(AccountStatus.PENDING_VERIFICATION.name())))
                .andExpect(jsonPath("$.user.emailVerifiedAt").doesNotExist())
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn();

        String responseBody = registerResult.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> userSummary = (Map<String, Object>) responseMap.get("user");
        UUID userId = UUID.fromString((String) userSummary.get("id"));

        // 2. Verify Database State after registration
        Map<String, Object> userRow = jdbcTemplate.queryForMap(
                "SELECT id, email, password_hash, status, email_verified_at FROM fitness.users WHERE id = ?",
                userId
        );
        assertThat(String.valueOf(userRow.get("email"))).isEqualTo(email);
        assertThat(userRow.get("status")).isEqualTo(AccountStatus.PENDING_VERIFICATION.name());
        assertThat(userRow.get("email_verified_at")).isNull();

        String storedPasswordHash = (String) userRow.get("password_hash");
        assertThat(storedPasswordHash).startsWith("$2a$");
        assertThat(storedPasswordHash).doesNotContain(password);

        // Verify no roles were assigned (Common Account only, no Student, Trainer, or Admin authority)
        Integer roleCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.user_roles WHERE user_id = ?",
                Integer.class,
                userId
        );
        assertThat(roleCount).isEqualTo(0);

        // Verify single-use one-time token in DB (stored as hash)
        Map<String, Object> tokenRow = jdbcTemplate.queryForMap(
                "SELECT id, purpose, token_hash, expires_at, consumed_at FROM fitness.one_time_tokens WHERE user_id = ?",
                userId
        );
        assertThat(tokenRow.get("purpose")).isEqualTo("EMAIL_VERIFICATION");
        assertThat(tokenRow.get("consumed_at")).isNull();
        String storedTokenHash = (String) tokenRow.get("token_hash");
        assertThat(storedTokenHash).matches("^[a-f0-9]{64}$");

        // Verify verification email dispatched through port
        String rawToken = emailSender.getLastTokenFor(email);
        assertThat(rawToken).isNotNull();
        assertThat(rawToken).isNotEqualTo(storedTokenHash);
        assertThat(TokenHasher.sha256Hex(rawToken)).isEqualTo(storedTokenHash);

        // Verify audit log and security event in database
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'USER_REGISTERED'",
                Integer.class,
                userId
        );
        assertThat(auditCount).isEqualTo(1);

        Integer secEventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.security_events WHERE user_id = ? AND event_type = 'USER_REGISTRATION'",
                Integer.class,
                userId
        );
        assertThat(secEventCount).isEqualTo(1);

        // 3. Confirm Email
        ConfirmEmailRequest confirmRequest = new ConfirmEmailRequest(rawToken);
        mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                        .header("X-Request-ID", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isNoContent());

        // 4. Verify Database State after confirmation
        Map<String, Object> confirmedUserRow = jdbcTemplate.queryForMap(
                "SELECT status, email_verified_at FROM fitness.users WHERE id = ?",
                userId
        );
        assertThat(confirmedUserRow.get("status")).isEqualTo(AccountStatus.ACTIVE.name());
        assertThat(confirmedUserRow.get("email_verified_at")).isNotNull();

        Map<String, Object> confirmedTokenRow = jdbcTemplate.queryForMap(
                "SELECT consumed_at FROM fitness.one_time_tokens WHERE user_id = ?",
                userId
        );
        assertThat(confirmedTokenRow.get("consumed_at")).isNotNull();

        // Verify verification audit logs
        Integer confirmAuditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'EMAIL_VERIFIED'",
                Integer.class,
                userId
        );
        assertThat(confirmAuditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Case-insensitive email uniqueness rejects registration with 409 Conflict")
    void testCaseInsensitiveDuplicateEmailRejection() throws Exception {
        RegisterRequest initialRequest = new RegisterRequest(
                "unique.runner@example.com",
                "StrongPassword123!",
                "First Athlete",
                null,
                null
        );

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialRequest)))
                .andExpect(status().isCreated());

        // Duplicate with UPPERCASE domain and name
        RegisterRequest duplicateRequest = new RegisterRequest(
                "UNIQUE.RUNNER@EXAMPLE.COM",
                "DifferentPassword123!",
                "Duplicate Athlete",
                null,
                null
        );

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("EMAIL_ALREADY_REGISTERED")))
                .andExpect(jsonPath("$.message", containsString("already registered")))
                .andExpect(jsonPath("$.fieldErrors", empty()));

        // Duplicate with leading/trailing spaces
        RegisterRequest trimmedDuplicateRequest = new RegisterRequest(
                "  Unique.Runner@Example.Com  ",
                "DifferentPassword123!",
                "Duplicate Athlete 2",
                null,
                null
        );

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trimmedDuplicateRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("EMAIL_ALREADY_REGISTERED")));
    }

    @Test
    @DisplayName("Verification token replay is rejected with 400 Bad Request")
    void testTokenReplayRejection() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest(
                "replay.check@example.com",
                "StrongPassword123!",
                "Replay Runner",
                null,
                null
        );

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        String token = emailSender.getLastTokenFor("replay.check@example.com");
        ConfirmEmailRequest confirmRequest = new ConfirmEmailRequest(token);

        // First use: 204 No Content
        mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isNoContent());

        // Replay use: 400 Bad Request
        mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_OR_EXPIRED_TOKEN")))
                .andExpect(jsonPath("$.message", containsString("invalid, expired, or has already been used")));
    }

    @Test
    @DisplayName("Expired verification token is rejected with 400 Bad Request")
    void testExpiredTokenRejection() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest(
                "expired.test@example.com",
                "StrongPassword123!",
                "Expired Runner",
                null,
                null
        );

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        String token = emailSender.getLastTokenFor("expired.test@example.com");

        // Manually set token expiration in the past
        jdbcTemplate.update(
                "UPDATE fitness.one_time_tokens SET expires_at = ? WHERE token_hash = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                TokenHasher.sha256Hex(token)
        );

        ConfirmEmailRequest confirmRequest = new ConfirmEmailRequest(token);
        mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_OR_EXPIRED_TOKEN")))
                .andExpect(jsonPath("$.message", containsString("invalid, expired, or has already been used")));
    }

    @Test
    @DisplayName("Boundary: Token expiring exactly at current time is treated as expired (400 Bad Request)")
    void testBoundaryTokenExpiringExactlyAtCurrentTime() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest(
                "exact.expiry@example.com",
                "StrongPassword123!",
                "Exact Expiry Runner",
                null,
                null
        );

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        String token = emailSender.getLastTokenFor("exact.expiry@example.com");

        // Force expires_at to be in the past or now
        jdbcTemplate.update(
                "UPDATE fitness.one_time_tokens SET expires_at = now() WHERE token_hash = ?",
                TokenHasher.sha256Hex(token)
        );

        ConfirmEmailRequest confirmRequest = new ConfirmEmailRequest(token);
        mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_OR_EXPIRED_TOKEN")));
    }

    @Test
    @DisplayName("Invalid verification token is rejected with 400 Bad Request")
    void testInvalidTokenRejection() throws Exception {
        ConfirmEmailRequest confirmRequest = new ConfirmEmailRequest("non-existent-token-value-1234567890");

        mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_OR_EXPIRED_TOKEN")))
                .andExpect(jsonPath("$.message", containsString("invalid, expired, or has already been used")));
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "DISABLED", "DELETION_PENDING", "DELETED"})
    @DisplayName("Account lifecycle authority: email confirmation refuses to reactivate unavailable account states")
    void testRefusesToReactivateUnavailableAccountStates(AccountStatus unavailableStatus) throws Exception {
        String email = "lifecycle." + unavailableStatus.name().toLowerCase() + "@example.com";
        RegisterRequest registerRequest = new RegisterRequest(
                email,
                "StrongPassword123!",
                "Lifecycle Runner",
                null,
                null
        );

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        String token = emailSender.getLastTokenFor(email);

        // Change account status directly to an unavailable state
        jdbcTemplate.update(
                "UPDATE fitness.users SET status = ?::fitness.account_status WHERE email = ?",
                unavailableStatus.name(),
                email
        );

        ConfirmEmailRequest confirmRequest = new ConfirmEmailRequest(token);
        mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_OR_EXPIRED_TOKEN")))
                .andExpect(jsonPath("$.message", containsString("invalid, expired, or has already been used")));

        // Verify account remained in the unavailable state and was NOT reactivated
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.users WHERE email = ?",
                String.class,
                email
        );
        assertThat(finalStatus).isEqualTo(unavailableStatus.name());
    }

    @Test
    @DisplayName("Concurrent confirmation calls consume token exactly once")
    void testConcurrentConfirmationSingleUse() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest(
                "concurrent.test@example.com",
                "StrongPassword123!",
                "Concurrent Runner",
                null,
                null
        );

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        String token = emailSender.getLastTokenFor("concurrent.test@example.com");

        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            tasks.add(() -> {
                ConfirmEmailRequest confirmRequest = new ConfirmEmailRequest(token);
                MvcResult result = mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                                .header("X-Request-ID", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(confirmRequest)))
                        .andReturn();
                return result.getResponse().getStatus();
            });
        }

        List<Future<Integer>> results = executor.invokeAll(tasks);
        executor.shutdown();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (Future<Integer> f : results) {
            int status = f.get();
            if (status == 204) {
                successCount.incrementAndGet();
            } else if (status == 400) {
                failureCount.incrementAndGet();
            }
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(concurrency - 1);
    }

    @Test
    @DisplayName("OpenAPI additionalProperties: false rejects unknown properties with 400")
    void testRejectUnknownProperties() throws Exception {
        String jsonPayload = """
            {
                "email": "intruder@example.com",
                "password": "StrongPassword123!",
                "displayName": "Intruder",
                "unknownField": "maliciousValue"
            }
        """;

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", containsString("Malformed request body")));
    }

    @Test
    @DisplayName("Malformed or oversized X-Request-ID is sanitized to safe UUID")
    void testSanitizeMalformedRequestId() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest(
                "reqid.test@example.com",
                "StrongPassword123!",
                "ReqId Runner",
                null,
                null
        );

        String malformedRequestId = "bad-id\r\nInjected: value";

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("X-Request-ID", malformedRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-ID", matchesPattern("^[0-9a-fA-F-]{36}$")));
    }

    @Test
    @DisplayName("Public Actuator access permits only /actuator/health")
    void testPublicActuatorRestrictedToHealth() throws Exception {
        // GET /actuator/health is permitted publicly
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        // Other actuator paths require Bearer authentication.
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Transaction rollback: Audit persistence failure rolls back users, tokens, security events, and audit logs")
    void testTransactionRollbackOnAuditPersistenceFailure() throws Exception {
        String email = "rollback.athlete@example.com";
        RegisterRequest registerRequest = new RegisterRequest(
                email,
                "SuperSecretPassword123!",
                "Rollback Athlete",
                "vi-VN",
                "Asia/Ho_Chi_Minh"
        );

        // Fail-fast safety assertion: verify JDBC connection is strictly on isolated test container
        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            String actualUrl = conn.getMetaData().getURL();
            int containerPort = postgres.getMappedPort(5432);
            if (!actualUrl.contains(":" + containerPort) || actualUrl.contains(":5433")) {
                throw new IllegalStateException("CRITICAL SAFETY VIOLATION: Refusing test execution on non-test DB: " + actualUrl);
            }
        }

        // Force audit persistence to fail inside the Spring transaction by adding a CHECK (false) constraint
        jdbcTemplate.execute("ALTER TABLE fitness.audit_logs ADD CONSTRAINT test_force_audit_failure CHECK (false)");
        try {
            mockMvc.perform(post("/api/v1/auth/registrations")
                            .header("X-Request-ID", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest)))
                    .andExpect(status().isConflict());

            // Assert that all changes are rolled back in the isolated test container database:
            Integer userCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.users WHERE email = ?",
                    Integer.class,
                    email
            );
            assertThat(userCount).isZero();

            Integer tokenCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.one_time_tokens",
                    Integer.class
            );
            assertThat(tokenCount).isZero();

            Integer securityEventsCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.security_events",
                    Integer.class
            );
            assertThat(securityEventsCount).isZero();

            Integer auditLogCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.audit_logs",
                    Integer.class
            );
            assertThat(auditLogCount).isZero();

            // Assert that verification email was NOT dispatched
            assertThat(emailSender.getLastTokenFor(email)).isNull();
        } finally {
            jdbcTemplate.execute("ALTER TABLE fitness.audit_logs DROP CONSTRAINT IF EXISTS test_force_audit_failure");
        }
    }
}
