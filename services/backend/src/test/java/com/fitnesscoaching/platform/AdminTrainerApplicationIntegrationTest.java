package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerActivityStatus;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
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
import java.time.Instant;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminTrainerApplicationIntegrationTest {

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
        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            String actualUrl = conn.getMetaData().getURL();
            int containerPort = postgres.getMappedPort(5432);
            if (!actualUrl.contains(":" + containerPort) || actualUrl.contains(":5433")) {
                throw new IllegalStateException(
                        "DANGER: Test is pointing to live database! URL: " + actualUrl);
            }
        }

        jdbcTemplate.execute("TRUNCATE TABLE fitness.audit_logs, fitness.trainer_verification_documents, " +
                "fitness.trainer_application_certificates, fitness.trainer_application_status_history, " +
                "fitness.trainer_applications, fitness.trainer_certificates, fitness.media_files, " +
                "fitness.student_profiles, fitness.trainer_profiles, fitness.user_roles, fitness.users CASCADE");
    }

    private String generateToken(UUID userId, String email, List<String> roles) {
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

    private RequestPostProcessor adminJwt(UUID adminId) {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                .jwt(j -> j.subject(adminId.toString()).claim("roles", List.of("ADMIN")));
    }

    private RequestPostProcessor trainerJwt(UUID trainerId) {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_TRAINER"))
                .jwt(j -> j.subject(trainerId.toString()).claim("roles", List.of("TRAINER")));
    }

    private RequestPostProcessor userJwt(UUID userId) {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .jwt(j -> j.subject(userId.toString()).claim("roles", List.of("USER")));
    }

    private UUID createActiveUser(String email) {
        return createUserWithStatus(email, "ACTIVE");
    }

    private UUID createUserWithStatus(String email, String status) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, ?, ?, 'Test User', '+84901234567', ?::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """, userId, email, passwordEncoder.encode("Secret123!"), status);
        return userId;
    }

    private void assignRole(UUID userId, String roleCode) {
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at, revoked_at)
                SELECT ?, r.id, ?, now() - interval '1 hour', NULL
                FROM fitness.roles r
                WHERE r.code = ?
                """, userId, userId, roleCode);
    }

    private void revokeRole(UUID userId, String roleCode) {
        jdbcTemplate.update("""
                UPDATE fitness.user_roles
                SET revoked_at = now()
                WHERE user_id = ? AND role_id = (SELECT id FROM fitness.roles WHERE code = ?)
                """, userId, roleCode);
    }

    private void createTrainerProfile(UUID userId, String slug, TrainerVerificationStatus status) {
        createTrainerProfile(userId, slug, status, true, TrainerActivityStatus.ACTIVE);
    }

    private void createTrainerProfile(UUID userId, String slug, TrainerVerificationStatus status, boolean isActive, TrainerActivityStatus activityStatus) {
        jdbcTemplate.update("""
                INSERT INTO fitness.trainer_profiles (
                    user_id, public_slug, bio, years_experience,
                    verification_status, activity_status, is_accepting_students, is_active
                ) VALUES (
                    ?, ?, 'Experienced personal trainer.', 5.0,
                    ?::fitness.trainer_verification_state, ?::fitness.trainer_activity_status, true, ?
                )
                """, userId, slug, status.name(), activityStatus.name(), isActive);
    }

    private UUID createApplication(UUID trainerId, TrainerVerificationStatus status, Instant submittedAt) {
        UUID appId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.trainer_applications (
                    id, trainer_id, status, submitted_at, applicant_note, created_at, updated_at
                ) VALUES (
                    ?, ?, ?::fitness.trainer_verification_state, ?, 'Please verify me', ?, ?
                )
                """, appId, trainerId, status.name(), java.sql.Timestamp.from(submittedAt),
                java.sql.Timestamp.from(submittedAt), java.sql.Timestamp.from(submittedAt));
        return appId;
    }

    private UUID createCertificate(UUID trainerId, String name) {
        UUID certId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.trainer_certificates (id, trainer_id, name)
                VALUES (?, ?, ?)
                """, certId, trainerId, name);
        return certId;
    }

    private UUID createMediaFile(UUID ownerId, String filename) {
        UUID mediaId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.media_files (
                    id, owner_user_id, storage_provider, bucket_name, object_key,
                    original_filename, content_type, size_bytes, media_purpose
                ) VALUES (
                    ?, ?, 'S3', 'test-bucket', ?,
                    ?, 'application/pdf', 1024, 'VERIFICATION_DOCUMENT'
                )
                """, mediaId, ownerId, "keys/" + mediaId, filename);
        return mediaId;
    }

    // --- Tests ---

    @Test
    @DisplayName("1. Admin hợp lệ xem pending applications (default queue PENDING, deterministic sorting submitted_at ASC, id ASC)")
    void getApplications_admin_defaultsToPendingQueueSorted() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");
        String adminToken = generateToken(adminId, "admin@example.com", List.of("ADMIN"));

        UUID trainer1 = createActiveUser("trainer1@example.com");
        assignRole(trainer1, "TRAINER");
        createTrainerProfile(trainer1, "coach-one", TrainerVerificationStatus.PENDING);
        Instant t1 = Instant.now().minus(2, ChronoUnit.HOURS);
        UUID app1 = createApplication(trainer1, TrainerVerificationStatus.PENDING, t1);

        UUID trainer2 = createActiveUser("trainer2@example.com");
        assignRole(trainer2, "TRAINER");
        createTrainerProfile(trainer2, "coach-two", TrainerVerificationStatus.PENDING);
        Instant t2 = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID app2 = createApplication(trainer2, TrainerVerificationStatus.PENDING, t2);

        // Third trainer with VERIFIED application should NOT appear in default PENDING queue
        UUID trainer3 = createActiveUser("trainer3@example.com");
        assignRole(trainer3, "TRAINER");
        createTrainerProfile(trainer3, "coach-three", TrainerVerificationStatus.VERIFIED);
        createApplication(trainer3, TrainerVerificationStatus.VERIFIED, Instant.now().minus(3, ChronoUnit.HOURS));

        mockMvc.perform(get("/api/v1/admin/trainer-applications")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems", is(2)))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id", is(app1.toString()))) // older submittedAt first
                .andExpect(jsonPath("$.items[1].id", is(app2.toString())))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)));
    }

    @Test
    @DisplayName("2. Admin hợp lệ xem application detail")
    void getApplicationDetail_admin_success() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");
        String adminToken = generateToken(adminId, "admin@example.com", List.of("ADMIN"));

        UUID trainerId = createActiveUser("trainer@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-detail", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        mockMvc.perform(get("/api/v1/admin/trainer-applications/" + appId)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(appId.toString())))
                .andExpect(jsonPath("$.trainerId", is(trainerId.toString())))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.applicantNote", is("Please verify me")));
    }

    @Test
    @DisplayName("3. Unauthenticated request returns 401 UNAUTHORIZED")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/trainer-applications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));
    }

    @Test
    @DisplayName("4. Non-admin (USER / TRAINER role) returns 403 ACCESS_DENIED")
    void nonAdmin_returns403() throws Exception {
        UUID userId = createActiveUser("user@example.com");
        assignRole(userId, "USER");

        mockMvc.perform(get("/api/v1/admin/trainer-applications")
                        .with(userJwt(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
    }

    @Test
    @DisplayName("5. ADMIN role đã revoked trong PostgreSQL (dù token có role ADMIN) returns 403 ACCESS_DENIED")
    void revokedAdminRole_returns403() throws Exception {
        UUID adminId = createActiveUser("revoked-admin@example.com");
        assignRole(adminId, "ADMIN");
        revokeRole(adminId, "ADMIN"); // revoked in PostgreSQL!

        mockMvc.perform(get("/api/v1/admin/trainer-applications")
                        .with(adminJwt(adminId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
    }

    @Test
    @DisplayName("6. Admin account không ACTIVE returns 403 ACCOUNT_UNAVAILABLE")
    void inactiveAdminAccount_returns403() throws Exception {
        UUID adminId = createUserWithStatus("suspended-admin@example.com", "SUSPENDED");
        assignRole(adminId, "ADMIN");

        mockMvc.perform(get("/api/v1/admin/trainer-applications")
                        .with(adminJwt(adminId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }

    @Test
    @DisplayName("7. Approve PENDING thành công (application VERIFIED, reviewedAt/By, rejectionReason null, profile VERIFIED, verifiedAt/By, history, audit)")
    void decide_approve_success() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer-approve@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-approve", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now().minus(1, ChronoUnit.HOURS));

        String body = """
                {
                    "decision": "APPROVE",
                    "reviewNotes": "All certificates verified directly with issuer."
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(appId.toString())))
                .andExpect(jsonPath("$.status", is("VERIFIED")))
                .andExpect(jsonPath("$.reviewedBy", is(adminId.toString())))
                .andExpect(jsonPath("$.reviewedAt", notNullValue()))
                .andExpect(jsonPath("$.rejectionReason", nullValue()))
                .andExpect(jsonPath("$.reviewNotes", is("All certificates verified directly with issuer.")));

        // Check trainer_profiles updated
        Map<String, Object> profile = jdbcTemplate.queryForMap(
                "SELECT verification_status, verified_at, verified_by FROM fitness.trainer_profiles WHERE user_id = ?",
                trainerId
        );
        assertThat(profile.get("verification_status")).isEqualTo("VERIFIED");
        assertThat(profile.get("verified_at")).isNotNull();
        assertThat(profile.get("verified_by")).isEqualTo(adminId);

        // Check history recorded
        Map<String, Object> history = jdbcTemplate.queryForMap(
                "SELECT from_status, to_status, changed_by, reason FROM fitness.trainer_application_status_history WHERE trainer_application_id = ?",
                appId
        );
        assertThat(history.get("from_status")).isEqualTo("PENDING");
        assertThat(history.get("to_status")).isEqualTo("VERIFIED");
        assertThat(history.get("changed_by")).isEqualTo(adminId);

        // Check audit recorded
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE action = 'TRAINER_APPLICATION_APPROVED' AND target_id = ? AND actor_user_id = ?",
                Integer.class, appId, adminId
        );
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("8. Reject PENDING thành công (application REJECTED, rejectionReason, profile REJECTED, verifiedAt/By null)")
    void decide_reject_success() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer-reject@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-reject", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now().minus(1, ChronoUnit.HOURS));

        String body = """
                {
                    "decision": "REJECT",
                    "rejectionReason": "Certification expired and could not be verified.",
                    "reviewNotes": "Contacted issuer on 2026-09-23."
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(appId.toString())))
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.rejectionReason", is("Certification expired and could not be verified.")))
                .andExpect(jsonPath("$.reviewNotes", is("Contacted issuer on 2026-09-23.")));

        // Profile should be REJECTED with verified_at and verified_by NULL
        Map<String, Object> profile = jdbcTemplate.queryForMap(
                "SELECT verification_status, verified_at, verified_by FROM fitness.trainer_profiles WHERE user_id = ?",
                trainerId
        );
        assertThat(profile.get("verification_status")).isEqualTo("REJECTED");
        assertThat(profile.get("verified_at")).isNull();
        assertThat(profile.get("verified_by")).isNull();

        // History
        Map<String, Object> history = jdbcTemplate.queryForMap(
                "SELECT from_status, to_status, changed_by, reason FROM fitness.trainer_application_status_history WHERE trainer_application_id = ?",
                appId
        );
        assertThat(history.get("from_status")).isEqualTo("PENDING");
        assertThat(history.get("to_status")).isEqualTo("REJECTED");
        assertThat(history.get("changed_by")).isEqualTo(adminId);
        assertThat(history.get("reason")).isEqualTo("Certification expired and could not be verified.");
    }

    @Test
    @DisplayName("9. Reject thiếu/blank rejectionReason returns 400 VALIDATION_FAILED")
    void decide_rejectMissingReason_returns400() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-fail", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        String body = """
                {
                    "decision": "REJECT",
                    "rejectionReason": "   "
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(adminJwt(adminId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("rejectionReason")));
    }

    @Test
    @DisplayName("10. Approve có rejectionReason returns 400 VALIDATION_FAILED")
    void decide_approveWithRejectionReason_returns400() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-fail2", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        String body = """
                {
                    "decision": "APPROVE",
                    "rejectionReason": "Should not have a reason"
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(adminJwt(adminId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("rejectionReason")));
    }

    @Test
    @DisplayName("11. Application không tồn tại returns 404 TRAINER_APPLICATION_NOT_FOUND")
    void decide_nonExistentApplication_returns404() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");
        UUID nonExistentId = UUID.randomUUID();

        String body = """
                {
                    "decision": "APPROVE"
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + nonExistentId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(adminJwt(adminId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_APPLICATION_NOT_FOUND")));
    }

    @Test
    @DisplayName("12. Approve/reject application không còn PENDING returns 409 TRAINER_APPLICATION_ALREADY_DECIDED")
    void decide_notPending_returns409() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-verified", TrainerVerificationStatus.VERIFIED);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.VERIFIED, Instant.now());

        String body = """
                {
                    "decision": "APPROVE"
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(adminJwt(adminId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_APPLICATION_ALREADY_DECIDED")));
    }

    @Test
    @DisplayName("13. Repeated decision: chỉ lần đầu thành công, lần hai nhận 409")
    void repeatedDecision_onlyFirstSucceeds() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-repeat", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        String body = "{\"decision\": \"APPROVE\"}";

        // First call -> 200
        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("VERIFIED")));

        // Second call -> 409
        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(adminJwt(adminId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_APPLICATION_ALREADY_DECIDED")));
    }

    @Test
    @DisplayName("14. Concurrent approve/approve: chỉ một thành công (1x200, 1x409)")
    void concurrentApproveApprove_onlyOneSucceeds() throws Exception {
        UUID admin1 = createActiveUser("admin1@example.com");
        assignRole(admin1, "ADMIN");
        UUID admin2 = createActiveUser("admin2@example.com");
        assignRole(admin2, "ADMIN");

        UUID trainerId = createActiveUser("trainer-conc@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-conc", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger status200 = new AtomicInteger(0);
        AtomicInteger status409 = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"APPROVE\",\"reviewNotes\":\"Admin 1\"}")
                                .with(adminJwt(admin1)))
                        .andReturn();
                int httpStatus = result.getResponse().getStatus();
                if (httpStatus == 200) status200.incrementAndGet();
                if (httpStatus == 409) status409.incrementAndGet();
            } catch (Exception e) {
                // error
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"APPROVE\",\"reviewNotes\":\"Admin 2\"}")
                                .with(adminJwt(admin2)))
                        .andReturn();
                int httpStatus = result.getResponse().getStatus();
                if (httpStatus == 200) status200.incrementAndGet();
                if (httpStatus == 409) status409.incrementAndGet();
            } catch (Exception e) {
                // error
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(status200.get()).isEqualTo(1);
        assertThat(status409.get()).isEqualTo(1);

        // Verify status history has exactly ONE decision row
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_application_status_history WHERE trainer_application_id = ? AND to_status = 'VERIFIED'",
                Integer.class, appId
        );
        assertThat(historyCount).isEqualTo(1);
    }

    @Test
    @DisplayName("15. Concurrent approve/reject: chỉ một thành công (1x200, 1x409)")
    void concurrentApproveReject_onlyOneSucceeds() throws Exception {
        UUID admin1 = createActiveUser("admin1-diff@example.com");
        assignRole(admin1, "ADMIN");
        UUID admin2 = createActiveUser("admin2-diff@example.com");
        assignRole(admin2, "ADMIN");

        UUID trainerId = createActiveUser("trainer-diff@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-diff", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger status200 = new AtomicInteger(0);
        AtomicInteger status409 = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"APPROVE\"}")
                                .with(adminJwt(admin1)))
                        .andReturn();
                int httpStatus = result.getResponse().getStatus();
                if (httpStatus == 200) status200.incrementAndGet();
                if (httpStatus == 409) status409.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"REJECT\",\"rejectionReason\":\"Cert invalid\"}")
                                .with(adminJwt(admin2)))
                        .andReturn();
                int httpStatus = result.getResponse().getStatus();
                if (httpStatus == 200) status200.incrementAndGet();
                if (httpStatus == 409) status409.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(status200.get()).isEqualTo(1);
        assertThat(status409.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("18. Audit failure rollback application, profile và history")
    void decide_auditFailure_rollsBackAllChanges() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer-audit-fail@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-audit-fail", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        // Spy audit service to throw exception
        doThrow(new RuntimeException("Simulated audit write failure"))
                .when(auditService).recordAudit(any());

        String body = "{\"decision\": \"APPROVE\"}";

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(adminJwt(adminId)))
                .andExpect(status().isInternalServerError());

        // Verify application status is STILL PENDING
        String appStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.trainer_applications WHERE id = ?",
                String.class, appId
        );
        assertThat(appStatus).isEqualTo("PENDING");

        // Verify trainer profile is STILL PENDING
        String profileStatus = jdbcTemplate.queryForObject(
                "SELECT verification_status::text FROM fitness.trainer_profiles WHERE user_id = ?",
                String.class, trainerId
        );
        assertThat(profileStatus).isEqualTo("PENDING");

        // Verify NO status history was committed
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_application_status_history WHERE trainer_application_id = ?",
                Integer.class, appId
        );
        assertThat(historyCount).isEqualTo(0);
    }

    @Test
    @DisplayName("20. Reject giữ canCoach=false")
    void decide_reject_keepsCanCoachFalse() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer-coach-false@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-false", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"rejectionReason\":\"Incomplete documentation\"}")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk());

        // Check GET /users/me for trainer
        mockMvc.perform(get("/api/v1/users/me")
                        .with(trainerJwt(trainerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.canCoach", is(false)));
    }

    @Test
    @DisplayName("21. Approve không làm canCoach=true nếu profile inactive hoặc activityStatus không ACTIVE")
    void decide_approve_inactiveProfileKeepsCanCoachFalse() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer-inactive@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-inactive", TrainerVerificationStatus.PENDING, false, TrainerActivityStatus.ACTIVE); // isActive=false!
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk());

        // canCoach is still false because profile.isActive is false!
        mockMvc.perform(get("/api/v1/users/me")
                        .with(trainerJwt(trainerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.canCoach", is(false)));
    }

    @Test
    @DisplayName("22. Approve làm canCoach=true khi toàn bộ canonical eligibility conditions đều hợp lệ")
    void decide_approve_fullyValidEligibilityMakesCanCoachTrue() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer-verified@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-verified-true", TrainerVerificationStatus.PENDING, true, TrainerActivityStatus.ACTIVE);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk());

        // canCoach is now TRUE!
        mockMvc.perform(get("/api/v1/users/me")
                        .with(trainerJwt(trainerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.canCoach", is(true)));
    }

    @Test
    @DisplayName("23. Trainer self-service response GET /trainer-applications/me/current không expose reviewNotes")
    void trainerSelfService_doesNotExposeReviewNotes() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer-self@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-self", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        // Admin rejects with review notes
        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"rejectionReason\":\"Public reason\",\"reviewNotes\":\"Confidential admin note\"}")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk());

        // Trainer queries their current application: reviewNotes must NOT be present in response
        mockMvc.perform(get("/api/v1/trainer-applications/me/current")
                        .with(trainerJwt(trainerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.rejectionReason", is("Public reason")))
                .andExpect(jsonPath("$.reviewNotes").doesNotExist());
    }

    @Test
    @DisplayName("24. Verification approval/rejection không tạo Coaching Relationship")
    void decide_doesNotCreateCoachingRelationship() throws Exception {
        UUID adminId = createActiveUser("admin@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer-nocoach@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-nocoach", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.coaching_relationships",
                Integer.class
        );
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("25. Application=PENDING nhưng profile=SUSPENDED thì decision bị 409, rollback toàn bộ")
    void decide_profileSuspended_returns409AndRollsBack() throws Exception {
        UUID adminId = createActiveUser("admin-prof-sus@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer-prof-sus@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-prof-sus", TrainerVerificationStatus.SUSPENDED);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}")
                        .with(adminJwt(adminId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));

        // Verify application status is still PENDING
        String appStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.trainer_applications WHERE id = ?",
                String.class, appId
        );
        assertThat(appStatus).isEqualTo("PENDING");

        // Verify profile status is still SUSPENDED
        String profileStatus = jdbcTemplate.queryForObject(
                "SELECT verification_status::text FROM fitness.trainer_profiles WHERE user_id = ?",
                String.class, trainerId
        );
        assertThat(profileStatus).isEqualTo("SUSPENDED");

        // Verify no history
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_application_status_history WHERE trainer_application_id = ?",
                Integer.class, appId
        );
        assertThat(historyCount).isEqualTo(0);

        // Verify no audit for this application
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE target_id = ?",
                Integer.class, appId
        );
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("26. Concurrency: tương tác đồng thời giữa decision và profile modification bảo toàn tính nhất quán dữ liệu")
    void concurrency_decisionAndProfileModification() throws Exception {
        UUID adminId = createActiveUser("admin-conc-prof@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainerId = createActiveUser("trainer-conc-prof@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "coach-conc-prof", TrainerVerificationStatus.PENDING);
        UUID appId = createApplication(trainerId, TrainerVerificationStatus.PENDING, Instant.now());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicReference<Throwable> decisionError = new AtomicReference<>();
        AtomicReference<Throwable> profileError = new AtomicReference<>();
        AtomicInteger decisionStatus = new AtomicInteger(0);
        AtomicInteger profileRowsUpdated = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/api/v1/admin/trainer-applications/" + appId + "/decisions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"APPROVE\"}")
                                .with(adminJwt(adminId)))
                        .andReturn();
                decisionStatus.set(result.getResponse().getStatus());
            } catch (Throwable t) {
                decisionError.set(t);
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                int rows = jdbcTemplate.update(
                        "UPDATE fitness.trainer_profiles SET verification_status = 'SUSPENDED'::fitness.trainer_verification_state WHERE user_id = ?",
                        trainerId
                );
                profileRowsUpdated.set(rows);
            } catch (Throwable t) {
                profileError.set(t);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Không có unhandled thread exceptions
        assertThat(decisionError.get()).isNull();
        assertThat(profileError.get()).isNull();
        assertThat(profileRowsUpdated.get()).isEqualTo(1);

        // Decision status phải là 200 hoặc 409 tùy theo commit ordering
        assertThat(decisionStatus.get()).isIn(200, 409);

        String finalAppStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.trainer_applications WHERE id = ?",
                String.class, appId
        );
        String finalProfileStatus = jdbcTemplate.queryForObject(
                "SELECT verification_status::text FROM fitness.trainer_profiles WHERE user_id = ?",
                String.class, trainerId
        );
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_application_status_history WHERE trainer_application_id = ?",
                Integer.class, appId
        );
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE target_id = ? AND action LIKE 'TRAINER_APPLICATION_%'",
                Integer.class, appId
        );

        if (decisionStatus.get() == 409) {
            // a. Nếu suspension commit trước: decision=409, application=PENDING, profile=SUSPENDED, không history/audit
            assertThat(finalAppStatus).isEqualTo("PENDING");
            assertThat(finalProfileStatus).isEqualTo("SUSPENDED");
            assertThat(historyCount).isEqualTo(0);
            assertThat(auditCount).isEqualTo(0);
        } else {
            // b. Nếu decision commit trước: decision=200, application=VERIFIED, profile sau đó có thể SUSPENDED, có đúng một decision history/audit
            assertThat(finalAppStatus).isEqualTo("VERIFIED");
            assertThat(finalProfileStatus).isEqualTo("SUSPENDED");
            assertThat(historyCount).isEqualTo(1);
            assertThat(auditCount).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("27. Pagination overflow trả về 400 VALIDATION_FAILED, không lỗi DB 500")
    void paginationOverflow_returns400ValidationFailed() throws Exception {
        UUID adminId = createActiveUser("admin-overflow@example.com");
        assignRole(adminId, "ADMIN");

        mockMvc.perform(get("/api/v1/admin/trainer-applications?page=2147483647&size=20")
                        .with(adminJwt(adminId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        mockMvc.perform(get("/api/v1/admin/trainer-applications?page=999999999999999999999")
                        .with(adminJwt(adminId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("28. Malformed applicationId trong URL trả về 400 VALIDATION_FAILED")
    void malformedApplicationId_returns400() throws Exception {
        UUID adminId = createActiveUser("admin-malformed@example.com");
        assignRole(adminId, "ADMIN");

        mockMvc.perform(get("/api/v1/admin/trainer-applications/not-a-valid-uuid")
                        .with(adminJwt(adminId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("29. Batch loading attachments chính xác cho danh sách applications")
    void batchLoadingAttachments_populatesCorrectly() throws Exception {
        UUID adminId = createActiveUser("admin-batch@example.com");
        assignRole(adminId, "ADMIN");

        UUID trainer1 = createActiveUser("trainer-batch1@example.com");
        assignRole(trainer1, "TRAINER");
        createTrainerProfile(trainer1, "coach-batch1", TrainerVerificationStatus.PENDING);
        UUID app1 = createApplication(trainer1, TrainerVerificationStatus.PENDING, Instant.now().minus(2, ChronoUnit.HOURS));
        UUID cert1 = createCertificate(trainer1, "Cert 1");
        UUID media1 = createMediaFile(trainer1, "doc1.pdf");

        jdbcTemplate.update("INSERT INTO fitness.trainer_application_certificates (trainer_application_id, certificate_id) VALUES (?, ?)", app1, cert1);
        jdbcTemplate.update("INSERT INTO fitness.trainer_verification_documents (trainer_application_id, media_id, document_type) VALUES (?, ?, 'VERIFICATION_DOCUMENT')", app1, media1);

        UUID trainer2 = createActiveUser("trainer-batch2@example.com");
        assignRole(trainer2, "TRAINER");
        createTrainerProfile(trainer2, "coach-batch2", TrainerVerificationStatus.PENDING);
        UUID app2 = createApplication(trainer2, TrainerVerificationStatus.PENDING, Instant.now().minus(1, ChronoUnit.HOURS));
        UUID cert2 = createCertificate(trainer2, "Cert 2");
        UUID media2 = createMediaFile(trainer2, "doc2.pdf");

        jdbcTemplate.update("INSERT INTO fitness.trainer_application_certificates (trainer_application_id, certificate_id) VALUES (?, ?)", app2, cert2);
        jdbcTemplate.update("INSERT INTO fitness.trainer_verification_documents (trainer_application_id, media_id, document_type) VALUES (?, ?, 'VERIFICATION_DOCUMENT')", app2, media2);

        mockMvc.perform(get("/api/v1/admin/trainer-applications")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id", is(app1.toString())))
                .andExpect(jsonPath("$.items[0].certificateIds", hasSize(1)))
                .andExpect(jsonPath("$.items[0].certificateIds[0]", is(cert1.toString())))
                .andExpect(jsonPath("$.items[0].documentMediaIds", hasSize(1)))
                .andExpect(jsonPath("$.items[0].documentMediaIds[0]", is(media1.toString())))
                .andExpect(jsonPath("$.items[1].id", is(app2.toString())))
                .andExpect(jsonPath("$.items[1].certificateIds", hasSize(1)))
                .andExpect(jsonPath("$.items[1].certificateIds[0]", is(cert2.toString())))
                .andExpect(jsonPath("$.items[1].documentMediaIds", hasSize(1)))
                .andExpect(jsonPath("$.items[1].documentMediaIds[0]", is(media2.toString())));
    }
}
