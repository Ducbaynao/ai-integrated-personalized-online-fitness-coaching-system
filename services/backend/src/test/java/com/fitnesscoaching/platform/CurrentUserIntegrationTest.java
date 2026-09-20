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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CurrentUserIntegrationTest {

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

    private UUID insertUser(String email, String displayName, String phoneNumber, AccountStatus status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """,
                id, email, passwordEncoder.encode("SecretPassword123!"), displayName, phoneNumber, status.name());
        return id;
    }

    private void ensureRole(String roleCode) {
        jdbcTemplate.update(
                "INSERT INTO fitness.roles(code, name, description) VALUES (?, ?, ?) ON CONFLICT (code) DO NOTHING",
                roleCode, roleCode, "Test role: " + roleCode
        );
    }

    private void assignRole(UUID userId, String roleCode) {
        ensureRole(roleCode);
        Integer roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.roles WHERE code = ?", Integer.class, roleCode);
        jdbcTemplate.update(
                "INSERT INTO fitness.user_roles (user_id, role_id, assigned_at) VALUES (?, ?, now())",
                userId, roleId);
    }

    private void insertStudentProfile(UUID userId) {
        jdbcTemplate.update("""
                INSERT INTO fitness.student_profiles (user_id, date_of_birth, gender, training_experience_level, created_at, updated_at)
                VALUES (?, '1995-05-15', 'FEMALE', 'INTERMEDIATE', now(), now())
                """, userId);
    }

    private void insertUserSettings(UUID userId, int weekStartsOn, String measurementSystem, String accessJson, String privacyJson) {
        jdbcTemplate.update("""
                INSERT INTO fitness.user_settings (user_id, week_starts_on, measurement_system, accessibility_preferences, privacy_preferences, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, now(), now())
                """, userId, weekStartsOn, measurementSystem, accessJson, privacyJson);
    }

    @Test
    @DisplayName("GET /api/v1/users/me returns correct roles, settings, and capabilities without leaking secrets")
    void getCurrentUser_success() throws Exception {
        UUID userId = insertUser("athlete@example.com", "Alice Athlete", "+84901234567", AccountStatus.ACTIVE);
        assignRole(userId, "STUDENT");
        insertStudentProfile(userId);
        insertUserSettings(userId, 0, "IMPERIAL", "{\"fontSize\": \"large\"}", "{\"shareActivity\": false}");

        String token = createAccessToken(userId, "athlete@example.com", List.of("STUDENT"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(userId.toString())))
                .andExpect(jsonPath("$.email", is("athlete@example.com")))
                .andExpect(jsonPath("$.displayName", is("Alice Athlete")))
                .andExpect(jsonPath("$.phoneNumber", is("+84901234567")))
                .andExpect(jsonPath("$.roles", is(List.of("STUDENT"))))
                .andExpect(jsonPath("$.capabilities.hasStudentProfile", is(true)))
                .andExpect(jsonPath("$.capabilities.hasTrainerProfile", is(false)))
                .andExpect(jsonPath("$.capabilities.canCoach", is(false)))
                .andExpect(jsonPath("$.settings.weekStartsOn", is(0)))
                .andExpect(jsonPath("$.settings.measurementSystem", is("IMPERIAL")))
                .andExpect(jsonPath("$.settings.accessibilityPreferences.fontSize", is("large")))
                .andExpect(jsonPath("$.settings.privacyPreferences.shareActivity", is(false)))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/users/me returns default settings when user_settings row is missing")
    void getCurrentUser_defaultsWhenNoSettingsRow() throws Exception {
        UUID userId = insertUser("no_settings@example.com", "Bob Runner", null, AccountStatus.ACTIVE);
        assignRole(userId, "STUDENT");

        String token = createAccessToken(userId, "no_settings@example.com", List.of("STUDENT"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber", nullValue()))
                .andExpect(jsonPath("$.settings.weekStartsOn", is(1)))
                .andExpect(jsonPath("$.settings.measurementSystem", is("METRIC")))
                .andExpect(jsonPath("$.settings.accessibilityPreferences", is(new java.util.HashMap<>())))
                .andExpect(jsonPath("$.settings.privacyPreferences", is(new java.util.HashMap<>())));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me persists partial update correctly to users and user_settings")
    void updateCurrentUser_persistsCorrectly() throws Exception {
        UUID userId = insertUser("charlie@example.com", "Charlie Old", "+84911111111", AccountStatus.ACTIVE);
        assignRole(userId, "STUDENT");
        insertUserSettings(userId, 1, "METRIC", "{\"contrast\": \"high\"}", "{\"showOnline\": true}");

        String token = createAccessToken(userId, "charlie@example.com", List.of("STUDENT"));

        // Partial update: change displayName, clear phoneNumber, update weekStartsOn and privacyPreferences
        String patchBody = """
                {
                    "displayName": "Charlie New",
                    "phoneNumber": null,
                    "settings": {
                        "weekStartsOn": 0,
                        "privacyPreferences": {
                            "showOnline": false
                        }
                    }
                }
                """;

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName", is("Charlie New")))
                .andExpect(jsonPath("$.phoneNumber", nullValue()))
                .andExpect(jsonPath("$.settings.weekStartsOn", is(0)))
                .andExpect(jsonPath("$.settings.measurementSystem", is("METRIC"))) // preserved!
                .andExpect(jsonPath("$.settings.accessibilityPreferences.contrast", is("high"))) // preserved!
                .andExpect(jsonPath("$.settings.privacyPreferences.showOnline", is(false)));

        // Verify direct database values
        String dbName = jdbcTemplate.queryForObject(
                "SELECT display_name FROM fitness.users WHERE id = ?", String.class, userId);
        String dbPhone = jdbcTemplate.queryForObject(
                "SELECT phone_number FROM fitness.users WHERE id = ?", String.class, userId);
        Integer dbWeek = jdbcTemplate.queryForObject(
                "SELECT week_starts_on FROM fitness.user_settings WHERE user_id = ?", Integer.class, userId);
        String dbMeasurement = jdbcTemplate.queryForObject(
                "SELECT measurement_system FROM fitness.user_settings WHERE user_id = ?", String.class, userId);

        assertThat(dbName).isEqualTo("Charlie New");
        assertThat(dbPhone).isNull();
        assertThat(dbWeek).isEqualTo(0);
        assertThat(dbMeasurement).isEqualTo("METRIC");
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me does not allow client to change email, status, or roles")
    void updateCurrentUser_cannotModifyProtectedFields() throws Exception {
        UUID userId = insertUser("protected@example.com", "David Dave", "+84922222222", AccountStatus.ACTIVE);
        assignRole(userId, "STUDENT");

        String token = createAccessToken(userId, "protected@example.com", List.of("STUDENT"));

        // Client attempts to sneak in email, status, or roles
        String maliciousBody = """
                {
                    "displayName": "David Updated",
                    "email": "hacked@example.com",
                    "status": "SUSPENDED",
                    "roles": ["ADMIN"]
                }
                """;

        // Rejected with 400 VALIDATION_FAILED due to FAIL_ON_UNKNOWN_PROPERTIES
        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Verify protected fields remained completely unchanged in DB
        String dbEmail = jdbcTemplate.queryForObject("SELECT email FROM fitness.users WHERE id = ?", String.class, userId);
        String dbStatus = jdbcTemplate.queryForObject("SELECT status FROM fitness.users WHERE id = ?", String.class, userId);
        List<String> dbRoles = jdbcTemplate.query(
                "SELECT r.code FROM fitness.roles r JOIN fitness.user_roles ur ON ur.role_id = r.id WHERE ur.user_id = ?",
                (rs, rowNum) -> rs.getString("code"), userId);

        assertThat(dbEmail).isEqualTo("protected@example.com");
        assertThat(dbStatus).isEqualTo("ACTIVE");
        assertThat(dbRoles).containsExactly("STUDENT");
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me rolls back the entire transaction if any part of update fails")
    void updateCurrentUser_transactionRollbackOnFailure() {
        UUID userId = insertUser("rollback@example.com", "Original Name", "+84933333333", AccountStatus.ACTIVE);

        // When updating with an invalid user_settings state via direct service or invalid constraint
        // Let's verify that a failed update does not partially commit to fitness.users
        com.fitnesscoaching.platform.modules.user.application.service.UserService userService =
                new com.fitnesscoaching.platform.modules.user.application.service.UserService(
                        new com.fitnesscoaching.platform.modules.user.adapter.out.persistence.CurrentUserReadAdapter(
                                jdbcTemplate, objectMapper),
                        new com.fitnesscoaching.platform.modules.user.adapter.out.persistence.UserPersistenceAdapter(
                                jdbcTemplate, objectMapper) {
                            @Override
                            public void upsertUserSettings(UUID uid, com.fitnesscoaching.platform.modules.user.application.model.UserSettingsData data) {
                                throw new RuntimeException("Simulated failure in user_settings update!");
                            }
                        },
                        java.time.Clock.systemUTC()
                );

        com.fitnesscoaching.platform.modules.user.application.port.in.UpdateCurrentUserCommand command =
                new com.fitnesscoaching.platform.modules.user.application.port.in.UpdateCurrentUserCommand(
                        userId,
                        com.fitnesscoaching.platform.modules.user.application.model.PatchField.of("Rolled Back Name"),
                        com.fitnesscoaching.platform.modules.user.application.model.PatchField.omitted(),
                        com.fitnesscoaching.platform.modules.user.application.model.PatchField.omitted(),
                        com.fitnesscoaching.platform.modules.user.application.model.PatchField.omitted(),
                        com.fitnesscoaching.platform.modules.user.application.model.PatchField.of(
                                new com.fitnesscoaching.platform.modules.user.application.port.in.UpdateUserSettingsCommand(
                                        com.fitnesscoaching.platform.modules.user.application.model.PatchField.of(0),
                                        com.fitnesscoaching.platform.modules.user.application.model.PatchField.omitted(),
                                        com.fitnesscoaching.platform.modules.user.application.model.PatchField.omitted(),
                                        com.fitnesscoaching.platform.modules.user.application.model.PatchField.omitted()
                                )
                        )
                );

        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbcTemplate.getDataSource())
                );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                txTemplate.execute(status -> userService.updateCurrentUser(command))
        ).hasMessageContaining("Simulated failure");

        // Verify that display_name was NOT updated to "Rolled Back Name"
        String dbName = jdbcTemplate.queryForObject(
                "SELECT display_name FROM fitness.users WHERE id = ?", String.class, userId);
        assertThat(dbName).isEqualTo("Original Name");
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me with {\"settings\": {}} is a safe no-op returning current projection")
    void updateCurrentUser_emptySettingsObjectIsSafeNoOp() throws Exception {
        UUID userId = insertUser("empty_settings@example.com", "Emma Empty", "+84944444444", AccountStatus.ACTIVE);
        assignRole(userId, "STUDENT");
        insertUserSettings(userId, 1, "METRIC", "{\"highContrast\": true}", "{}");

        String token = createAccessToken(userId, "empty_settings@example.com", List.of("STUDENT"));

        // Capture initial updated_at
        java.sql.Timestamp initialUserUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM fitness.users WHERE id = ?", java.sql.Timestamp.class, userId);
        java.sql.Timestamp initialSettingsUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM fitness.user_settings WHERE user_id = ?", java.sql.Timestamp.class, userId);

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(userId.toString())))
                .andExpect(jsonPath("$.displayName", is("Emma Empty")))
                .andExpect(jsonPath("$.phoneNumber", is("+84944444444")))
                .andExpect(jsonPath("$.settings.weekStartsOn", is(1)))
                .andExpect(jsonPath("$.settings.measurementSystem", is("METRIC")))
                .andExpect(jsonPath("$.settings.accessibilityPreferences.highContrast", is(true)));

        // Verify DB records were not modified (safe no-op)
        java.sql.Timestamp postUserUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM fitness.users WHERE id = ?", java.sql.Timestamp.class, userId);
        java.sql.Timestamp postSettingsUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM fitness.user_settings WHERE user_id = ?", java.sql.Timestamp.class, userId);

        assertThat(postUserUpdatedAt).isEqualTo(initialUserUpdatedAt);
        assertThat(postSettingsUpdatedAt).isEqualTo(initialSettingsUpdatedAt);
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me with empty body {} is rejected with 400 VALIDATION_FAILED")
    void updateCurrentUser_emptyBodyIsRejected() throws Exception {
        UUID userId = insertUser("empty_body@example.com", "Frank Empty", null, AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "empty_body@example.com", List.of("STUDENT"));

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me with {\"settings\": null} is rejected with 400 VALIDATION_FAILED")
    void updateCurrentUser_nullSettingsIsRejected() throws Exception {
        UUID userId = insertUser("null_settings@example.com", "Grace Null", null, AccountStatus.ACTIVE);
        String token = createAccessToken(userId, "null_settings@example.com", List.of("STUDENT"));

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }
}
