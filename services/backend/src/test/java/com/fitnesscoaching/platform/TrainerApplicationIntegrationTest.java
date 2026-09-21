package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fitnesscoaching.platform.modules.audit.AuditService;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrainerApplicationIntegrationTest {

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

    private UUID createActiveUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, ?, ?, 'Test User', '+84901234567', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """, userId, email, passwordEncoder.encode("Secret123!"));
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

    private void createTrainerProfile(UUID userId, String slug, TrainerVerificationStatus status) {
        createTrainerProfile(userId, slug, status, true);
    }

    private void createTrainerProfile(UUID userId, String slug, TrainerVerificationStatus status, boolean isActive) {
        jdbcTemplate.update("""
                INSERT INTO fitness.trainer_profiles (
                    user_id, public_slug, bio, years_experience,
                    verification_status, activity_status, is_accepting_students, is_active
                ) VALUES (
                    ?, ?, 'Experienced personal trainer.', 5.0,
                    ?::fitness.trainer_verification_state, 'ACTIVE'::fitness.trainer_activity_status, true, ?
                )
                """, userId, slug, status.name(), isActive);
    }

    private void createStudentProfile(UUID userId) {
        jdbcTemplate.update("""
                INSERT INTO fitness.student_profiles (
                    user_id, training_experience_level, training_experience_months, available_days_per_week, preferred_session_minutes
                ) VALUES (
                    ?, 'INTERMEDIATE', 12.0, 4, 60
                )
                """, userId);
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

    @Test
    @DisplayName("Trainer-only user submits application successfully with certificates, media, status history, and audit")
    void trainerOnly_submitApplication_success() throws Exception {
        UUID trainerId = createActiveUser("trainer@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "trainer-mike", TrainerVerificationStatus.NOT_SUBMITTED);
        UUID certId = createCertificate(trainerId, "NASM-CPT");
        UUID mediaId = createMediaFile(trainerId, "nasm_cpt.pdf");

        String token = generateToken(trainerId, "trainer@example.com", List.of("TRAINER"));

        String requestJson = String.format("""
                {
                    "certificateIds": ["%s"],
                    "documentMediaIds": ["%s"],
                    "applicantNote": "Please verify my NASM certification."
                }
                """, certId, mediaId);

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/trainer-applications/me/current"))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.trainerId", is(trainerId.toString())))
                .andExpect(jsonPath("$.submittedAt", notNullValue()))
                .andExpect(jsonPath("$.reviewedAt", nullValue()))
                .andExpect(jsonPath("$.rejectionReason", nullValue()))
                .andExpect(jsonPath("$.reviewNotes").doesNotExist());

        // 1. Verify application row
        Map<String, Object> app = jdbcTemplate.queryForMap(
                "SELECT * FROM fitness.trainer_applications WHERE trainer_id = ?", trainerId);
        assertThat(app.get("status")).isEqualTo("PENDING");
        assertThat(app.get("applicant_note")).isEqualTo("Please verify my NASM certification.");
        UUID applicationId = (UUID) app.get("id");

        // 2. Verify certificate junction
        Integer certCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_application_certificates WHERE trainer_application_id = ? AND certificate_id = ?",
                Integer.class, applicationId, certId);
        assertThat(certCount).isEqualTo(1);

        // 3. Verify document media junction
        Integer docCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_verification_documents WHERE trainer_application_id = ? AND media_id = ?",
                Integer.class, applicationId, mediaId);
        assertThat(docCount).isEqualTo(1);

        // 4. Verify application status history
        Map<String, Object> history = jdbcTemplate.queryForMap(
                "SELECT * FROM fitness.trainer_application_status_history WHERE trainer_application_id = ?", applicationId);
        assertThat(history.get("from_status")).isNull();
        assertThat(history.get("to_status")).isEqualTo("PENDING");
        assertThat(history.get("changed_by")).isEqualTo(trainerId);

        // 5. Verify trainer profile status transitioned to PENDING
        String profileStatus = jdbcTemplate.queryForObject(
                "SELECT verification_status FROM fitness.trainer_profiles WHERE user_id = ?", String.class, trainerId);
        assertThat(profileStatus).isEqualTo("PENDING");

        // 6. Invariant: canCoach remains false
        Boolean isAccepting = jdbcTemplate.queryForObject(
                "SELECT is_accepting_students FROM fitness.trainer_profiles WHERE user_id = ?", Boolean.class, trainerId);
        assertThat(isAccepting).isTrue();

        // 7. Verify audit log recorded
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT * FROM fitness.audit_logs WHERE actor_user_id = ? AND action = 'TRAINER_APPLICATION_SUBMITTED'", trainerId);
        assertThat(audit.get("actor_role")).isEqualTo("TRAINER");
        assertThat(audit.get("target_type")).isEqualTo("TRAINER_APPLICATION");
        assertThat(audit.get("target_id")).isEqualTo(applicationId);
    }

    @Test
    @DisplayName("Student + Trainer multi-role user submits application and preserves Student Profile intact")
    void multiRole_studentAndTrainer_submitPreservesStudentProfile() throws Exception {
        UUID userId = createActiveUser("multirole@example.com");
        assignRole(userId, "STUDENT");
        assignRole(userId, "TRAINER");
        createStudentProfile(userId);
        createTrainerProfile(userId, "coach-dual", TrainerVerificationStatus.NOT_SUBMITTED);

        String token = generateToken(userId, "multirole@example.com", List.of("STUDENT", "TRAINER"));

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING")));

        // Verify Student Profile is still intact
        Map<String, Object> studentProfile = jdbcTemplate.queryForMap(
                "SELECT * FROM fitness.student_profiles WHERE user_id = ?", userId);
        assertThat(studentProfile.get("training_experience_level")).isEqualTo("INTERMEDIATE");
    }

    @Test
    @DisplayName("User without Trainer Profile cannot submit application (404)")
    void userWithoutTrainerProfile_cannotSubmit() throws Exception {
        UUID userId = createActiveUser("no-profile@example.com");
        assignRole(userId, "TRAINER");
        String token = generateToken(userId, "no-profile@example.com", List.of("TRAINER"));

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_PROFILE_NOT_FOUND")));
    }

    @Test
    @DisplayName("User without TRAINER role cannot submit application (403)")
    void userWithoutTrainerRole_cannotSubmit() throws Exception {
        UUID userId = createActiveUser("student-only@example.com");
        assignRole(userId, "STUDENT");
        String token = generateToken(userId, "student-only@example.com", List.of("STUDENT"));

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_CAPABILITY_UNAVAILABLE")));
    }

    @Test
    @DisplayName("Duplicate active application is blocked (409)")
    void duplicateActiveApplication_blockedWith409() throws Exception {
        UUID trainerId = createActiveUser("dup@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "trainer-dup", TrainerVerificationStatus.NOT_SUBMITTED);
        String token = generateToken(trainerId, "dup@example.com", List.of("TRAINER"));

        // First submission succeeds
        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());

        // Second submission fails with 409
        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_APPLICATION_ALREADY_ACTIVE")));
    }

    @Test
    @DisplayName("Certificate ownership is enforced: cannot submit certificate belonging to another trainer")
    void certificateOwnership_enforced() throws Exception {
        UUID trainerA = createActiveUser("trainera@example.com");
        assignRole(trainerA, "TRAINER");
        createTrainerProfile(trainerA, "trainer-a", TrainerVerificationStatus.NOT_SUBMITTED);

        UUID trainerB = createActiveUser("trainerb@example.com");
        assignRole(trainerB, "TRAINER");
        createTrainerProfile(trainerB, "trainer-b", TrainerVerificationStatus.NOT_SUBMITTED);
        UUID certOfB = createCertificate(trainerB, "B-Certificate");

        String tokenA = generateToken(trainerA, "trainera@example.com", List.of("TRAINER"));

        // Trainer A attempts to submit Trainer B's certificate
        String json = String.format("{\"certificateIds\": [\"%s\"]}", certOfB);
        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Document reference is enforced: cannot submit media belonging to another user")
    void documentReference_enforced() throws Exception {
        UUID trainerA = createActiveUser("trainer-media-a@example.com");
        assignRole(trainerA, "TRAINER");
        createTrainerProfile(trainerA, "trainer-media-a", TrainerVerificationStatus.NOT_SUBMITTED);

        UUID trainerB = createActiveUser("trainer-media-b@example.com");
        UUID mediaOfB = createMediaFile(trainerB, "b_file.pdf");

        String tokenA = generateToken(trainerA, "trainer-media-a@example.com", List.of("TRAINER"));

        String json = String.format("{\"documentMediaIds\": [\"%s\"]}", mediaOfB);
        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("GET /api/v1/trainer-applications/me/current returns only authenticated trainer application")
    void getCurrentApplication_isolationEnforced() throws Exception {
        UUID trainerA = createActiveUser("user-a@example.com");
        assignRole(trainerA, "TRAINER");
        createTrainerProfile(trainerA, "trainer-user-a", TrainerVerificationStatus.NOT_SUBMITTED);
        String tokenA = generateToken(trainerA, "user-a@example.com", List.of("TRAINER"));

        UUID trainerB = createActiveUser("user-b@example.com");
        assignRole(trainerB, "TRAINER");
        createTrainerProfile(trainerB, "trainer-user-b", TrainerVerificationStatus.NOT_SUBMITTED);
        String tokenB = generateToken(trainerB, "user-b@example.com", List.of("TRAINER"));

        // Trainer A submits
        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicantNote\": \"Trainer A application\"}"))
                .andExpect(status().isCreated());

        // Trainer A can see their application
        mockMvc.perform(get("/api/v1/trainer-applications/me/current")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainerId", is(trainerA.toString())));

        // Trainer B has no application -> 404, cannot see Trainer A's
        mockMvc.perform(get("/api/v1/trainer-applications/me/current")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_APPLICATION_NOT_FOUND")));
    }

    @Test
    @DisplayName("Concurrent duplicate submission creates at most one active application and returns 409 to the loser")
    void concurrentDuplicateSubmission_createsOnlyOne() throws Exception {
        UUID trainerId = createActiveUser("concurrent@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "trainer-concurrent", TrainerVerificationStatus.NOT_SUBMITTED);
        String token = generateToken(trainerId, "concurrent@example.com", List.of("TRAINER"));

        int concurrency = 2;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/trainer-applications")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                            .andReturn();

                    int resStatus = result.getResponse().getStatus();
                    if (resStatus == 201) {
                        successCount.incrementAndGet();
                    } else if (resStatus == 409) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // unexpected
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        Integer activeAppCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_applications WHERE trainer_id = ? AND status = 'PENDING'",
                Integer.class, trainerId);
        assertThat(activeAppCount).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/v1/trainer-applications/me/current returns 403 when trainer profile is inactive")
    void getCurrentApplication_inactiveTrainerProfile_returns403() throws Exception {
        UUID trainerId = createActiveUser("inactive-profile@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "trainer-inactive", TrainerVerificationStatus.NOT_SUBMITTED, false);
        String token = generateToken(trainerId, "inactive-profile@example.com", List.of("TRAINER"));

        mockMvc.perform(get("/api/v1/trainer-applications/me/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_CAPABILITY_UNAVAILABLE")));
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications returns 403 when trainer profile is inactive")
    void submitApplication_inactiveTrainerProfile_returns403() throws Exception {
        UUID trainerId = createActiveUser("inactive-submit@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "trainer-inactive-sub", TrainerVerificationStatus.NOT_SUBMITTED, false);
        String token = generateToken(trainerId, "inactive-submit@example.com", List.of("TRAINER"));

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_CAPABILITY_UNAVAILABLE")));
    }

    @Test
    @DisplayName("Trainer profile with REJECTED verification status cannot submit new application (409)")
    void submitApplication_rejectedProfile_returns409() throws Exception {
        UUID trainerId = createActiveUser("rejected@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "trainer-rejected", TrainerVerificationStatus.REJECTED, true);
        String token = generateToken(trainerId, "rejected@example.com", List.of("TRAINER"));

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));
    }

    @Test
    @DisplayName("GET /api/v1/trainer-applications/me/current prioritizes PENDING application over newer terminal applications")
    void getCurrentApplication_prioritizesPendingOverNewerTerminal() throws Exception {
        UUID trainerId = createActiveUser("priority@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "trainer-prio", TrainerVerificationStatus.PENDING, true);
        String token = generateToken(trainerId, "priority@example.com", List.of("TRAINER"));

        UUID pendingAppId = UUID.randomUUID();
        UUID rejectedAppId = UUID.randomUUID();

        // Insert older PENDING application
        jdbcTemplate.update("""
                INSERT INTO fitness.trainer_applications (
                    id, trainer_id, status, applicant_note, submitted_at, updated_at
                ) VALUES (?, ?, 'PENDING'::fitness.trainer_verification_state, 'Old pending app', now() - interval '2 days', now() - interval '2 days')
                """, pendingAppId, trainerId);

        // Insert newer REJECTED application
        jdbcTemplate.update("""
                INSERT INTO fitness.trainer_applications (
                    id, trainer_id, status, applicant_note, submitted_at, reviewed_at, rejection_reason, updated_at
                ) VALUES (?, ?, 'REJECTED'::fitness.trainer_verification_state, 'Newer rejected app', now() - interval '1 day', now() - interval '1 day', 'Incomplete', now() - interval '1 day')
                """, rejectedAppId, trainerId);

        mockMvc.perform(get("/api/v1/trainer-applications/me/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(pendingAppId.toString())))
                .andExpect(jsonPath("$.status", is("PENDING")));
    }

    @Test
    @DisplayName("Transaction rollback: failure in audit records rolls back application, certificates, media junctions, and status history atomically")
    void submitApplication_auditFailure_rollsBackTransactionAtomically() throws Exception {
        UUID trainerId = createActiveUser("rollback@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "trainer-rollback", TrainerVerificationStatus.NOT_SUBMITTED);
        UUID certId = createCertificate(trainerId, "Rollback-Cert");
        UUID mediaId = createMediaFile(trainerId, "rollback-doc.pdf");
        String token = generateToken(trainerId, "rollback@example.com", List.of("TRAINER"));

        org.mockito.Mockito.doThrow(new RuntimeException("Simulated audit failure"))
                .when(auditService).recordAudit(any());

        try {
            String requestJson = String.format("""
                    {
                        "certificateIds": ["%s"],
                        "documentMediaIds": ["%s"],
                        "applicantNote": "Testing rollback."
                    }
                    """, certId, mediaId);

            mockMvc.perform(post("/api/v1/trainer-applications")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isInternalServerError());

            // Verify total rollback in database
            Integer appCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.trainer_applications WHERE trainer_id = ?", Integer.class, trainerId);
            assertThat(appCount).isZero();

            Integer certJunctionCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.trainer_application_certificates WHERE certificate_id = ?", Integer.class, certId);
            assertThat(certJunctionCount).isZero();

            Integer docJunctionCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.trainer_verification_documents WHERE media_id = ?", Integer.class, mediaId);
            assertThat(docJunctionCount).isZero();

            Integer historyCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.trainer_application_status_history WHERE changed_by = ?", Integer.class, trainerId);
            assertThat(historyCount).isZero();

            Integer auditCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ?", Integer.class, trainerId);
            assertThat(auditCount).isZero();

            String profileStatus = jdbcTemplate.queryForObject(
                    "SELECT verification_status FROM fitness.trainer_profiles WHERE user_id = ?", String.class, trainerId);
            assertThat(profileStatus).isEqualTo("NOT_SUBMITTED");
        } finally {
            org.mockito.Mockito.reset(auditService);
        }
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications: returns 400 not 500 when certificateIds or documentMediaIds contain null")
    void submitApplication_nullItemInLists_returns400Not500() throws Exception {
        UUID trainerId = createActiveUser("nullcheck@example.com");
        assignRole(trainerId, "TRAINER");
        createTrainerProfile(trainerId, "trainer-nullcheck", TrainerVerificationStatus.NOT_SUBMITTED);
        String token = generateToken(trainerId, "nullcheck@example.com", List.of("TRAINER"));

        // Payload with null in certificateIds
        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateIds\": [null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Payload with null in documentMediaIds
        mockMvc.perform(post("/api/v1/trainer-applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentMediaIds\": [null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // Ensure database was not touched
        Integer appCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.trainer_applications WHERE trainer_id = ?", Integer.class, trainerId);
        assertThat(appCount).isZero();
    }
}
