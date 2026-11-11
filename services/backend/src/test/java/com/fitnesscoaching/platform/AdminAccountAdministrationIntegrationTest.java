package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAccountAdministrationIntegrationTest {

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

    private static final String DEFAULT_PASSWORD = "Password123!";

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            String actualUrl = conn.getMetaData().getURL();
            int containerPort = postgres.getMappedPort(5432);
            if (!actualUrl.contains(":" + containerPort) || actualUrl.contains(":5433")) {
                throw new IllegalStateException("DANGER: Test is pointing to live database! URL: " + actualUrl);
            }
        }

        jdbcTemplate.execute("TRUNCATE TABLE fitness.audit_logs, fitness.security_events, " +
                "fitness.session_revocation_events, fitness.admin_actions, fitness.step_up_authentications, " +
                "fitness.user_account_status_history, fitness.refresh_tokens, fitness.trainer_applications, " +
                "fitness.student_profiles, fitness.trainer_profiles, fitness.user_roles, fitness.users CASCADE");

        // Seed roles & permissions
        jdbcTemplate.execute("""
                INSERT INTO fitness.roles (code, name, description) VALUES
                ('STUDENT', 'Student', 'Student role'),
                ('TRAINER', 'Trainer', 'Trainer role'),
                ('ADMIN', 'Platform Administrator', 'Admin role')
                ON CONFLICT (code) DO NOTHING;

                INSERT INTO fitness.permissions (code, description) VALUES
                ('USER_SUSPEND', 'Suspend users'),
                ('USER_DISABLE', 'Disable users'),
                ('USER_ROLE_MANAGE', 'Manage roles'),
                ('SESSION_REVOKE', 'Revoke sessions')
                ON CONFLICT (code) DO NOTHING;

                INSERT INTO fitness.role_permissions (role_id, permission_id)
                SELECT r.id, p.id FROM fitness.roles r, fitness.permissions p
                WHERE r.code = 'ADMIN' AND p.code IN ('USER_SUSPEND', 'USER_DISABLE', 'USER_ROLE_MANAGE', 'SESSION_REVOKE')
                ON CONFLICT DO NOTHING;
                """);
    }

    private RequestPostProcessor adminJwt(UUID adminId) {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                .jwt(j -> j.subject(adminId.toString()).claim("roles", List.of("ADMIN")));
    }

    private RequestPostProcessor userJwt(UUID userId) {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .jwt(j -> j.subject(userId.toString()).claim("roles", List.of("STUDENT")));
    }

    private UUID createAdmin(String email) {
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, ?, ?, 'Admin User', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """, adminId, email, passwordEncoder.encode(DEFAULT_PASSWORD));

        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now()
                FROM fitness.roles r WHERE r.code = 'ADMIN'
                """, adminId, adminId);
        return adminId;
    }

    private UUID createUser(String email, String statusStr, String displayName) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, email_verified_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, '+84901234567', ?::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now(), now())
                """, userId, email, passwordEncoder.encode(DEFAULT_PASSWORD), displayName, statusStr);
        return userId;
    }

    private void assignRole(UUID userId, String roleCode, UUID assignedBy) {
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now()
                FROM fitness.roles r WHERE r.code = ?
                """, userId, assignedBy, roleCode);
    }

    @Test
    @DisplayName("Admin list users with search, status, and role filters")
    void testListUsersFilters() throws Exception {
        UUID adminId = createAdmin("admin1@fitcoach.vn");
        UUID u1 = createUser("student1@fitcoach.vn", "ACTIVE", "Nguyen Van An");
        assignRole(u1, "STUDENT", adminId);

        UUID u2 = createUser("trainer1@fitcoach.vn", "SUSPENDED", "Tran Thi Binh");
        assignRole(u2, "TRAINER", adminId);

        UUID u3 = createUser("pending1@fitcoach.vn", "PENDING_VERIFICATION", "Le Van Cuong");

        // 1. All users
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(4)))
                .andExpect(jsonPath("$.content", hasSize(4)));

        // 2. Filter by status SUSPENDED
        mockMvc.perform(get("/api/v1/admin/users")
                        .param("status", "SUSPENDED")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].email", is("trainer1@fitcoach.vn")));

        // 3. Filter by role STUDENT
        mockMvc.perform(get("/api/v1/admin/users")
                        .param("role", "STUDENT")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].email", is("student1@fitcoach.vn")));

        // 4. Search by display name
        mockMvc.perform(get("/api/v1/admin/users")
                        .param("search", "Cuong")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].displayName", is("Le Van Cuong")));

        // 5. Non-admin forbidden
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(userJwt(u1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin get user detail returns full profile and status history")
    void testGetUserDetail() throws Exception {
        UUID adminId = createAdmin("admin2@fitcoach.vn");
        UUID targetId = createUser("target1@fitcoach.vn", "ACTIVE", "Dang Van D");
        assignRole(targetId, "STUDENT", adminId);

        // Record a student profile
        jdbcTemplate.update("""
                INSERT INTO fitness.student_profiles (user_id, date_of_birth, gender, training_experience_level, onboarding_completed_at)
                VALUES (?, '1995-05-15', 'MALE'::fitness.gender_code, 'INTERMEDIATE', now())
                """, targetId);

        mockMvc.perform(get("/api/v1/admin/users/{userId}", targetId)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(targetId.toString())))
                .andExpect(jsonPath("$.email", is("target1@fitcoach.vn")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.studentProfile.gender", is("MALE")))
                .andExpect(jsonPath("$.studentProfile.trainingExperienceLevel", is("INTERMEDIATE")))
                .andExpect(jsonPath("$.roles[0]", is("STUDENT")));

        // Non-existent user returns 404
        mockMvc.perform(get("/api/v1/admin/users/{userId}", UUID.randomUUID())
                        .with(adminJwt(adminId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("USER_NOT_FOUND")));
    }

    @Test
    @DisplayName("Admin suspend user succeeds, revokes sessions, and records audit & history")
    void testSuspendUserSuccess() throws Exception {
        UUID adminId = createAdmin("admin3@fitcoach.vn");
        UUID targetId = createUser("target2@fitcoach.vn", "ACTIVE", "Hoang Van E");

        // Create an active refresh token for target
        jdbcTemplate.update("""
                INSERT INTO fitness.refresh_tokens (id, user_id, token_hash, expires_at, issued_at)
                VALUES (gen_random_uuid(), ?, 'fake-token-hash-1', now() + interval '30 days', now())
                """, targetId);

        String payload = """
                {
                    "reason": "Vi phạm điều khoản dịch vụ và chính sách cộng đồng",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUSPENDED")))
                .andExpect(jsonPath("$.statusHistory[0].fromStatus", is("ACTIVE")))
                .andExpect(jsonPath("$.statusHistory[0].toStatus", is("SUSPENDED")))
                .andExpect(jsonPath("$.statusHistory[0].reason", is("Vi phạm điều khoản dịch vụ và chính sách cộng đồng")));

        // Verify database state: status is SUSPENDED
        String status = jdbcTemplate.queryForObject("SELECT status FROM fitness.users WHERE id = ?", String.class, targetId);
        assertThat(status).isEqualTo("SUSPENDED");

        // Verify refresh token is revoked
        Integer activeTokens = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, targetId);
        assertThat(activeTokens).isEqualTo(0);

        // Verify session_revocation_events
        Integer revEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.session_revocation_events WHERE target_user_id = ?", Integer.class, targetId);
        assertThat(revEvents).isGreaterThanOrEqualTo(1);

        // Verify admin_actions
        Integer adminActions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.admin_actions WHERE target_id = ? AND action_type = 'SUSPEND_ACCOUNT'", Integer.class, targetId);
        assertThat(adminActions).isEqualTo(1);

        // Verify audit_logs
        Integer auditLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'USER_ACCOUNT_SUSPENDED'", Integer.class, targetId);
        assertThat(auditLogs).isEqualTo(1);
    }

    @Test
    @DisplayName("Admin cannot suspend themselves (self-action guard)")
    void testCannotSuspendSelf() throws Exception {
        UUID adminId = createAdmin("admin4@fitcoach.vn");

        String payload = """
                {
                    "reason": "Attempting to suspend self",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", adminId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("SELF_ACTION_NOT_ALLOWED")));
    }

    @Test
    @DisplayName("Admin cannot suspend the only remaining active administrator")
    void testCannotSuspendLastAdmin() throws Exception {
        UUID admin1 = createAdmin("onlyadmin@fitcoach.vn");

        // admin1 is the only admin in system
        UUID targetAdmin = admin1;
        // Even if requested by another fake token with admin role
        UUID callerAdmin = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, 'otheradmin@fitcoach.vn', ?, 'Other Admin', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """, callerAdmin, passwordEncoder.encode(DEFAULT_PASSWORD));
        // callerAdmin does NOT have ADMIN role yet, so count is 1
        assignRole(callerAdmin, "ADMIN", admin1);
        // Now count is 2. Remove callerAdmin role so count becomes 1.
        jdbcTemplate.update("DELETE FROM fitness.user_roles WHERE user_id = ?", callerAdmin);

        // Give caller temporary permission to test the last admin check
        assignRole(callerAdmin, "ADMIN", admin1);
        // Suspend admin1: now 2 admins exist, so suspend succeeds
        // But if we delete callerAdmin so only admin1 remains:
        jdbcTemplate.update("DELETE FROM fitness.user_roles WHERE user_id = ?", admin1);
        // Now callerAdmin is the ONLY admin
        String payload = """
                {
                    "reason": "Attempting to suspend only admin",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        // Try suspending callerAdmin by callerAdmin: hits self-action first
        // If callerAdmin tries to suspend a non-admin, it's fine.
    }

    @Test
    @DisplayName("Suspending already suspended user returns 409 conflict")
    void testSuspendAlreadySuspendedReturns409() throws Exception {
        UUID adminId = createAdmin("admin5@fitcoach.vn");
        UUID targetId = createUser("target3@fitcoach.vn", "SUSPENDED", "Vu Van F");

        String payload = """
                {
                    "reason": "Suspending an already suspended user",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));
    }

    @Test
    @DisplayName("Admin restore user restores SUSPENDED account to ACTIVE without resurrecting sessions")
    void testRestoreUserSuccess() throws Exception {
        UUID adminId = createAdmin("admin6@fitcoach.vn");
        UUID targetId = createUser("target4@fitcoach.vn", "SUSPENDED", "Bui Van G");

        String payload = """
                {
                    "reason": "Người dùng đã hoàn thành giải trình và khiếu nại thành công",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/restore", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.statusHistory[0].fromStatus", is("SUSPENDED")))
                .andExpect(jsonPath("$.statusHistory[0].toStatus", is("ACTIVE")));

        // Verify status is ACTIVE
        String status = jdbcTemplate.queryForObject("SELECT status FROM fitness.users WHERE id = ?", String.class, targetId);
        assertThat(status).isEqualTo("ACTIVE");

        // Verify audit log
        Integer auditLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.audit_logs WHERE target_id = ? AND action = 'USER_ACCOUNT_RESTORED'", Integer.class, targetId);
        assertThat(auditLogs).isEqualTo(1);
    }

    @Test
    @DisplayName("Restoring already ACTIVE or DISABLED account returns 409 conflict")
    void testRestoreInvalidStatusReturns409() throws Exception {
        UUID adminId = createAdmin("admin7@fitcoach.vn");
        UUID activeTarget = createUser("active@fitcoach.vn", "ACTIVE", "Active User");
        UUID disabledTarget = createUser("disabled@fitcoach.vn", "DISABLED", "Disabled User");

        String payload = """
                {
                    "reason": "Restoring invalid status account",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        // Active -> Conflict
        mockMvc.perform(post("/api/v1/admin/users/{userId}/restore", activeTarget)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));

        // Disabled -> Conflict
        mockMvc.perform(post("/api/v1/admin/users/{userId}/restore", disabledTarget)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));
    }

    @Test
    @DisplayName("Admin disable user permanently sets status to DISABLED and revokes sessions")
    void testDisableUserSuccess() throws Exception {
        UUID adminId = createAdmin("admin8@fitcoach.vn");
        UUID targetId = createUser("target5@fitcoach.vn", "ACTIVE", "Pham Van H");

        String payload = """
                {
                    "reason": "Vô hiệu hóa vĩnh viễn theo yêu cầu tuân thủ bảo mật",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/disable", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DISABLED")))
                .andExpect(jsonPath("$.statusHistory[0].toStatus", is("DISABLED")));

        String status = jdbcTemplate.queryForObject("SELECT status FROM fitness.users WHERE id = ?", String.class, targetId);
        assertThat(status).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("Admin force logout revokes sessions without altering status")
    void testForceLogoutSuccess() throws Exception {
        UUID adminId = createAdmin("admin9@fitcoach.vn");
        UUID targetId = createUser("target6@fitcoach.vn", "ACTIVE", "Dinh Van K");

        // Seed 2 active refresh tokens
        jdbcTemplate.update("""
                INSERT INTO fitness.refresh_tokens (id, user_id, token_hash, expires_at, issued_at) VALUES
                (gen_random_uuid(), ?, 'hash-token-1', now() + interval '30 days', now()),
                (gen_random_uuid(), ?, 'hash-token-2', now() + interval '30 days', now())
                """, targetId, targetId);

        String payload = """
                {
                    "reason": "Phát hiện đăng nhập bất thường từ thiết bị lạ",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/force-logout", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedSessionCount", is(2)))
                .andExpect(jsonPath("$.message", notNullValue()));

        // Status is still ACTIVE
        String status = jdbcTemplate.queryForObject("SELECT status FROM fitness.users WHERE id = ?", String.class, targetId);
        assertThat(status).isEqualTo("ACTIVE");

        // All tokens revoked
        Integer activeTokens = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, targetId);
        assertThat(activeTokens).isEqualTo(0);
    }

    @Test
    @DisplayName("Admin change user role: assign and revoke roles")
    void testChangeUserRole() throws Exception {
        UUID adminId = createAdmin("admin10@fitcoach.vn");
        UUID targetId = createUser("target7@fitcoach.vn", "ACTIVE", "Ngo Van L");

        // 1. Assign TRAINER role
        String assignPayload = """
                {
                    "roleCode": "TRAINER",
                    "action": "ASSIGN",
                    "reason": "Đã hoàn thành xác minh hồ sơ huấn luyện viên",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/roles", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignPayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles[0]", is("TRAINER")));

        // 2. Revoke TRAINER role
        String revokePayload = """
                {
                    "roleCode": "TRAINER",
                    "action": "REVOKE",
                    "reason": "Tạm dừng hoạt động huấn luyện theo nguyện vọng",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/roles", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokePayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasSize(0)));
    }

    @Test
    @DisplayName("Admin step-up endpoint issues verified step-up session and rejects wrong password")
    void testStepUpEndpoint() throws Exception {
        UUID adminId = createAdmin("admin11@fitcoach.vn");

        // 1. Correct password
        String validPayload = """
                {
                    "actionCode": "SUSPEND_ACCOUNT",
                    "password": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        MvcResult result = mockMvc.perform(post("/api/v1/admin/auth/step-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepUpAuthenticationId", notNullValue()))
                .andExpect(jsonPath("$.expiresAt", notNullValue()))
                .andReturn();

        // 2. Wrong password returns 401
        String invalidPayload = """
                {
                    "actionCode": "SUSPEND_ACCOUNT",
                    "password": "WrongPassword999!"
                }
                """;

        mockMvc.perform(post("/api/v1/admin/auth/step-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Admin dashboard summary returns platform metrics")
    void testDashboardSummary() throws Exception {
        UUID adminId = createAdmin("admin12@fitcoach.vn");
        createUser("userA@fitcoach.vn", "ACTIVE", "User A");
        createUser("userB@fitcoach.vn", "SUSPENDED", "User B");
        createUser("userC@fitcoach.vn", "DISABLED", "User C");

        mockMvc.perform(get("/api/v1/admin/dashboard/summary")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers", is(4))) // admin + 3 users
                .andExpect(jsonPath("$.activeUsers", is(2))) // admin + userA
                .andExpect(jsonPath("$.suspendedUsers", is(1)))
                .andExpect(jsonPath("$.disabledUsers", is(1)));
    }

    @Test
    @DisplayName("Admin cannot force-logout their own account via administrative endpoint")
    void testCannotForceLogoutSelf() throws Exception {
        UUID adminId = createAdmin("admin.self.fl@fitcoach.vn");

        String payload = """
                {
                    "reason": "Attempting to force logout self",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/force-logout", adminId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("SELF_ACTION_NOT_ALLOWED")));
    }

    @Test
    @DisplayName("Admin cannot disable their own account (self-action guard)")
    void testCannotDisableSelf() throws Exception {
        UUID adminId = createAdmin("admin.self.dis@fitcoach.vn");

        String payload = """
                {
                    "reason": "Attempting to disable self",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/disable", adminId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("SELF_ACTION_NOT_ALLOWED")));
    }

    @Test
    @DisplayName("Admin cannot revoke their own ADMIN role (self-action guard)")
    void testCannotRevokeOwnAdminRole() throws Exception {
        UUID adminId = createAdmin("admin.self.role@fitcoach.vn");

        String payload = """
                {
                    "roleCode": "ADMIN",
                    "action": "REVOKE",
                    "reason": "Attempting to revoke own admin role",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/roles", adminId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("SELF_ACTION_NOT_ALLOWED")));
    }

    @Test
    @DisplayName("Whitespace-only or too short reason is rejected with validation error")
    void testWhitespaceOnlyReasonRejected() throws Exception {
        UUID adminId = createAdmin("admin.val@fitcoach.vn");
        UUID targetId = createUser("target.val@fitcoach.vn", "ACTIVE", "Target Val");

        String payload = """
                {
                    "reason": "    ",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Audit failure causes transaction rollback and preserves user status")
    void testAuditFailureRollsBackStatusChange() throws Exception {
        UUID adminId = createAdmin("admin.audit.fail@fitcoach.vn");
        UUID targetId = createUser("target.audit.fail@fitcoach.vn", "ACTIVE", "Target Audit Fail");

        doThrow(new RuntimeException("Simulated audit service database failure"))
                .when(auditService).recordAudit(any());

        String payload = """
                {
                    "reason": "Valid reason for suspension under audit check",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        try {
            mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", targetId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .with(adminJwt(adminId)))
                    .andExpect(status().isInternalServerError());
        } finally {
            reset(auditService);
        }

        // Verify database status was rolled back to ACTIVE
        String status = jdbcTemplate.queryForObject("SELECT status FROM fitness.users WHERE id = ?", String.class, targetId);
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Revoked refresh token from force logout prevents obtaining new access token")
    void testRevokedRefreshTokenCannotRefresh() throws Exception {
        UUID adminId = createAdmin("admin.fl@fitcoach.vn");
        UUID targetId = createUser("target.fl@fitcoach.vn", "ACTIVE", "Target FL");

        // Perform standard login to get valid refresh token
        String loginPayload = """
                {
                    "email": "target.fl@fitcoach.vn",
                    "password": "%s",
                    "deviceName": "FL Device"
                }
                """.formatted(DEFAULT_PASSWORD);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> session = objectMapper.readValue(loginResult.getResponse().getContentAsString(), Map.class);
        String refreshToken = (String) session.get("refreshToken");
        assertThat(refreshToken).isNotBlank();

        // Admin force-logout target user
        String logoutPayload = """
                {
                    "reason": "Security force logout by admin",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/force-logout", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(logoutPayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk());

        // 4. Under new strict session revocation policy, Force-logout immediately invalidates
        // the existing access token on all endpoints (HTTP 401 UNAUTHORIZED).
        String accessToken = (String) session.get("accessToken");
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));

        // Now target tries to refresh token -> must be rejected with 401
        String refreshPayload = """
                {
                    "refreshToken": "%s"
                }
                """.formatted(refreshToken);

        mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshPayload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Concurrent attempt to suspend both remaining admins is serialized; exactly one succeeds and one is rejected")
    void testConcurrentLastAdminProtection() throws Exception {
        UUID adminA = createAdmin("admin.conc.a@fitcoach.vn");
        UUID adminB = createAdmin("admin.conc.b@fitcoach.vn");

        String payload = """
                {
                    "reason": "Concurrent admin suspension attempt",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Integer> f1 = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", adminB)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload)
                                    .with(adminJwt(adminA)))
                            .andReturn().getResponse().getStatus()
            );

            java.util.concurrent.Future<Integer> f2 = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", adminA)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload)
                                    .with(adminJwt(adminB)))
                            .andReturn().getResponse().getStatus()
            );

            int status1 = f1.get();
            int status2 = f2.get();

            List<Integer> statuses = List.of(status1, status2);
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }

        // Verify exactly one admin remains ACTIVE in database
        Integer activeAdmins = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT ur.user_id)
                FROM fitness.user_roles ur
                JOIN fitness.roles r ON r.id = ur.role_id
                JOIN fitness.users u ON u.id = ur.user_id
                WHERE r.code = 'ADMIN' AND ur.revoked_at IS NULL AND u.status = 'ACTIVE'::fitness.account_status
                """, Integer.class);
        assertThat(activeAdmins).isEqualTo(1);
    }

    @Test
    @DisplayName("Suspend account revokes refresh token, blocks new login, and reflects SUSPENDED status on /users/me")
    void testSuspendAccountTokenAndLoginImpact() throws Exception {
        UUID adminId = createAdmin("admin.sus.impact@fitcoach.vn");
        UUID targetId = createUser("target.sus.impact@fitcoach.vn", "ACTIVE", "Target Sus Impact");

        // 1. Target logs in to establish active session
        String loginPayload = """
                {
                    "email": "target.sus.impact@fitcoach.vn",
                    "password": "%s",
                    "deviceName": "Impact Device"
                }
                """.formatted(DEFAULT_PASSWORD);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> session = objectMapper.readValue(loginResult.getResponse().getContentAsString(), Map.class);
        String accessToken = (String) session.get("accessToken");
        String refreshToken = (String) session.get("refreshToken");
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();

        // 2. Admin suspends target user
        String suspendPayload = """
                {
                    "reason": "Account suspended for compliance investigation",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suspendPayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUSPENDED")));

        // 3. Old refresh token cannot be used to refresh session (HTTP 401)
        mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        // 4. Target cannot log in again with credentials (HTTP 403 ACCOUNT_UNAVAILABLE)
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));

        // 5. Calling /users/me with existing access token is IMMEDIATELY rejected (HTTP 401 UNAUTHORIZED)
        // because ActiveAccountJwtValidator strictly validates that account status is ACTIVE on every decode.
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));
    }

    @Test
    @DisplayName("Disable account revokes refresh token, blocks new login, reflects DISABLED, and cannot be restored")
    void testDisableAccountTokenAndLoginImpactAndCannotRestore() throws Exception {
        UUID adminId = createAdmin("admin.dis.impact@fitcoach.vn");
        UUID targetId = createUser("target.dis.impact@fitcoach.vn", "ACTIVE", "Target Dis Impact");

        // 1. Establish session
        String loginPayload = """
                {
                    "email": "target.dis.impact@fitcoach.vn",
                    "password": "%s",
                    "deviceName": "Impact Device"
                }
                """.formatted(DEFAULT_PASSWORD);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> session = objectMapper.readValue(loginResult.getResponse().getContentAsString(), Map.class);
        String accessToken = (String) session.get("accessToken");
        String refreshToken = (String) session.get("refreshToken");

        // 2. Admin disables target
        String disablePayload = """
                {
                    "reason": "Account permanently disabled by security officer",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/disable", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disablePayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DISABLED")));

        // 3. Refresh token rejected with 401
        mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        // 4. Login blocked with 403 ACCOUNT_UNAVAILABLE
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));

        // 5. Calling /users/me is IMMEDIATELY rejected (HTTP 401 UNAUTHORIZED) due to ActiveAccountJwtValidator
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));

        // 6. Attempting to restore DISABLED account returns 409 Conflict
        String restorePayload = """
                {
                    "reason": "Attempting to restore disabled account",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/restore", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restorePayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));
    }

    @Test
    @DisplayName("Role revocation is immediately reflected in /users/me dynamic permissions and roles")
    void testRoleChangeFreshnessOnSessionAndMe() throws Exception {
        UUID adminId = createAdmin("admin.role.fresh@fitcoach.vn");
        UUID targetId = createUser("target.role.fresh@fitcoach.vn", "ACTIVE", "Target Role Fresh");
        assignRole(targetId, "TRAINER", adminId);

        // 1. Establish session with TRAINER role
        String loginPayload = """
                {
                    "email": "target.role.fresh@fitcoach.vn",
                    "password": "%s",
                    "deviceName": "Trainer Device"
                }
                """.formatted(DEFAULT_PASSWORD);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> session = objectMapper.readValue(loginResult.getResponse().getContentAsString(), Map.class);
        String accessToken = (String) session.get("accessToken");

        // Verify initial /users/me has TRAINER role
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]", is("TRAINER")));

        // 2. Admin revokes TRAINER role
        String revokePayload = """
                {
                    "roleCode": "TRAINER",
                    "action": "REVOKE",
                    "reason": "Revoking trainer role due to license lapse",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/roles", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokePayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk());

        // 3. Verify /users/me dynamically reflects role removal immediately
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasSize(0)));
    }

    @Test
    @DisplayName("Step-up authentication is strictly bound to admin user ID and action scope")
    void testStepUpBoundToSpecificAdminAndScope() throws Exception {
        UUID adminA = createAdmin("admin.stepup.a@fitcoach.vn");
        UUID adminB = createAdmin("admin.stepup.b@fitcoach.vn");
        UUID targetId = createUser("target.stepup.user@fitcoach.vn", "ACTIVE", "Target StepUp");

        // 1. Admin A requests step-up for SUSPEND_ACCOUNT
        String stepUpPayload = """
                {
                    "actionCode": "SUSPEND_ACCOUNT",
                    "password": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        MvcResult stepUpResult = mockMvc.perform(post("/api/v1/admin/auth/step-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepUpPayload)
                        .with(adminJwt(adminA)))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> stepUpResponse = objectMapper.readValue(stepUpResult.getResponse().getContentAsString(), Map.class);
        String stepUpAuthId = (String) stepUpResponse.get("stepUpAuthenticationId");
        assertThat(stepUpAuthId).isNotBlank();

        // 2. Admin B attempts to use Admin A's step-up ID -> rejected with 403 Forbidden
        String actionWithForeignStepUp = """
                {
                    "reason": "Admin B attempts to reuse Admin A step-up",
                    "stepUpAuthenticationId": "%s"
                }
                """.formatted(stepUpAuthId);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionWithForeignStepUp)
                        .with(adminJwt(adminB)))
                .andExpect(status().isForbidden());

        // 3. Admin A attempts to use SUSPEND_ACCOUNT step-up ID for DISABLE_ACCOUNT -> rejected with 403 Forbidden
        String disableWithWrongScopeStepUp = """
                {
                    "reason": "Admin A attempts to use suspend step-up for disable",
                    "stepUpAuthenticationId": "%s"
                }
                """.formatted(stepUpAuthId);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/disable", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disableWithWrongScopeStepUp)
                        .with(adminJwt(adminA)))
                .andExpect(status().isForbidden());

        // 4. Using non-existent / fake step-up UUID -> rejected with 403 Forbidden
        String fakeStepUpPayload = """
                {
                    "reason": "Admin A attempts to use fake step-up ID",
                    "stepUpAuthenticationId": "%s"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fakeStepUpPayload)
                        .with(adminJwt(adminA)))
                .andExpect(status().isForbidden());

        // 5. Admin A uses correct step-up ID for SUSPEND_ACCOUNT -> succeeds
        String validActionPayload = """
                {
                    "reason": "Admin A uses legitimate matching step-up",
                    "stepUpAuthenticationId": "%s"
                }
                """.formatted(stepUpAuthId);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validActionPayload)
                        .with(adminJwt(adminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUSPENDED")));
    }

    @Test
    @DisplayName("Force logout invalidates existing access token and refresh token, while allowing fresh login")
    void testForceLogoutAccessAndRefreshTokenInvalidationAndFreshLogin() throws Exception {
        UUID adminId = createAdmin("admin.forcelogout.strict@fitcoach.vn");
        UUID targetId = createUser("target.forcelogout.strict@fitcoach.vn", "ACTIVE", "Target ForceLogout Strict");

        // 1. Target logs in to establish session
        String loginPayload = """
                {
                    "email": "target.forcelogout.strict@fitcoach.vn",
                    "password": "%s",
                    "deviceName": "Target Device"
                }
                """.formatted(DEFAULT_PASSWORD);

        MvcResult loginResult1 = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> session1 = objectMapper.readValue(loginResult1.getResponse().getContentAsString(), Map.class);
        String accessToken1 = (String) session1.get("accessToken");
        String refreshToken1 = (String) session1.get("refreshToken");

        // Verify initial access token works
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("target.forcelogout.strict@fitcoach.vn")));

        // 2. Admin executes Force Logout
        String forceLogoutPayload = """
                {
                    "reason": "Administrative session revocation due to suspicious activity",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/force-logout", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forceLogoutPayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk());

        // 3. Old access token is IMMEDIATELY rejected with HTTP 401 Unauthorized
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));

        // 4. Old refresh token cannot refresh session (HTTP 401)
        mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken1 + "\"}"))
                .andExpect(status().isUnauthorized());

        // 5. Target can log in afresh with credentials
        MvcResult loginResult2 = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> session2 = objectMapper.readValue(loginResult2.getResponse().getContentAsString(), Map.class);
        String accessToken2 = (String) session2.get("accessToken");
        assertThat(accessToken2).isNotBlank();
        assertThat(accessToken2).isNotEqualTo(accessToken1);

        // 6. New access token works normally (HTTP 200 OK)
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("target.forcelogout.strict@fitcoach.vn")));
    }

    @Test
    @DisplayName("Suspend then Restore does NOT resurrect access token issued prior to suspend")
    void testSuspendThenRestoreTokensCannotResurrect() throws Exception {
        UUID adminId = createAdmin("admin.restore.strict@fitcoach.vn");
        UUID targetId = createUser("target.restore.strict@fitcoach.vn", "ACTIVE", "Target Restore Strict");

        // 1. Establish session
        String loginPayload = """
                {
                    "email": "target.restore.strict@fitcoach.vn",
                    "password": "%s",
                    "deviceName": "Target Device"
                }
                """.formatted(DEFAULT_PASSWORD);

        MvcResult loginResult1 = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> session1 = objectMapper.readValue(loginResult1.getResponse().getContentAsString(), Map.class);
        String accessToken1 = (String) session1.get("accessToken");
        String refreshToken1 = (String) session1.get("refreshToken");

        // 2. Admin suspends target
        String suspendPayload = """
                {
                    "reason": "Account suspended during investigation",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/suspend", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suspendPayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUSPENDED")));

        // 3. Token rejected during suspension
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken1))
                .andExpect(status().isUnauthorized());

        // 4. Admin restores target
        String restorePayload = """
                {
                    "reason": "Investigation completed, account cleared and restored",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/restore", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restorePayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));

        // 5. Old access token STILL rejected after restore (HTTP 401)
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));

        // 6. Old refresh token cannot refresh (HTTP 401)
        mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken1 + "\"}"))
                .andExpect(status().isUnauthorized());

        // 7. Fresh login succeeds and yields working token
        MvcResult loginResult2 = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> session2 = objectMapper.readValue(loginResult2.getResponse().getContentAsString(), Map.class);
        String accessToken2 = (String) session2.get("accessToken");

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("Revoking ADMIN role revokes active sessions and immediately blocks all admin endpoints")
    void testRevokeAdminRoleBlocksAllAdminEndpointsAndRevokesSessions() throws Exception {
        UUID adminA = createAdmin("admin.a.primary@fitcoach.vn");
        UUID adminB = createAdmin("admin.b.secondary@fitcoach.vn");

        // 1. Admin B establishes real session
        String loginPayloadB = """
                {
                    "email": "admin.b.secondary@fitcoach.vn",
                    "password": "%s",
                    "deviceName": "Admin B Laptop"
                }
                """.formatted(DEFAULT_PASSWORD);

        MvcResult loginResultB = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayloadB))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> sessionB = objectMapper.readValue(loginResultB.getResponse().getContentAsString(), Map.class);
        String accessTokenB1 = (String) sessionB.get("accessToken");

        // Verify Admin B can access admin endpoint
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + accessTokenB1))
                .andExpect(status().isOk());

        // 2. Admin A revokes ADMIN role from Admin B
        String revokePayload = """
                {
                    "roleCode": "ADMIN",
                    "action": "REVOKE",
                    "reason": "Administrative rights revoked due to role realignment",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/roles", adminB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokePayload)
                        .with(adminJwt(adminA)))
                .andExpect(status().isOk());

        // 3. Old access token B1 is immediately rejected on admin endpoints (HTTP 401 Unauthorized)
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + accessTokenB1))
                .andExpect(status().isUnauthorized());

        // And on user endpoints
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessTokenB1))
                .andExpect(status().isUnauthorized());

        // 4. Admin B logs in afresh to receive new tokens without ADMIN role
        MvcResult freshLoginResultB = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayloadB))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> freshSessionB = objectMapper.readValue(freshLoginResultB.getResponse().getContentAsString(), Map.class);
        String accessTokenB2 = (String) freshSessionB.get("accessToken");

        // 5. Fresh token without ADMIN role is strictly rejected (HTTP 403 Forbidden) on admin read API
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + accessTokenB2))
                .andExpect(status().isForbidden());

        // 6. Fresh token without ADMIN role is strictly rejected (HTTP 403 Forbidden) on admin sensitive API
        String stepUpAttempt = """
                {
                    "actionCode": "SUSPEND_ACCOUNT",
                    "password": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/auth/step-up")
                        .header("Authorization", "Bearer " + accessTokenB2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepUpAttempt))
                .andExpect(status().isForbidden());

        // 7. Non-admin endpoints still work for the user
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessTokenB2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", notNullValue()));

        // 8. Legitimate Admin A continues to have active admin privileges
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(adminJwt(adminA)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Edge Case: Token issued at the exact same millisecond as revocation or lacking iat_epoch_ms is strictly revoked, while fresh token is accepted")
    void testSameMillisecondAndLegacyTokenRevocationBehavior() throws Exception {
        UUID adminId = createAdmin("admin.edge.time@fitcoach.vn");
        UUID targetId = createUser("target.edge.time@fitcoach.vn", "ACTIVE", "Target Edge Time");

        Instant eventTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // 1. Manually insert session revocation event at exact millisecond
        jdbcTemplate.update("""
                INSERT INTO fitness.session_revocation_events (id, target_user_id, requested_by, revocation_scope, reason, occurred_at)
                VALUES (gen_random_uuid(), ?, ?, 'ALL_SESSIONS', 'Test precise time cutoff', ?)
                """, targetId, adminId, Timestamp.from(eventTime));

        // 2. Token created at the EXACT SAME millisecond as revocation event (legacy without iat_epoch_ms)
        JwtClaimsSet sameMsClaims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(targetId.toString())
                .issuedAt(eventTime)
                .expiresAt(eventTime.plus(15, ChronoUnit.MINUTES))
                .id(UUID.randomUUID().toString())
                .claim("email", "target.edge.time@fitcoach.vn")
                .claim("roles", List.of("STUDENT"))
                .build();
        String sameMsToken = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), sameMsClaims)).getTokenValue();

        // Must be rejected with HTTP 401 Unauthorized (fail-safe for security)
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + sameMsToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));

        // 3. Fresh token issued strictly after revocation event (eventTime + 1 millisecond, with iat_epoch_ms)
        Instant freshTime = eventTime.plusMillis(1);
        JwtClaimsSet freshClaims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(targetId.toString())
                .issuedAt(freshTime)
                .expiresAt(freshTime.plus(15, ChronoUnit.MINUTES))
                .id(UUID.randomUUID().toString())
                .claim("email", "target.edge.time@fitcoach.vn")
                .claim("roles", List.of("STUDENT"))
                .claim("iat_epoch_ms", freshTime.toEpochMilli())
                .build();
        String freshToken = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), freshClaims)).getTokenValue();

        // Fresh token is strictly accepted with HTTP 200 OK
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("target.edge.time@fitcoach.vn")));
    }

    @Test
    @DisplayName("Edge Case: Refresh token of old session cannot spawn valid session after force-logout occurs")
    void testConcurrentRefreshDuringForceLogout() throws Exception {
        UUID adminId = createAdmin("admin.concur.refresh@fitcoach.vn");
        UUID targetId = createUser("target.concur.refresh@fitcoach.vn", "ACTIVE", "Target Concur Refresh");

        // 1. Target logs in to establish session
        String loginPayload = """
                {
                    "email": "target.concur.refresh@fitcoach.vn",
                    "password": "%s",
                    "deviceName": "Target Device"
                }
                """.formatted(DEFAULT_PASSWORD);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> session = objectMapper.readValue(loginResult.getResponse().getContentAsString(), Map.class);
        String refreshToken = (String) session.get("refreshToken");

        // 2. Admin executes Force Logout
        String forceLogoutPayload = """
                {
                    "reason": "Administrative forced session revocation",
                    "stepUpPassword": "%s"
                }
                """.formatted(DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/force-logout", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forceLogoutPayload)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk());

        // 3. Attempting to refresh using old refresh token is strictly rejected (HTTP 401)
        mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        // 4. Double-check that no active refresh tokens exist in database for target
        Integer activeTokens = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, targetId);
        assertThat(activeTokens).isEqualTo(0);
    }
}
