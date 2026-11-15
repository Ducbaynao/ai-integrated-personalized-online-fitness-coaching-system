package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.common.exception.ScannerUnavailableException;
import com.fitnesscoaching.platform.common.exception.StorageUnavailableException;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.CorrectMeasurementRequest;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.RecordMeasurementRequest;
import com.fitnesscoaching.platform.modules.measurement.application.service.ProgressPhotoStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeasurementApiIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("measurement_api_test_db")
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
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @MockitoSpyBean
    private ProgressPhotoStorageService photoStorageService;

    @MockitoSpyBean
    private AuditService auditService;

    private UUID studentId;
    private UUID otherStudentId;
    private UUID trainerId;
    private String studentToken;
    private String otherStudentToken;
    private String trainerToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE fitness.measurement_validation_events, fitness.progress_photos, " +
                "fitness.measurements, fitness.media_files, fitness.goal_targets, fitness.fitness_goal_versions, " +
                "fitness.fitness_goals, fitness.coaching_relationships, fitness.student_profiles, " +
                "fitness.trainer_profiles, fitness.user_roles, fitness.users CASCADE");

        // Seed basic metric definitions & units if not present (from V56)
        jdbcTemplate.execute("""
                INSERT INTO fitness.measurement_units(code, symbol, dimension, base_unit_code, multiplier_to_base, offset_to_base)
                VALUES ('KG', 'kg', 'MASS', 'KG', 1, 0),
                       ('LB', 'lb', 'MASS', 'KG', 0.45359237, 0),
                       ('CM', 'cm', 'LENGTH', 'M', 0.01, 0),
                       ('M', 'm', 'LENGTH', 'M', 1, 0),
                       ('PERCENT', '%', 'RATIO', 'PERCENT', 1, 0),
                       ('SCORE', 'score', 'SCORE', 'SCORE', 1, 0)
                ON CONFLICT (code) DO NOTHING;
                """);

        jdbcTemplate.execute("""
                INSERT INTO fitness.metric_definitions(code, display_name, value_type, collection_type, default_unit_id, valid_min, valid_max, stale_after_days, is_active)
                SELECT 'WEIGHT', 'Weight', 'NUMERIC'::fitness.measurement_value_type, 'DIRECT'::fitness.metric_collection_type, u.id, 20::numeric, 500::numeric, 14, true
                FROM fitness.measurement_units u WHERE u.code = 'KG'
                ON CONFLICT (code) DO NOTHING;
                """);

        jdbcTemplate.execute("""
                INSERT INTO fitness.metric_definitions(code, display_name, value_type, collection_type, default_unit_id, valid_min, valid_max, stale_after_days, is_active)
                SELECT 'HEIGHT', 'Height', 'NUMERIC'::fitness.measurement_value_type, 'DIRECT'::fitness.metric_collection_type, u.id, 50::numeric, 260::numeric, 365, true
                FROM fitness.measurement_units u WHERE u.code = 'CM'
                ON CONFLICT (code) DO NOTHING;
                """);

        jdbcTemplate.execute("""
                INSERT INTO fitness.measurement_sources(code, name, source_kind, is_active)
                VALUES ('MANUAL_STUDENT', 'Student manual entry', 'MANUAL', true),
                       ('MANUAL_TRAINER', 'Trainer measurement', 'TRAINER', true)
                ON CONFLICT (code) DO NOTHING;
                """);

        studentId = createActiveStudent("student@example.com");
        otherStudentId = createActiveStudent("other_student@example.com");
        trainerId = createActiveTrainer("trainer@example.com");

        studentToken = generateToken(studentId, "student@example.com", List.of("STUDENT"));
        otherStudentToken = generateToken(otherStudentId, "other_student@example.com", List.of("STUDENT"));
        trainerToken = generateToken(trainerId, "trainer@example.com", List.of("TRAINER"));
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

    private UUID createActiveStudent(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, ?, 'hash', 'Test Student', '+84901234567', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """, userId, email);
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now() FROM fitness.roles r WHERE r.code = 'STUDENT'
                """, userId, userId);
        jdbcTemplate.update("INSERT INTO fitness.student_profiles(user_id, created_at) VALUES (?, now() - interval '1 year')", userId);
        return userId;
    }

    private UUID createActiveTrainer(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, ?, 'hash', 'Test Trainer', '+84901234568', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """, userId, email);
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now() FROM fitness.roles r WHERE r.code = 'TRAINER'
                """, userId, userId);
        jdbcTemplate.update("""
                INSERT INTO fitness.trainer_profiles(user_id, public_slug, verification_status, activity_status, is_active)
                VALUES (?, ?, 'VERIFIED'::fitness.trainer_verification_state, 'ACTIVE'::fitness.trainer_activity_status, true)
                """, userId, "trainer-" + userId.toString().substring(0, 8));
        return userId;
    }

    private void grantCoachingAccess(UUID trainerId, UUID studentId, String dataScope, String accessLevel, Instant historyFrom, Instant historyUntil) {
        UUID relId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.coaching_relationships(id, trainer_id, student_id, status, requested_by, requested_at, accepted_at, started_at, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE'::fitness.coaching_relationship_status, ?, now(), now(), now(), now(), now())
                ON CONFLICT (trainer_id, student_id) WHERE status IN ('PENDING', 'ACTIVE', 'PAUSED')
                DO UPDATE SET status = 'ACTIVE'::fitness.coaching_relationship_status
                """, relId, trainerId, studentId, studentId);

        UUID effectiveRelId = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.coaching_relationships WHERE trainer_id = ? AND student_id = ? AND status = 'ACTIVE'",
                UUID.class, trainerId, studentId);

        Integer activePeriodCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.coaching_periods WHERE student_id = ? AND ended_at IS NULL",
                Integer.class, studentId);

        if (activePeriodCount == null || activePeriodCount == 0) {
            jdbcTemplate.update("""
                    INSERT INTO fitness.coaching_periods(id, student_id, mode, coaching_relationship_id, trainer_id, started_at, created_by, created_at)
                    VALUES (gen_random_uuid(), ?, 'HUMAN_COACH'::fitness.coaching_mode, ?, ?, now() - interval '1 hour', ?, now() - interval '1 hour')
                    """, studentId, effectiveRelId, trainerId, studentId);
        }

        jdbcTemplate.update("""
                INSERT INTO fitness.data_sharing_permissions(id, relationship_id, student_id, trainer_id, data_scope, decision, access_level, history_from, history_until, valid_from, granted_by, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?::fitness.data_scope_code, 'ALLOW'::fitness.permission_decision, ?::fitness.data_access_level, ?, ?, now(), ?, now(), now())
                """, effectiveRelId, studentId, trainerId, dataScope, accessLevel,
                historyFrom != null ? Timestamp.from(historyFrom) : null,
                historyUntil != null ? Timestamp.from(historyUntil) : null,
                studentId);
    }

    // --- Metric Catalog Tests ---

    @Test
    @DisplayName("GET /api/v1/metrics returns active metric definitions with unit details")
    void getMetrics_returnsActiveMetrics() throws Exception {
        mockMvc.perform(get("/api/v1/metrics")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$[?(@.code == 'WEIGHT')].displayName").value("Weight"))
                .andExpect(jsonPath("$[?(@.code == 'WEIGHT')].defaultUnitCode").value("KG"))
                .andExpect(jsonPath("$[?(@.code == 'WEIGHT')].dimension").value("MASS"));
    }

    // --- Record Measurement Tests ---

    @Test
    @DisplayName("POST /api/v1/measurements allows student to record valid numeric measurement")
    void recordMeasurement_asStudent_success() throws Exception {
        RecordMeasurementRequest request = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("75.50"), null, null, "KG",
                Instant.now().minusSeconds(60), null, "Morning weigh-in"
        );

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.studentId").value(studentId.toString()))
                .andExpect(jsonPath("$.metricCode").value("WEIGHT"))
                .andExpect(jsonPath("$.numericValue").value(75.50))
                .andExpect(jsonPath("$.unitCode").value("KG"))
                .andExpect(jsonPath("$.validationStatus").value("VALID"))
                .andExpect(jsonPath("$.notes").value("Morning weigh-in"));
    }

    @Test
    @DisplayName("POST /api/v1/measurements assigns SUSPECT status via DB trigger when value outside valid range")
    void recordMeasurement_outOfRange_triggersSuspectStatus() throws Exception {
        // Valid max is 500. Submitting 600 kg.
        RecordMeasurementRequest request = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("600.00"), null, null, "KG",
                Instant.now().minusSeconds(60), null, "Suspect entry"
        );

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validationStatus").value("SUSPECT"));
    }

    @Test
    @DisplayName("POST /api/v1/measurements rejects incompatible unit dimension (LENGTH for MASS metric)")
    void recordMeasurement_incompatibleUnitDimension_rejected() throws Exception {
        RecordMeasurementRequest request = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("75.50"), null, null, "CM",
                Instant.now().minusSeconds(60), null, "Invalid unit dimension"
        );

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNIT_INCOMPATIBLE"));
    }

    @Test
    @DisplayName("POST /api/v1/measurements rejects future timestamp")
    void recordMeasurement_futureTime_rejected() throws Exception {
        RecordMeasurementRequest request = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("75.50"), null, null, "KG",
                Instant.now().plus(1, ChronoUnit.DAYS), null, "Future time"
        );

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/measurements rejects recording for other student without trainer scope")
    void recordMeasurement_otherStudent_denied() throws Exception {
        RecordMeasurementRequest request = new RecordMeasurementRequest(
                otherStudentId, "WEIGHT", new BigDecimal("75.50"), null, null, "KG",
                Instant.now().minusSeconds(60), null, "Unsolicited entry"
        );

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MEASUREMENT_ACCESS_DENIED"));
    }

    // --- Measurement Correction Lifecycle Tests ---

    @Test
    @DisplayName("POST /api/v1/measurements/{id}/correction creates superseded history without overwriting old record")
    void correctMeasurement_createsSupersededHistory() throws Exception {
        // 1. Record original measurement
        RecordMeasurementRequest recordReq = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("80.00"), null, null, "KG",
                Instant.now().minusSeconds(3600), null, "Original entry"
        );

        String origJson = mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(recordReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID originalId = UUID.fromString(objectMapper.readTree(origJson).get("id").asText());

        // 2. Correct measurement with reason
        CorrectMeasurementRequest correctReq = new CorrectMeasurementRequest(
                new BigDecimal("78.50"), null, null, "KG", null,
                "Typo in scale reading", "Corrected to 78.5"
        );

        String correctJson = mockMvc.perform(post("/api/v1/measurements/" + originalId + "/correction")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.numericValue").value(78.50))
                .andReturn().getResponse().getContentAsString();

        UUID newId = UUID.fromString(objectMapper.readTree(correctJson).get("id").asText());
        assertThat(newId).isNotEqualTo(originalId);

        // 3. Verify original record has superseded_by_id pointing to new record AND status is SUPERSEDED
        UUID supersededInDb = jdbcTemplate.queryForObject(
                "SELECT superseded_by_id FROM fitness.measurements WHERE id = ?",
                UUID.class, originalId
        );
        assertThat(supersededInDb).isEqualTo(newId);

        String origStatus = jdbcTemplate.queryForObject(
                "SELECT validation_status::text FROM fitness.measurements WHERE id = ?",
                String.class, originalId
        );
        assertThat(origStatus).isEqualTo("SUPERSEDED");

        // 4. Verify validation event was recorded
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.measurement_validation_events WHERE measurement_id = ? AND rule_code = 'USER_CORRECTION'",
                Integer.class, newId
        );
        assertThat(eventCount).isEqualTo(1);

        // 5. Verify audit log entry was created
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.audit_logs WHERE action = 'MEASUREMENT_CORRECTED' AND target_type = 'MEASUREMENT' AND target_id = ?::uuid",
                Integer.class, originalId.toString()
        );
        assertThat(auditCount).isGreaterThanOrEqualTo(1);

        // 6. Attempting to correct already superseded record returns 409 CONFLICT
        mockMvc.perform(post("/api/v1/measurements/" + originalId + "/correction")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MEASUREMENT_CORRECTION_CONFLICT"));
    }

    // --- Measurement History Query Tests ---

    @Test
    @DisplayName("GET /api/v1/measurements supports filtering by metric and pagination")
    void getMeasurements_paginationAndFilter() throws Exception {
        Instant t1 = Instant.now().minusSeconds(7200);
        Instant t2 = Instant.now().minusSeconds(3600);

        RecordMeasurementRequest m1 = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("75.00"), null, null, "KG", t1, null, "Entry 1"
        );
        RecordMeasurementRequest m2 = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("74.80"), null, null, "KG", t2, null, "Entry 2"
        );

        mockMvc.perform(post("/api/v1/measurements")
                .header("Authorization", "Bearer " + studentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(m1)));

        mockMvc.perform(post("/api/v1/measurements")
                .header("Authorization", "Bearer " + studentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(m2)));

        mockMvc.perform(get("/api/v1/measurements")
                        .header("Authorization", "Bearer " + studentToken)
                        .param("metricCode", "WEIGHT")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].numericValue").value(74.80))
                .andExpect(jsonPath("$.content[1].numericValue").value(75.00));
    }

    // --- Progress Photos Tests ---

    @Test
    @DisplayName("POST /api/v1/measurements/progress-photos uploads valid PNG photo")
    void uploadProgressPhoto_validPng_success() throws Exception {
        // Valid 1x1 PNG header bytes
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "front.png", "image/png", pngBytes);

        // Mock scanner and storage so integration test passes without running clamd/S3 daemons
        doReturn("CLEAN").when(photoStorageService).scanAndVerifyClean(any());
        doAnswer(invocation -> null).when(photoStorageService).storeFile(any(), any(), any());

        mockMvc.perform(multipart("/api/v1/measurements/progress-photos")
                        .file(file)
                        .header("Authorization", "Bearer " + studentToken)
                        .param("viewType", "FRONT")
                        .param("weightAtTime", "75.0")
                        .param("weightUnitCode", "KG")
                        .param("notes", "Front pose"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.studentId").value(studentId.toString()))
                .andExpect(jsonPath("$.viewType").value("FRONT"))
                .andExpect(jsonPath("$.weightAtTime").value(75.0))
                .andExpect(jsonPath("$.weightUnitCode").value("KG"));

        mockMvc.perform(get("/api/v1/measurements/progress-photos")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].weightAtTime").value(75.0))
                .andExpect(jsonPath("$.content[0].weightUnitId", notNullValue()))
                .andExpect(jsonPath("$.content[0].weightUnitCode").value("KG"));
    }

    @Test
    @DisplayName("POST /api/v1/measurements/progress-photos rejects unsupported file type")
    void uploadProgressPhoto_unsupportedType_rejected() throws Exception {
        byte[] textBytes = "Just plain text, not an image".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", textBytes);

        mockMvc.perform(multipart("/api/v1/measurements/progress-photos")
                        .file(file)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    // --- Progress Dashboard Tests ---

    @Test
    @DisplayName("GET /api/v1/progress/summary works for CURRENT_GOAL and LIFETIME independently from AI")
    void getProgressSummary_independentFromAi() throws Exception {
        // Create an active fitness goal for the student
        UUID goalId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(14);
        LocalDate targetDate = today.plusDays(45);

        jdbcTemplate.update("""
                INSERT INTO fitness.fitness_goals(id, student_id, title, status, created_by, created_at, updated_at)
                VALUES (?, ?, 'Fat Loss Goal', 'DRAFT'::fitness.lifecycle_status, ?, now(), now())
                """, goalId, studentId, studentId);

        jdbcTemplate.update("""
                INSERT INTO fitness.fitness_goal_versions(id, fitness_goal_id, version_number, title, start_date, target_date, duration_days, created_by, locked_at, lock_reason, created_at)
                VALUES (?, ?, 1, 'Fat Loss Goal v1', ?, ?, 60, ?, now(), 'ACTIVATED'::fitness.version_lock_reason, now())
                """, versionId, goalId, startDate, targetDate, studentId);

        jdbcTemplate.update("""
                UPDATE fitness.fitness_goals
                SET status = 'ACTIVE'::fitness.lifecycle_status, activated_at = now()
                WHERE id = ?
                """, goalId);

        // Add 2 measurements in the period to form a trend
        RecordMeasurementRequest m1 = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("82.00"), null, null, "KG",
                startDate.atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(), null, "Baseline"
        );
        RecordMeasurementRequest m2 = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("79.50"), null, null, "KG",
                today.minusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(), null, "Latest"
        );

        mockMvc.perform(post("/api/v1/measurements")
                .header("Authorization", "Bearer " + studentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(m1)));

        mockMvc.perform(post("/api/v1/measurements")
                .header("Authorization", "Bearer " + studentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(m2)));

        // 1. Query CURRENT_GOAL
        mockMvc.perform(get("/api/v1/progress/summary")
                        .header("Authorization", "Bearer " + studentToken)
                        .param("scope", "CURRENT_GOAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("CURRENT_GOAL"))
                .andExpect(jsonPath("$.goal.id").value(goalId.toString()))
                .andExpect(jsonPath("$.goal.title").value("Fat Loss Goal"))
                .andExpect(jsonPath("$.measurement.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.measurement.metricDeltas", hasSize(1)))
                .andExpect(jsonPath("$.measurement.metricDeltas[0].metricCode").value("WEIGHT"))
                .andExpect(jsonPath("$.measurement.metricDeltas[0].baselineValue").value(82.00))
                .andExpect(jsonPath("$.measurement.metricDeltas[0].latestValue").value(79.50))
                .andExpect(jsonPath("$.measurement.metricDeltas[0].trendDirection").value("DECREASED"));

        // 2. Query LIFETIME
        mockMvc.perform(get("/api/v1/progress/summary")
                        .header("Authorization", "Bearer " + studentToken)
                        .param("scope", "LIFETIME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("LIFETIME"))
                .andExpect(jsonPath("$.goal").value(nullValue()))
                .andExpect(jsonPath("$.measurement.availability").value("AVAILABLE"));
    }

    // --- Trainer Access & Permissions Integration Tests ---

    @Test
    @DisplayName("POST /api/v1/measurements allows trainer to record when BODY_METRICS CONTRIBUTE granted")
    void recordMeasurement_asTrainer_withContributeScope_success() throws Exception {
        grantCoachingAccess(trainerId, studentId, "BODY_METRICS", "CONTRIBUTE", null, null);

        RecordMeasurementRequest request = new RecordMeasurementRequest(
                studentId, "WEIGHT", new BigDecimal("76.00"), null, null, "KG",
                Instant.now().minusSeconds(60), null, "Trainer assisted check-in"
        );

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.studentId").value(studentId.toString()))
                .andExpect(jsonPath("$.numericValue").value(76.00))
                .andExpect(jsonPath("$.sourceCode").value("MANUAL_TRAINER"));
    }

    @Test
    @DisplayName("GET /api/v1/measurements allows trainer with VIEW scope, denies trainer without scope")
    void getMeasurements_trainerPermissions() throws Exception {
        // Record student measurement
        RecordMeasurementRequest request = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("75.00"), null, null, "KG",
                Instant.now().minusSeconds(60), null, "Student entry"
        );
        mockMvc.perform(post("/api/v1/measurements")
                .header("Authorization", "Bearer " + studentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // 1. Without permission -> 403
        mockMvc.perform(get("/api/v1/measurements")
                        .header("Authorization", "Bearer " + trainerToken)
                        .param("studentId", studentId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MEASUREMENT_ACCESS_DENIED"));

        // 2. Grant VIEW permission -> 200
        grantCoachingAccess(trainerId, studentId, "BODY_METRICS", "VIEW", null, null);

        mockMvc.perform(get("/api/v1/measurements")
                        .header("Authorization", "Bearer " + trainerToken)
                        .param("studentId", studentId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/measurements/progress-photos/{id}/file enforces sharing window boundaries and cross-student denial")
    void getPhotoFile_sharingWindowAndPrivacyRules() throws Exception {
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00};
        MockMultipartFile file1 = new MockMultipartFile("file", "old.png", "image/png", pngBytes);
        MockMultipartFile file2 = new MockMultipartFile("file", "recent.png", "image/png", pngBytes);

        doReturn("CLEAN").when(photoStorageService).scanAndVerifyClean(any());
        doAnswer(invocation -> null).when(photoStorageService).storeFile(any(), any(), any());
        doReturn(pngBytes).when(photoStorageService).loadFile(any());

        Instant photoTimeOld = Instant.now().minus(5, ChronoUnit.DAYS);
        Instant photoTimeRecent = Instant.now().minus(1, ChronoUnit.DAYS);

        String uploadOldJson = mockMvc.perform(multipart("/api/v1/measurements/progress-photos")
                        .file(file1)
                        .header("Authorization", "Bearer " + studentToken)
                        .param("viewType", "FRONT")
                        .param("takenAt", photoTimeOld.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID photoOldId = UUID.fromString(objectMapper.readTree(uploadOldJson).get("id").asText());

        String uploadRecentJson = mockMvc.perform(multipart("/api/v1/measurements/progress-photos")
                        .file(file2)
                        .header("Authorization", "Bearer " + studentToken)
                        .param("viewType", "FRONT")
                        .param("takenAt", photoTimeRecent.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID photoRecentId = UUID.fromString(objectMapper.readTree(uploadRecentJson).get("id").asText());

        // 1. Unrelated student -> 403 MEASUREMENT_ACCESS_DENIED
        mockMvc.perform(get("/api/v1/measurements/progress-photos/" + photoRecentId + "/file")
                        .header("Authorization", "Bearer " + otherStudentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MEASUREMENT_ACCESS_DENIED"));

        // 2. Trainer without PROGRESS_PHOTOS scope -> 403 MEASUREMENT_ACCESS_DENIED
        mockMvc.perform(get("/api/v1/measurements/progress-photos/" + photoRecentId + "/file")
                        .header("Authorization", "Bearer " + trainerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MEASUREMENT_ACCESS_DENIED"));

        // 3. Grant trainer PROGRESS_PHOTOS scope with historyFrom = 2 days ago
        grantCoachingAccess(trainerId, studentId, "PROGRESS_PHOTOS", "VIEW",
                Instant.now().minus(2, ChronoUnit.DAYS), Instant.now());

        // Photo taken 5 days ago is before window -> 403 MEASUREMENT_ACCESS_DENIED
        mockMvc.perform(get("/api/v1/measurements/progress-photos/" + photoOldId + "/file")
                        .header("Authorization", "Bearer " + trainerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MEASUREMENT_ACCESS_DENIED"));

        // Photo taken 1 day ago is within window -> 200 OK
        mockMvc.perform(get("/api/v1/measurements/progress-photos/" + photoRecentId + "/file")
                        .header("Authorization", "Bearer " + trainerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    @DisplayName("GET /api/v1/progress/summary privacy: unrelated student denied, trainer without FITNESS_GOAL scope omits goal")
    void getProgressSummary_privacyAndScopeSeparation() throws Exception {
        UUID goalId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        jdbcTemplate.update("""
                INSERT INTO fitness.fitness_goals(id, student_id, title, status, created_by, created_at, updated_at)
                VALUES (?, ?, 'Trainer Visible Goal', 'DRAFT'::fitness.lifecycle_status, ?, now(), now())
                """, goalId, studentId, studentId);

        jdbcTemplate.update("""
                INSERT INTO fitness.fitness_goal_versions(id, fitness_goal_id, version_number, title, start_date, target_date, duration_days, created_by, locked_at, lock_reason, created_at)
                VALUES (?, ?, 1, 'v1', ?, ?, 30, ?, now(), 'ACTIVATED'::fitness.version_lock_reason, now())
                """, versionId, goalId, today.minusDays(5), today.plusDays(25), studentId);

        jdbcTemplate.update("""
                UPDATE fitness.fitness_goals
                SET status = 'ACTIVE'::fitness.lifecycle_status, activated_at = now()
                WHERE id = ?
                """, goalId);

        // 1. Unrelated student -> 403
        mockMvc.perform(get("/api/v1/progress/summary")
                        .header("Authorization", "Bearer " + otherStudentToken)
                        .param("studentId", studentId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PROGRESS_ACCESS_DENIED"));

        // 2. Trainer with BODY_METRICS only -> 200, but goal is NULL
        grantCoachingAccess(trainerId, studentId, "BODY_METRICS", "VIEW", null, null);

        mockMvc.perform(get("/api/v1/progress/summary")
                        .header("Authorization", "Bearer " + trainerToken)
                        .param("studentId", studentId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal").value(nullValue()));

        // 3. Trainer also granted FITNESS_GOAL scope -> 200 and goal is PRESENT
        grantCoachingAccess(trainerId, studentId, "FITNESS_GOAL", "VIEW", null, null);

        mockMvc.perform(get("/api/v1/progress/summary")
                        .header("Authorization", "Bearer " + trainerToken)
                        .param("studentId", studentId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal.id").value(goalId.toString()))
                .andExpect(jsonPath("$.goal.title").value("Trainer Visible Goal"));
    }

    // --- Concurrent Correction & Audit Rollback Tests ---

    @Test
    @DisplayName("Concurrent correction: exactly one succeeds with 200, concurrent attempt gets 409, no split-brain")
    void concurrentCorrection_onlyOneWins_andNoOrphansOrSplitBrain() throws Exception {
        RecordMeasurementRequest recordReq = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("80.00"), null, null, "KG",
                Instant.now().minusSeconds(3600), null, "Concurrent test target"
        );
        String origJson = mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(recordReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID originalId = UUID.fromString(objectMapper.readTree(origJson).get("id").asText());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        java.util.concurrent.Callable<Integer> task1 = () -> {
            readyLatch.countDown();
            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.await(5, TimeUnit.SECONDS);
            CorrectMeasurementRequest req1 = new CorrectMeasurementRequest(
                    new BigDecimal("79.00"), null, null, "KG", null,
                    "Correction attempt 1", "Updated to 79.0"
            );
            return mockMvc.perform(post("/api/v1/measurements/" + originalId + "/correction")
                            .header("Authorization", "Bearer " + studentToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req1)))
                    .andReturn().getResponse().getStatus();
        };

        java.util.concurrent.Callable<Integer> task2 = () -> {
            readyLatch.countDown();
            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.await(5, TimeUnit.SECONDS);
            CorrectMeasurementRequest req2 = new CorrectMeasurementRequest(
                    new BigDecimal("78.00"), null, null, "KG", null,
                    "Correction attempt 2", "Updated to 78.0"
            );
            return mockMvc.perform(post("/api/v1/measurements/" + originalId + "/correction")
                            .header("Authorization", "Bearer " + studentToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req2)))
                    .andReturn().getResponse().getStatus();
        };

        Future<Integer> f1 = executor.submit(task1);
        Future<Integer> f2 = executor.submit(task2);

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int s1 = f1.get(10, TimeUnit.SECONDS);
        int s2 = f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(List.of(s1, s2)).containsExactlyInAnyOrder(200, 409);

        // Verify exactly one active successor measurement exists
        Integer validActiveCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.measurements WHERE student_id = ? AND validation_status != 'SUPERSEDED'::fitness.measurement_validation_status AND erased_at IS NULL",
                Integer.class, studentId
        );
        assertThat(validActiveCount).isEqualTo(1);

        // Verify original measurement has superseded_by_id pointing to the winner
        UUID supersededInDb = jdbcTemplate.queryForObject(
                "SELECT superseded_by_id FROM fitness.measurements WHERE id = ?",
                UUID.class, originalId
        );
        assertThat(supersededInDb).isNotNull();

        // Verify audit log has exactly one MEASUREMENT_CORRECTED entry
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.audit_logs WHERE action = 'MEASUREMENT_CORRECTED' AND target_id = ?::uuid",
                Integer.class, originalId.toString()
        );
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Audit failure triggers transaction rollback, preserving original measurement status")
    void auditFailure_rollsBackCorrectionTransaction_andPreservesOriginalRecord() throws Exception {
        RecordMeasurementRequest recordReq = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("85.00"), null, null, "KG",
                Instant.now().minusSeconds(1800), null, "Audit rollback test"
        );
        String origJson = mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(recordReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID originalId = UUID.fromString(objectMapper.readTree(origJson).get("id").asText());

        // Configure auditService spy to throw exception
        doThrow(new RuntimeException("Simulated audit disk IO failure"))
                .when(auditService).recordAudit(any());

        CorrectMeasurementRequest req = new CorrectMeasurementRequest(
                new BigDecimal("84.00"), null, null, "KG", null,
                "Reason for rollback test", "Notes"
        );

        mockMvc.perform(post("/api/v1/measurements/" + originalId + "/correction")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // Verify original measurement row is UNTOUCHED
        String status = jdbcTemplate.queryForObject(
                "SELECT validation_status::text FROM fitness.measurements WHERE id = ?",
                String.class, originalId
        );
        assertThat(status).isEqualTo("VALID");

        UUID supersededById = jdbcTemplate.queryForObject(
                "SELECT superseded_by_id FROM fitness.measurements WHERE id = ?",
                UUID.class, originalId
        );
        assertThat(supersededById).isNull();

        // Verify no orphan measurement was persisted
        Integer totalMeasurements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.measurements WHERE student_id = ?",
                Integer.class, studentId
        );
        assertThat(totalMeasurements).isEqualTo(1);
    }

    // --- Lifetime Scope & Historical Continuity Tests ---

    @Test
    @DisplayName("LIFETIME scope includes historical measurements entered with timestamps predating profile creation")
    void lifetimeProgress_includesHistoricalMeasurementsPredatingProfileCreation() throws Exception {
        Instant pastObservationTime = Instant.now().minus(180, ChronoUnit.DAYS);

        RecordMeasurementRequest historicalRecord = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("90.00"), null, null, "KG",
                pastObservationTime, null, "Historical entry before profile"
        );
        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(historicalRecord)))
                .andExpect(status().isCreated());

        RecordMeasurementRequest currentRecord = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("82.00"), null, null, "KG",
                Instant.now().minus(1, ChronoUnit.DAYS), null, "Recent entry"
        );
        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(currentRecord)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/progress/summary")
                        .header("Authorization", "Bearer " + studentToken)
                        .param("scope", "LIFETIME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("LIFETIME"))
                .andExpect(jsonPath("$.measurement.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.measurement.metricDeltas", hasSize(1)))
                .andExpect(jsonPath("$.measurement.metricDeltas[0].baselineValue").value(90.00))
                .andExpect(jsonPath("$.measurement.metricDeltas[0].latestValue").value(82.00))
                .andExpect(jsonPath("$.measurement.metricDeltas[0].trendDirection").value("DECREASED"));
    }

    // --- Goal Privacy Paired Test ---

    @Test
    @DisplayName("Paired test: changing goal metadata or dates does not leak or alter response for trainer without FITNESS_GOAL scope")
    void progressDashboard_goalLeakagePairedTest_changingGoalDoesNotAffectTrainerWithoutFitnessGoalScope() throws Exception {
        grantCoachingAccess(trainerId, studentId, "BODY_METRICS", "VIEW", null, null);

        // Add 1 measurement so dashboard has valid body metrics data
        RecordMeasurementRequest m = new RecordMeasurementRequest(
                null, "WEIGHT", new BigDecimal("75.00"), null, null, "KG",
                Instant.now().minus(2, ChronoUnit.DAYS), null, "Paired test weigh-in"
        );
        mockMvc.perform(post("/api/v1/measurements")
                .header("Authorization", "Bearer " + studentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(m)));

        // State 1: Goal A
        UUID goalAId = UUID.randomUUID();
        UUID versionAId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        jdbcTemplate.update("""
                INSERT INTO fitness.fitness_goals(id, student_id, title, status, created_by, created_at, updated_at)
                VALUES (?, ?, 'Secret Goal A', 'DRAFT'::fitness.lifecycle_status, ?, now(), now())
                """, goalAId, studentId, studentId);
        jdbcTemplate.update("""
                INSERT INTO fitness.fitness_goal_versions(id, fitness_goal_id, version_number, title, start_date, target_date, duration_days, created_by, locked_at, lock_reason, created_at)
                VALUES (?, ?, 1, 'v1', ?, ?, 45, ?, now(), 'ACTIVATED'::fitness.version_lock_reason, now())
                """, versionAId, goalAId, today.minusDays(30), today.plusDays(15), studentId);
        jdbcTemplate.update("UPDATE fitness.fitness_goals SET status = 'ACTIVE'::fitness.lifecycle_status WHERE id = ?", goalAId);

        String jsonUnderGoalA = mockMvc.perform(get("/api/v1/progress/summary")
                        .header("Authorization", "Bearer " + trainerToken)
                        .param("studentId", studentId.toString())
                        .param("scope", "CURRENT_GOAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        String jsonUnderGoalA_lifetime = mockMvc.perform(get("/api/v1/progress/summary")
                        .header("Authorization", "Bearer " + trainerToken)
                        .param("studentId", studentId.toString())
                        .param("scope", "LIFETIME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        // State 2: Change to Goal B with different title, dates, targets
        jdbcTemplate.update("UPDATE fitness.fitness_goals SET status = 'COMPLETED'::fitness.lifecycle_status WHERE id = ?", goalAId);

        UUID goalBId = UUID.randomUUID();
        UUID versionBId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.fitness_goals(id, student_id, title, status, created_by, created_at, updated_at)
                VALUES (?, ?, 'Completely Different Goal B', 'DRAFT'::fitness.lifecycle_status, ?, now(), now())
                """, goalBId, studentId, studentId);
        jdbcTemplate.update("""
                INSERT INTO fitness.fitness_goal_versions(id, fitness_goal_id, version_number, title, start_date, target_date, duration_days, created_by, locked_at, lock_reason, created_at)
                VALUES (?, ?, 1, 'v1', ?, ?, 90, ?, now(), 'ACTIVATED'::fitness.version_lock_reason, now())
                """, versionBId, goalBId, today.minusDays(5), today.plusDays(85), studentId);
        jdbcTemplate.update("UPDATE fitness.fitness_goals SET status = 'ACTIVE'::fitness.lifecycle_status WHERE id = ?", goalBId);

        String jsonUnderGoalB = mockMvc.perform(get("/api/v1/progress/summary")
                        .header("Authorization", "Bearer " + trainerToken)
                        .param("studentId", studentId.toString())
                        .param("scope", "CURRENT_GOAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        String jsonUnderGoalB_lifetime = mockMvc.perform(get("/api/v1/progress/summary")
                        .header("Authorization", "Bearer " + trainerToken)
                        .param("studentId", studentId.toString())
                        .param("scope", "LIFETIME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        // Both responses must be identical in goal (null), fromDate, toDate, and measurement facts/watermark
        tools.jackson.databind.JsonNode treeA = objectMapper.readTree(jsonUnderGoalA);
        tools.jackson.databind.JsonNode treeB = objectMapper.readTree(jsonUnderGoalB);

        assertThat(treeA.get("goal").isNull()).isTrue();
        assertThat(treeB.get("goal").isNull()).isTrue();
        assertThat(treeA.get("fromDate").asText()).isEqualTo(treeB.get("fromDate").asText());
        assertThat(treeA.get("toDate").asText()).isEqualTo(treeB.get("toDate").asText());
        assertThat(treeA.get("measurement").get("availability").asText())
                .isEqualTo(treeB.get("measurement").get("availability").asText());
        assertThat(treeA.get("measurement").get("sourceWatermark").asText())
                .isEqualTo(treeB.get("measurement").get("sourceWatermark").asText());

        // Lifetime responses must also be completely invariant to Goal transitions
        tools.jackson.databind.JsonNode treeA_life = objectMapper.readTree(jsonUnderGoalA_lifetime);
        tools.jackson.databind.JsonNode treeB_life = objectMapper.readTree(jsonUnderGoalB_lifetime);

        assertThat(treeA_life.get("goal").isNull()).isTrue();
        assertThat(treeB_life.get("goal").isNull()).isTrue();
        assertThat(treeA_life.get("fromDate").asText()).isEqualTo(treeB_life.get("fromDate").asText());
        assertThat(treeA_life.get("toDate").asText()).isEqualTo(treeB_life.get("toDate").asText());
        assertThat(treeA_life.get("measurement").get("availability").asText())
                .isEqualTo(treeB_life.get("measurement").get("availability").asText());
        assertThat(treeA_life.get("measurement").get("sourceWatermark").asText())
                .isEqualTo(treeB_life.get("measurement").get("sourceWatermark").asText());
    }

    // --- Photo Lifecycle, Permissions & Failures ---

    @Test
    @DisplayName("Progress photo soft-delete removes photo from list and returns 404 on download")
    void progressPhotos_softDelete_cannotBeListedOrDownloaded() throws Exception {
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "to-delete.png", "image/png", pngBytes);

        doReturn("CLEAN").when(photoStorageService).scanAndVerifyClean(any());
        doAnswer(invocation -> null).when(photoStorageService).storeFile(any(), any(), any());
        doReturn(pngBytes).when(photoStorageService).loadFile(any());

        String uploadJson = mockMvc.perform(multipart("/api/v1/measurements/progress-photos")
                        .file(file)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID photoId = UUID.fromString(objectMapper.readTree(uploadJson).get("id").asText());

        // Verify photo is visible
        mockMvc.perform(get("/api/v1/measurements/progress-photos")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // Soft-delete photo
        mockMvc.perform(delete("/api/v1/measurements/progress-photos/" + photoId)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isNoContent());

        // Verify list returns 0 items
        mockMvc.perform(get("/api/v1/measurements/progress-photos")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Verify download returns 404 PHOTO_NOT_FOUND
        mockMvc.perform(get("/api/v1/measurements/progress-photos/" + photoId + "/file")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PHOTO_NOT_FOUND"));
    }

    @Test
    @DisplayName("Revoking PROGRESS_PHOTOS permission immediately denies file download")
    void progressPhotos_permissionRevocation_deniesDownloadImmediately() throws Exception {
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", pngBytes);

        doReturn("CLEAN").when(photoStorageService).scanAndVerifyClean(any());
        doAnswer(invocation -> null).when(photoStorageService).storeFile(any(), any(), any());
        doReturn(pngBytes).when(photoStorageService).loadFile(any());

        String uploadJson = mockMvc.perform(multipart("/api/v1/measurements/progress-photos")
                        .file(file)
                        .header("Authorization", "Bearer " + studentToken)
                        .param("takenAt", Instant.now().minusSeconds(60).toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID photoId = UUID.fromString(objectMapper.readTree(uploadJson).get("id").asText());

        // Grant trainer PROGRESS_PHOTOS scope
        grantCoachingAccess(trainerId, studentId, "PROGRESS_PHOTOS", "VIEW", null, null);

        // Download succeeds
        mockMvc.perform(get("/api/v1/measurements/progress-photos/" + photoId + "/file")
                        .header("Authorization", "Bearer " + trainerToken))
                .andExpect(status().isOk());

        // Revoke data sharing permission in compliance with protect_data_sharing_permission_history guard
        jdbcTemplate.update("""
                UPDATE fitness.data_sharing_permissions
                SET revoked_at = now(), version = version + 1
                WHERE trainer_id = ? AND student_id = ? AND data_scope = 'PROGRESS_PHOTOS'
                """, trainerId, studentId);

        // Download is immediately rejected with 403 MEASUREMENT_ACCESS_DENIED
        mockMvc.perform(get("/api/v1/measurements/progress-photos/" + photoId + "/file")
                        .header("Authorization", "Bearer " + trainerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MEASUREMENT_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Progress photo upload fail-closed: scanner failure (503) and storage failure (503)")
    void progressPhotos_scannerAndStorageFailures_failClosed() throws Exception {
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "fail.png", "image/png", pngBytes);

        // 1. Scanner unavailable -> 503
        doThrow(new ScannerUnavailableException("Antivirus daemon unreachable"))
                .when(photoStorageService).scanAndVerifyClean(any());

        mockMvc.perform(multipart("/api/v1/measurements/progress-photos")
                        .file(file)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SCANNER_UNAVAILABLE"));

        // 2. Scanner clean, but S3 storage unavailable -> 503
        doReturn("CLEAN").when(photoStorageService).scanAndVerifyClean(any());
        doThrow(new StorageUnavailableException("S3 connection timed out"))
                .when(photoStorageService).storeFile(any(), any(), any());

        mockMvc.perform(multipart("/api/v1/measurements/progress-photos")
                        .file(file)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("STORAGE_UNAVAILABLE"));
    }
}
