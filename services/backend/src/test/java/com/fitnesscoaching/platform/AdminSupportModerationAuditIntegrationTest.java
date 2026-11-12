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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
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
class AdminSupportModerationAuditIntegrationTest {

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
                "fitness.data_correction_actions, fitness.privileged_data_access_events, " +
                "fitness.privileged_data_access_requests, fitness.support_case_events, fitness.support_cases, " +
                "fitness.moderation_actions, fitness.moderation_cases, fitness.user_account_status_history, " +
                "fitness.refresh_tokens, fitness.trainer_applications, fitness.measurements, " +
                "fitness.student_profiles, fitness.trainer_profiles, fitness.user_roles, fitness.users CASCADE");

        // Ensure roles & permissions exist and are granted to ADMIN
        jdbcTemplate.execute("""
                INSERT INTO fitness.roles (code, name, description) VALUES
                ('STUDENT', 'Student', 'Student role'),
                ('TRAINER', 'Trainer', 'Trainer role'),
                ('ADMIN', 'Platform Administrator', 'Admin role')
                ON CONFLICT (code) DO NOTHING;

                INSERT INTO fitness.permissions (code, description) VALUES
                ('SUPPORT_MANAGE', 'Manage support cases'),
                ('MODERATION_MANAGE', 'Manage moderation cases'),
                ('AUDIT_VIEW', 'View audit logs'),
                ('PRIVILEGED_ACCESS_MANAGE', 'Manage privileged access'),
                ('DATA_CORRECTION_EXECUTE', 'Execute data corrections')
                ON CONFLICT (code) DO NOTHING;

                INSERT INTO fitness.role_permissions (role_id, permission_id)
                SELECT r.id, p.id FROM fitness.roles r, fitness.permissions p
                WHERE r.code = 'ADMIN' AND p.code IN (
                    'SUPPORT_MANAGE', 'MODERATION_MANAGE', 'AUDIT_VIEW',
                    'PRIVILEGED_ACCESS_MANAGE', 'DATA_CORRECTION_EXECUTE'
                )
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

    private UUID createUser(String email, String displayName) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, email_verified_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, '+84901234567', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now(), now())
                """, userId, email, passwordEncoder.encode(DEFAULT_PASSWORD), displayName);
        return userId;
    }

    private UUID createStudent(String email, String displayName) {
        UUID userId = createUser(email, displayName);
        jdbcTemplate.update("""
                INSERT INTO fitness.student_profiles (user_id, training_experience_level, created_at, updated_at)
                VALUES (?, 'BEGINNER', now(), now())
                """, userId);
        return userId;
    }

    private UUID createMeasurement(UUID studentId, String metricCode, double value, String unitCode) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.measurements (
                    id, student_id, metric_definition_id, numeric_value, unit_id,
                    measured_at, source_id, validation_status, created_at
                ) VALUES (
                    ?, ?,
                    (SELECT id FROM fitness.metric_definitions WHERE UPPER(code) = UPPER(?)),
                    ?,
                    (SELECT id FROM fitness.measurement_units WHERE UPPER(code) = UPPER(?) OR UPPER(symbol) = UPPER(?)),
                    now(),
                    (SELECT id FROM fitness.measurement_sources LIMIT 1),
                    'VALID'::fitness.measurement_validation_status,
                    now()
                )
                """, id, studentId, metricCode, value, unitCode, unitCode);
        return id;
    }

    @Test
    @DisplayName("Support Case lifecycle: create, list, detail, status change, assign, add notes, and verify audit")
    void testSupportCaseLifecycle() throws Exception {
        UUID adminId = createAdmin("support_admin@fitcoach.vn");
        UUID requesterId = createUser("requester@fitcoach.vn", "Nguyễn Văn A");

        // 1. Create support case
        String createJson = """
                {
                    "requesterId": "%s",
                    "subject": "Không cập nhật được số đo vòng eo",
                    "description": "Tôi nhập số đo hôm qua nhưng bị lỗi mạng, mong admin hỗ trợ.",
                    "priority": "HIGH"
                }
                """.formatted(requesterId);

        MvcResult createRes = mockMvc.perform(post("/api/v1/admin/support-cases")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.caseNumber").value(startsWith("SUP-")))
                .andExpect(jsonPath("$.subject").value("Không cập nhật được số đo vòng eo"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andReturn();

        String caseIdStr = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();
        UUID caseId = UUID.fromString(caseIdStr);

        // 2. List support cases with filter
        mockMvc.perform(get("/api/v1/admin/support-cases")
                        .with(adminJwt(adminId))
                        .param("status", "OPEN")
                        .param("priority", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(caseIdStr));

        // 3. Add note
        mockMvc.perform(post("/api/v1/admin/support-cases/" + caseId + "/notes")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Đã liên hệ người dùng kiểm tra thông tin số đo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(2))) // CASE_CREATED + NOTE_ADDED
                .andExpect(jsonPath("$.events[1].eventType").value("NOTE_ADDED"));

        // 4. Update status to IN_PROGRESS
        mockMvc.perform(post("/api/v1/admin/support-cases/" + caseId + "/status")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"note\":\"Bắt đầu xử lý yêu cầu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("IN_PROGRESS"));

        // 5. Update status to RESOLVED
        mockMvc.perform(post("/api/v1/admin/support-cases/" + caseId + "/status")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"note\":\"Đã xử lý xong số đo cho học viên\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("RESOLVED"))
                .andExpect(jsonPath("$.summary.resolvedAt").isNotEmpty());

        // 6. Verify audit logs recorded
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(1) FROM fitness.audit_logs WHERE target_type = 'SUPPORT_CASE' AND target_id = ?",
                Integer.class, caseId);
        assertThat(auditCount).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Support Case: Invalid lifecycle transitions reject with 409 Conflict")
    void testSupportCaseInvalidTransition() throws Exception {
        UUID adminId = createAdmin("admin_support_invalid@fitcoach.vn");

        String createJson = """
                {
                    "subject": "Lỗi tài khoản",
                    "description": "Mô tả sự cố",
                    "priority": "MEDIUM"
                }
                """;
        MvcResult res = mockMvc.perform(post("/api/v1/admin/support-cases")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn();

        String caseIdStr = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();

        // Transitioning to same status OPEN -> 409 Conflict
        mockMvc.perform(post("/api/v1/admin/support-cases/" + caseIdStr + "/status")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LIFECYCLE_TRANSITION"));
    }

    @Test
    @DisplayName("Moderation Case lifecycle: create, list, perform action, and verify append-only action log")
    void testModerationLifecycle() throws Exception {
        UUID adminId = createAdmin("mod_admin@fitcoach.vn");
        UUID reportedUserId = createUser("reported_user@fitcoach.vn", "Trần Thị B");

        // 1. Create moderation case
        String createJson = """
                {
                    "subjectUserId": "%s",
                    "priority": "HIGH",
                    "notes": "Người dùng đăng bình luận vi phạm tiêu chuẩn cộng đồng"
                }
                """.formatted(reportedUserId);

        MvcResult createRes = mockMvc.perform(post("/api/v1/admin/moderation-cases")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andReturn();

        String caseIdStr = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();
        UUID caseId = UUID.fromString(caseIdStr);

        // 2. Perform WARNING action with mandatory reason
        String actionJson = """
                {
                    "actionType": "WARNING",
                    "targetUserId": "%s",
                    "reason": "Cảnh cáo lần 1 về hành vi ngôn từ không phù hợp trong nhóm học",
                    "metadata": {"violationType": "HARASSMENT"}
                }
                """.formatted(reportedUserId);

        mockMvc.perform(post("/api/v1/admin/moderation-cases/" + caseId + "/actions")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionType").value("WARNING"))
                .andExpect(jsonPath("$.reason").value("Cảnh cáo lần 1 về hành vi ngôn từ không phù hợp trong nhóm học"));

        // 3. Inspect moderation case detail
        mockMvc.perform(get("/api/v1/admin/moderation-cases/" + caseId)
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions", hasSize(2))) // Initial NO_ACTION (notes) + WARNING
                .andExpect(jsonPath("$.actions[1].actionType").value("WARNING"));

        // 4. Missing reason on action must throw 400 Bad Request
        String badActionJson = """
                {
                    "actionType": "SUSPENSION",
                    "reason": ""
                }
                """;
        mockMvc.perform(post("/api/v1/admin/moderation-cases/" + caseId + "/actions")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badActionJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Privileged Data Access: request, active query, and revoke")
    void testPrivilegedDataAccess() throws Exception {
        UUID adminId = createAdmin("priv_admin@fitcoach.vn");
        UUID targetUserId = createUser("priv_target@fitcoach.vn", "Phạm Văn C");

        // 1. Request privileged access
        String reqJson = """
                {
                    "targetUserId": "%s",
                    "purpose": "DATA_CORRECTION",
                    "reason": "Xử lý hiệu chỉnh sai lệch số đo học viên yêu cầu qua ticket SUP-101",
                    "durationMinutes": 60,
                    "accessScope": {"domains": ["MEASUREMENT"]},
                    "stepUpPassword": "%s"
                }
                """.formatted(targetUserId, DEFAULT_PASSWORD);

        MvcResult res = mockMvc.perform(post("/api/v1/admin/privileged-access")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.purpose").value("DATA_CORRECTION"))
                .andExpect(jsonPath("$.currentlyActive").value(true))
                .andReturn();

        String requestIdStr = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
        UUID requestId = UUID.fromString(requestIdStr);

        // 2. Query active requests for this admin
        mockMvc.perform(get("/api/v1/admin/privileged-access/active")
                        .with(adminJwt(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestIdStr));

        // 3. Revoke privileged access
        mockMvc.perform(post("/api/v1/admin/privileged-access/" + requestId + "/revoke")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Đã hoàn thành công việc hiệu chỉnh dữ liệu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.currentlyActive").value(false));

        // 4. Query active requests after revoke returns 204 No Content
        mockMvc.perform(get("/api/v1/admin/privileged-access/active")
                        .with(adminJwt(adminId)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Governed Data Correction: Measurement correction creates SUPERSEDED history, links support case, satisfies trigger and records audit")
    void testGovernedDataCorrection_Measurement() throws Exception {
        UUID adminId = createAdmin("correction_admin@fitcoach.vn");
        UUID studentId = createStudent("student_correction@fitcoach.vn", "Lê Thị D");

        // Create measurement: 75.0 kg
        UUID oldMeasurementId = createMeasurement(studentId, "WEIGHT", 75.0, "KG");

        // 1. Create support case
        UUID supportCaseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-CORR-1', ?, 'Sai số đo cân nặng', 'Tôi nặng 72kg nhưng ấn nhầm 75kg', 'MEDIUM'::fitness.severity_level, 'OPEN', now(), now())
                """, supportCaseId, studentId);

        // 2. Open Privileged Access
        UUID privAccessId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_user_id,
                    purpose, access_scope, reason, related_support_case_id,
                    status, requested_at, approved_at, opened_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, 'DATA_CORRECTION', '{"domains":["MEASUREMENT"]}'::jsonb,
                    'Hỗ trợ sửa số đo cân nặng học viên', ?,
                    'ACTIVE'::fitness.privileged_access_status, now(), now(), now(), now() + interval '1 hour'
                )
                """, privAccessId, adminId, adminId, studentId, supportCaseId);

        // 3. Execute data correction with step-up password
        String correctionJson = """
                {
                    "supportCaseId": "%s",
                    "targetType": "MEASUREMENT",
                    "targetId": "%s",
                    "reason": "Điều chỉnh số đo cân nặng từ 75kg về đúng 72kg theo yêu cầu phiếu hỗ trợ SUP-CORR-1",
                    "proposedData": {
                        "value": 72.0,
                        "unit": "kg"
                    },
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, oldMeasurementId, privAccessId, DEFAULT_PASSWORD);

        MvcResult res = mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.targetType").value("MEASUREMENT"))
                .andExpect(jsonPath("$.targetId").value(oldMeasurementId.toString()))
                .andReturn();

        // 4. Verify old measurement has superseded_by_id and validation_status = 'SUPERSEDED'
        Map<String, Object> oldMeas = jdbcTemplate.queryForMap(
                "SELECT validation_status, superseded_by_id FROM fitness.measurements WHERE id = ?",
                oldMeasurementId);
        assertThat(oldMeas.get("validation_status")).isEqualTo("SUPERSEDED");
        UUID newMeasurementId = (UUID) oldMeas.get("superseded_by_id");
        assertThat(newMeasurementId).isNotNull();

        // 5. Verify new measurement inserted with 72.0 kg
        Map<String, Object> newMeas = jdbcTemplate.queryForMap(
                "SELECT numeric_value, notes, superseded_by_id FROM fitness.measurements WHERE id = ?",
                newMeasurementId);
        assertThat(((Number) newMeas.get("numeric_value")).doubleValue()).isEqualTo(72.0);
        assertThat(newMeas.get("superseded_by_id")).isNull();

        // 6. Verify data_correction_actions row
        Integer corrCount = jdbcTemplate.queryForObject(
                "SELECT count(1) FROM fitness.data_correction_actions WHERE support_case_id = ? AND target_id = ?",
                Integer.class, supportCaseId, oldMeasurementId);
        assertThat(corrCount).isEqualTo(1);

        // 7. Verify admin_actions table row (satisfied trigger)
        Integer adminActionCount = jdbcTemplate.queryForObject(
                "SELECT count(1) FROM fitness.admin_actions WHERE action_type = 'DATA_CORRECTION' AND target_id = ?",
                Integer.class, oldMeasurementId);
        assertThat(adminActionCount).isEqualTo(1);

        // 8. Verify audit log entry
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(1) FROM fitness.audit_logs WHERE action = 'ADMIN_DATA_CORRECTION' AND target_id = ?",
                Integer.class, oldMeasurementId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Governed Data Correction: Concurrency conflict when correcting an already superseded measurement returns 409 Conflict")
    void testGovernedDataCorrection_ConcurrentConflict() throws Exception {
        UUID adminId = createAdmin("corr_conflict_admin@fitcoach.vn");
        UUID studentId = createStudent("student_conflict@fitcoach.vn", "Võ Văn E");

        UUID oldMeasId = createMeasurement(studentId, "HEIGHT", 170.0, "CM");

        // Manually mark it superseded by another valid measurement for the same student & metric
        UUID supersedingMeasId = createMeasurement(studentId, "HEIGHT", 172.0, "CM");
        jdbcTemplate.update("UPDATE fitness.measurements SET superseded_by_id = ?, validation_status = 'SUPERSEDED'::fitness.measurement_validation_status WHERE id = ?", supersedingMeasId, oldMeasId);

        UUID supportCaseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-CORR-2', ?, 'Sửa chiều cao', 'Mô tả', 'MEDIUM'::fitness.severity_level, 'OPEN', now(), now())
                """, supportCaseId, studentId);

        UUID privAccessId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_user_id,
                    purpose, access_scope, reason, related_support_case_id,
                    status, requested_at, approved_at, opened_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, 'DATA_CORRECTION', '{"domains":["MEASUREMENT"]}'::jsonb,
                    'Sửa chiều cao', ?,
                    'ACTIVE'::fitness.privileged_access_status, now(), now(), now(), now() + interval '1 hour'
                )
                """, privAccessId, adminId, adminId, studentId, supportCaseId);

        String correctionJson = """
                {
                    "supportCaseId": "%s",
                    "targetType": "MEASUREMENT",
                    "targetId": "%s",
                    "reason": "Sửa lại chiều cao",
                    "proposedData": {"value": 172.0, "unit": "cm"},
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, oldMeasId, privAccessId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LIFECYCLE_TRANSITION"));
    }

    @Test
    @DisplayName("Governed Data Correction: Audit failure triggers full transaction rollback of data mutations")
    void testGovernedDataCorrection_AuditFailureRollback() throws Exception {
        UUID adminId = createAdmin("corr_rollback_admin@fitcoach.vn");
        UUID studentId = createStudent("student_rollback@fitcoach.vn", "Đặng Văn F");

        UUID measId = createMeasurement(studentId, "CHEST_CIRCUMFERENCE", 90.0, "CM");

        UUID supportCaseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-CORR-3', ?, 'Sửa số đo vòng 1', 'Mô tả', 'MEDIUM'::fitness.severity_level, 'OPEN', now(), now())
                """, supportCaseId, studentId);

        UUID privAccessId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_user_id,
                    purpose, access_scope, reason, related_support_case_id,
                    status, requested_at, approved_at, opened_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, 'DATA_CORRECTION', '{"domains":["MEASUREMENT"]}'::jsonb,
                    'Sửa số đo', ?,
                    'ACTIVE'::fitness.privileged_access_status, now(), now(), now(), now() + interval '1 hour'
                )
                """, privAccessId, adminId, adminId, studentId, supportCaseId);

        // Spy throw exception when auditService.recordAudit is called
        doThrow(new RuntimeException("Simulated audit write failure"))
                .when(auditService).recordAudit(any());

        try {
            String correctionJson = """
                    {
                        "supportCaseId": "%s",
                        "targetType": "MEASUREMENT",
                        "targetId": "%s",
                        "reason": "Điều chỉnh số đo vòng ngực",
                        "proposedData": {"value": 92.0, "unit": "cm"},
                        "privilegedAccessRequestId": "%s",
                        "stepUpPassword": "%s"
                    }
                    """.formatted(supportCaseId, measId, privAccessId, DEFAULT_PASSWORD);

            mockMvc.perform(post("/api/v1/admin/data-corrections")
                            .with(adminJwt(adminId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(correctionJson))
                    .andExpect(status().isInternalServerError());

            // Verify original measurement was NOT superseded (rolled back!)
            Map<String, Object> meas = jdbcTemplate.queryForMap(
                    "SELECT validation_status, superseded_by_id FROM fitness.measurements WHERE id = ?", measId);
            assertThat(meas.get("validation_status")).isEqualTo("VALID");
            assertThat(meas.get("superseded_by_id")).isNull();

            // Verify no data_correction_actions row was persisted
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(1) FROM fitness.data_correction_actions WHERE target_id = ?",
                    Integer.class, measId);
            assertThat(count).isEqualTo(0);

        } finally {
            reset(auditService);
        }
    }

    @Test
    @DisplayName("Audit Log Search: search with filters, pagination, and secret redaction")
    void testAuditLogSearch() throws Exception {
        UUID adminId = createAdmin("audit_search_admin@fitcoach.vn");
        UUID targetId = UUID.randomUUID();

        // Insert audit log with sensitive data
        jdbcTemplate.update("""
                INSERT INTO fitness.audit_logs (
                    actor_user_id, actor_role, action, target_type, target_id,
                    before_data, after_data, metadata, occurred_at
                ) VALUES (
                    ?, 'ADMIN', 'ACCOUNT_UPDATED', 'USER', ?,
                    '{"passwordHash":"$2a$12$secretHashValue123","email":"test@fitcoach.vn"}'::jsonb,
                    '{"password":"plainSecretPassword!","email":"test@fitcoach.vn"}'::jsonb,
                    '{"secret":"apiKey123"}'::jsonb,
                    now()
                )
                """, adminId, targetId);

        // Search audit logs
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .with(adminJwt(adminId))
                        .param("action", "ACCOUNT_UPDATED")
                        .param("targetType", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].action").value("ACCOUNT_UPDATED"))
                .andExpect(jsonPath("$.content[0].beforeDataJson", containsString("\"passwordHash\":\"[REDACTED]\"")))
                .andExpect(jsonPath("$.content[0].afterDataJson", containsString("\"password\":\"[REDACTED]\"")))
                .andExpect(jsonPath("$.content[0].metadataJson", containsString("\"secret\":\"[REDACTED]\"")));
    }

    @Test
    @DisplayName("Support Case: Assign case requires active ADMIN role; rejects non-admin assignees")
    void testSupportCaseAssignmentValidation() throws Exception {
        UUID admin1Id = createAdmin("admin1_assign@fitcoach.vn");
        UUID admin2Id = createAdmin("admin2_assign@fitcoach.vn");
        UUID nonAdminUserId = createUser("nonadmin@fitcoach.vn", "Non Admin User");
        UUID requesterId = createUser("requester_assign@fitcoach.vn", "Requester");

        // 1. Create support case
        String createJson = """
                {
                    "requesterId": "%s",
                    "subject": "Cần hỗ trợ chuyển lớp",
                    "description": "Chi tiết yêu cầu",
                    "priority": "MEDIUM"
                }
                """.formatted(requesterId);

        MvcResult createRes = mockMvc.perform(post("/api/v1/admin/support-cases")
                        .with(adminJwt(admin1Id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn();

        String caseIdStr = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();
        UUID caseId = UUID.fromString(caseIdStr);

        // 2. Assign case to non-admin user -> 400 Bad Request
        mockMvc.perform(post("/api/v1/admin/support-cases/" + caseId + "/assign")
                        .with(adminJwt(admin1Id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedToAdminId\":\"" + nonAdminUserId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // 3. Assign case to valid admin2 -> 200 OK
        mockMvc.perform(post("/api/v1/admin/support-cases/" + caseId + "/assign")
                        .with(adminJwt(admin1Id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedToAdminId\":\"" + admin2Id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.assignedToAdminId").value(admin2Id.toString()))
                .andExpect(jsonPath("$.events", hasSize(2)));
    }

    @Test
    @DisplayName("Moderation: SUSPENSION and DISABLE actions enforce account lifecycle changes, session revocation, step-up, and last-admin protection")
    void testModerationSuspensionAndDisableBusinessImpact() throws Exception {
        UUID adminId = createAdmin("mod_lifecycle_admin@fitcoach.vn");
        UUID targetUserId = createUser("target_moderated@fitcoach.vn", "Target Moderated User");

        // Add an active refresh token for target user
        jdbcTemplate.update("""
                INSERT INTO fitness.refresh_tokens (id, user_id, token_hash, expires_at, issued_at)
                VALUES (gen_random_uuid(), ?, 'sampleHash123', now() + interval '7 days', now())
                """, targetUserId);

        // Create moderation case
        UUID caseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.moderation_cases (id, subject_user_id, priority, status, opened_at, updated_at)
                VALUES (?, ?, 'CRITICAL'::fitness.severity_level, 'OPEN', now(), now())
                """, caseId, targetUserId);

        // 1. WARNING action writes to security_events
        String warnJson = """
                {
                    "actionType": "WARNING",
                    "targetUserId": "%s",
                    "reason": "Vi phạm quy tắc lần đầu"
                }
                """.formatted(targetUserId);

        mockMvc.perform(post("/api/v1/admin/moderation-cases/" + caseId + "/actions")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(warnJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionType").value("WARNING"));

        Integer secEventCount = jdbcTemplate.queryForObject(
                "SELECT count(1) FROM fitness.security_events WHERE user_id = ? AND event_type = 'USER_MODERATION_WARNING'",
                Integer.class, targetUserId);
        assertThat(secEventCount).isEqualTo(1);

        // 2. Self-action prevention: Admin cannot perform moderation action against self
        String selfActionJson = """
                {
                    "actionType": "WARNING",
                    "targetUserId": "%s",
                    "reason": "Tự xử phạt bản thân"
                }
                """.formatted(adminId);

        mockMvc.perform(post("/api/v1/admin/moderation-cases/" + caseId + "/actions")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfActionJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SELF_ACTION_NOT_ALLOWED"));

        // 3. SUSPENSION action requires step-up password, updates user status, and revokes refresh tokens
        String suspendJson = """
                {
                    "actionType": "SUSPENSION",
                    "targetUserId": "%s",
                    "reason": "Tạm đình chỉ tài khoản do vi phạm nghiêm trọng chính sách",
                    "stepUpPassword": "%s"
                }
                """.formatted(targetUserId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/moderation-cases/" + caseId + "/actions")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suspendJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionType").value("SUSPENSION"));

        // Verify user account status in database is now SUSPENDED
        String statusAfterSuspend = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.users WHERE id = ?", String.class, targetUserId);
        assertThat(statusAfterSuspend).isEqualTo("SUSPENDED");

        // Verify active refresh token was revoked
        Integer activeTokens = jdbcTemplate.queryForObject(
                "SELECT count(1) FROM fitness.refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, targetUserId);
        assertThat(activeTokens).isEqualTo(0);

        // Verify user_account_status_history has the entry
        Integer historyEntries = jdbcTemplate.queryForObject(
                "SELECT count(1) FROM fitness.user_account_status_history WHERE user_id = ? AND to_status = 'SUSPENDED'",
                Integer.class, targetUserId);
        assertThat(historyEntries).isEqualTo(1);

        // 4. DISABLE action permanently disables the account
        String disableJson = """
                {
                    "actionType": "DISABLE",
                    "targetUserId": "%s",
                    "reason": "Vô hiệu hóa tài khoản vĩnh viễn",
                    "stepUpPassword": "%s"
                }
                """.formatted(targetUserId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/moderation-cases/" + caseId + "/actions")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disableJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionType").value("DISABLE"));

        String statusAfterDisable = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.users WHERE id = ?", String.class, targetUserId);
        assertThat(statusAfterDisable).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("Governed Data Correction: USER_PROFILE correction updates profile safely with audit, provenance, and step-up")
    void testGovernedDataCorrection_UserProfile() throws Exception {
        UUID adminId = createAdmin("corr_profile_admin@fitcoach.vn");
        UUID targetUserId = createUser("profile_corr@fitcoach.vn", "Tên Cũ");

        UUID supportCaseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-PROF-1', ?, 'Sửa họ tên hiển thị', 'Mô tả', 'MEDIUM'::fitness.severity_level, 'OPEN', now(), now())
                """, supportCaseId, targetUserId);

        UUID privAccessId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_user_id,
                    purpose, access_scope, reason, related_support_case_id,
                    status, requested_at, approved_at, opened_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, 'DATA_CORRECTION', '{"domains":["USER_PROFILE"]}'::jsonb,
                    'Sửa tên theo CCCD', ?,
                    'ACTIVE'::fitness.privileged_access_status, now(), now(), now(), now() + interval '1 hour'
                )
                """, privAccessId, adminId, adminId, targetUserId, supportCaseId);

        String correctionJson = """
                {
                    "supportCaseId": "%s",
                    "targetType": "USER_PROFILE",
                    "targetId": "%s",
                    "reason": "Cập nhật họ tên chính xác theo giấy tờ tùy thân",
                    "proposedData": {
                        "displayName": "Nguyễn Văn Chuẩn",
                        "phoneNumber": "+84988776655"
                    },
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, targetUserId, privAccessId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.targetType").value("USER_PROFILE"))
                .andExpect(jsonPath("$.targetId").value(targetUserId.toString()));

        // Verify database user record was updated
        Map<String, Object> userRow = jdbcTemplate.queryForMap(
                "SELECT display_name, phone_number FROM fitness.users WHERE id = ?", targetUserId);
        assertThat(userRow.get("display_name")).isEqualTo("Nguyễn Văn Chuẩn");
        assertThat(userRow.get("phone_number")).isEqualTo("+84988776655");

        // Verify data_correction_actions row
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(1) FROM fitness.data_correction_actions WHERE target_id = ?",
                Integer.class, targetUserId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Governed Data Correction: Cross-user privileged access session usage is strictly rejected (403 Forbidden)")
    void testGovernedDataCorrection_CrossUserPrivilegedAccessRejection() throws Exception {
        UUID adminId = createAdmin("corr_cross_admin@fitcoach.vn");
        UUID userA = createUser("user_a@fitcoach.vn", "User A");
        UUID userB = createUser("user_b@fitcoach.vn", "User B");

        UUID supportCaseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-CROSS-1', ?, 'Sự cố User B', 'Mô tả', 'MEDIUM'::fitness.severity_level, 'OPEN', now(), now())
                """, supportCaseId, userB);

        // Privileged access was granted for User A, NOT User B
        UUID privAccessId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_user_id,
                    purpose, access_scope, reason, related_support_case_id,
                    status, requested_at, approved_at, opened_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, 'DATA_CORRECTION', '{"domains":["USER_PROFILE"]}'::jsonb,
                    'Truy cập User A', ?,
                    'ACTIVE'::fitness.privileged_access_status, now(), now(), now(), now() + interval '1 hour'
                )
                """, privAccessId, adminId, adminId, userA, supportCaseId);

        String correctionJson = """
                {
                    "supportCaseId": "%s",
                    "targetType": "USER_PROFILE",
                    "targetId": "%s",
                    "reason": "Cố tình dùng phiên của User A để sửa User B",
                    "proposedData": {"displayName": "Hacker Name"},
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, userB, privAccessId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Audit Log: Deep recursive redaction masks sensitive fields in nested JSON and arrays")
    void testAuditLogDeepRecursiveRedaction() throws Exception {
        UUID adminId = createAdmin("audit_deep_admin@fitcoach.vn");
        UUID targetId = UUID.randomUUID();

        // Complex nested JSON with secrets inside objects and arrays
        String nestedJson = """
                {
                    "user": {
                        "name": "Nguyễn Văn G",
                        "credentials": {
                            "password": "superSecret123",
                            "apiKey": "key-xyz-789"
                        }
                    },
                    "tokens": [
                        {"token": "tok_1", "type": "BEARER"},
                        {"refreshToken": "tok_refresh_2"}
                    ],
                    "status": "SUCCESS"
                }
                """;

        jdbcTemplate.update("""
                INSERT INTO fitness.audit_logs (
                    actor_user_id, actor_role, action, target_type, target_id,
                    before_data, after_data, metadata, occurred_at
                ) VALUES (
                    ?, 'ADMIN', 'DEEP_NESTED_ACTION', 'SYSTEM', ?,
                    ?::jsonb, ?::jsonb, ?::jsonb, now()
                )
                """, adminId, targetId, nestedJson, nestedJson, nestedJson);

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .with(adminJwt(adminId))
                        .param("action", "DEEP_NESTED_ACTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].afterDataJson", containsString("\"password\":\"[REDACTED]\"")))
                .andExpect(jsonPath("$.content[0].afterDataJson", containsString("\"apiKey\":\"[REDACTED]\"")))
                .andExpect(jsonPath("$.content[0].afterDataJson", containsString("\"token\":\"[REDACTED]\"")))
                .andExpect(jsonPath("$.content[0].afterDataJson", containsString("\"refreshToken\":\"[REDACTED]\"")))
                .andExpect(jsonPath("$.content[0].afterDataJson", containsString("\"name\":\"Nguyễn Văn G\"")));
    }

    @Test
    @DisplayName("Security: Unauthenticated and non-admin requests are blocked (401 and 403)")
    void testSecurityGuards() throws Exception {
        UUID normalUserId = createUser("normal_user@fitcoach.vn", "Người dùng thường");

        // 1. Unauthenticated -> 401
        mockMvc.perform(get("/api/v1/admin/support-cases"))
                .andExpect(status().isUnauthorized());

        // 2. Non-admin user -> 403 Forbidden
        mockMvc.perform(get("/api/v1/admin/support-cases")
                        .with(userJwt(normalUserId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/moderation-cases")
                        .with(userJwt(normalUserId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .with(userJwt(normalUserId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/privileged-access")
                        .with(userJwt(normalUserId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/data-corrections")
                        .with(userJwt(normalUserId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Governed Data Correction: Revoked or expired privileged access request is strictly rejected (403 Forbidden)")
    void testDataCorrectionWithRevokedOrExpiredPrivilegedAccess() throws Exception {
        UUID adminId = createAdmin("corr_revoked_admin@fitcoach.vn");
        UUID targetUserId = createUser("corr_revoked_target@fitcoach.vn", "Target User");

        UUID supportCaseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-REVOKED-1', ?, 'Ticket', 'Desc', 'MEDIUM'::fitness.severity_level, 'OPEN', now(), now())
                """, supportCaseId, targetUserId);

        // 1. Session is REVOKED
        UUID revokedPrivId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_user_id,
                    purpose, access_scope, reason, related_support_case_id,
                    status, requested_at, approved_at, opened_at, expires_at, revoked_at
                ) VALUES (
                    ?, ?, ?, ?, 'DATA_CORRECTION', '{}'::jsonb,
                    'Reason', ?,
                    'REVOKED'::fitness.privileged_access_status, now(), now(), now(), now() + interval '1 hour', now()
                )
                """, revokedPrivId, adminId, adminId, targetUserId, supportCaseId);

        String correctionWithRevoked = """
                {
                    "supportCaseId": "%s",
                    "targetType": "USER_PROFILE",
                    "targetId": "%s",
                    "reason": "Thử sửa với session đã bị thu hồi",
                    "proposedData": {"displayName": "New Name"},
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, targetUserId, revokedPrivId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionWithRevoked))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        // 2. Session is EXPIRED
        UUID expiredPrivId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_user_id,
                    purpose, access_scope, reason, related_support_case_id,
                    status, requested_at, approved_at, opened_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, 'DATA_CORRECTION', '{}'::jsonb,
                    'Reason', ?,
                    'ACTIVE'::fitness.privileged_access_status, now() - interval '2 hours', now() - interval '2 hours', now() - interval '2 hours', now() - interval '1 hour'
                )
                """, expiredPrivId, adminId, adminId, targetUserId, supportCaseId);

        String correctionWithExpired = """
                {
                    "supportCaseId": "%s",
                    "targetType": "USER_PROFILE",
                    "targetId": "%s",
                    "reason": "Thử sửa với session đã hết hạn",
                    "proposedData": {"displayName": "New Name"},
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, targetUserId, expiredPrivId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionWithExpired))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("P1.A Step-Up Enforcement: Privileged access requires valid step-up password or token, rejecting missing, invalid, or cross-admin credentials")
    void testPrivilegedAccessStepUpEnforcement() throws Exception {
        UUID adminId = createAdmin("stepup_admin@fitcoach.vn");
        UUID otherAdminId = createAdmin("other_admin@fitcoach.vn");
        UUID targetUserId = createUser("stepup_target@fitcoach.vn", "Nguyễn Step Up");

        String baseReq = """
                {
                    "targetUserId": "%s",
                    "purpose": "DATA_CORRECTION",
                    "reason": "Yêu cầu kiểm tra quyền đặc quyền có step-up",
                    "durationMinutes": 30,
                    "accessScope": {"domains": ["MEASUREMENT"]}
                }
                """.formatted(targetUserId);

        // 1. Missing step-up (neither password nor token) -> 403 Forbidden
        mockMvc.perform(post("/api/v1/admin/privileged-access")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(baseReq))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        // 2. Wrong step-up password -> 401 Unauthorized
        String wrongPwdReq = """
                {
                    "targetUserId": "%s",
                    "purpose": "DATA_CORRECTION",
                    "reason": "Yêu cầu kiểm tra sai password",
                    "durationMinutes": 30,
                    "accessScope": {"domains": ["MEASUREMENT"]},
                    "stepUpPassword": "WrongAdminPassword123!"
                }
                """.formatted(targetUserId);

        mockMvc.perform(post("/api/v1/admin/privileged-access")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongPwdReq))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));

        // 3. Expired or invalid step-up authentication ID -> 403 Forbidden
        UUID fakeStepUpId = UUID.randomUUID();
        String invalidTokenReq = """
                {
                    "targetUserId": "%s",
                    "purpose": "DATA_CORRECTION",
                    "reason": "Yêu cầu kiểm tra token không tồn tại",
                    "durationMinutes": 30,
                    "accessScope": {"domains": ["MEASUREMENT"]},
                    "stepUpAuthenticationId": "%s"
                }
                """.formatted(targetUserId, fakeStepUpId);

        mockMvc.perform(post("/api/v1/admin/privileged-access")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidTokenReq))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        // 4. Step-up token issued to another admin -> 403 Forbidden
        MvcResult otherStepUpRes = mockMvc.perform(post("/api/v1/admin/auth/step-up")
                        .with(adminJwt(otherAdminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "actionCode": "PRIVILEGED_ACCESS_REQUEST",
                                    "password": "%s"
                                }
                                """.formatted(DEFAULT_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String otherStepUpId = objectMapper.readTree(otherStepUpRes.getResponse().getContentAsString())
                .get("stepUpAuthenticationId").asText();

        String crossAdminReq = """
                {
                    "targetUserId": "%s",
                    "purpose": "DATA_CORRECTION",
                    "reason": "Yêu cầu kiểm tra dùng token của admin khác",
                    "durationMinutes": 30,
                    "accessScope": {"domains": ["MEASUREMENT"]},
                    "stepUpAuthenticationId": "%s"
                }
                """.formatted(targetUserId, otherStepUpId);

        mockMvc.perform(post("/api/v1/admin/privileged-access")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossAdminReq))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        // 5. Valid step-up token issued to current admin -> 201 Created
        MvcResult validStepUpRes = mockMvc.perform(post("/api/v1/admin/auth/step-up")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "actionCode": "PRIVILEGED_ACCESS_REQUEST",
                                    "password": "%s"
                                }
                                """.formatted(DEFAULT_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String validStepUpId = objectMapper.readTree(validStepUpRes.getResponse().getContentAsString())
                .get("stepUpAuthenticationId").asText();

        String validTokenReq = """
                {
                    "targetUserId": "%s",
                    "purpose": "DATA_CORRECTION",
                    "reason": "Yêu cầu hợp lệ dùng step-up token",
                    "durationMinutes": 30,
                    "accessScope": {"domains": ["MEASUREMENT"]},
                    "stepUpAuthenticationId": "%s"
                }
                """.formatted(targetUserId, validStepUpId);

        mockMvc.perform(post("/api/v1/admin/privileged-access")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTokenReq))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentlyActive").value(true));
    }

    @Test
    @DisplayName("P1.B Purpose & Scope Guard: Data correction rejects privileged sessions with wrong purpose, out-of-scope domain or mismatched resource")
    void testGovernedDataCorrection_ScopeAndPurposeValidation() throws Exception {
        UUID adminId = createAdmin("scope_admin@fitcoach.vn");
        UUID studentId = createStudent("scope_student@fitcoach.vn", "Trần Scope");
        UUID measurementId = createMeasurement(studentId, "WEIGHT", 68.0, "KG");

        UUID supportCaseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-SCOPE-1', ?, 'Hiệu chỉnh số đo', 'Nhầm cân nặng', 'MEDIUM'::fitness.severity_level, 'OPEN', now(), now())
                """, supportCaseId, studentId);

        // 1. Privileged session with purpose = 'SUPPORT' (not 'DATA_CORRECTION') -> 403 Forbidden
        UUID supportPurposePrivId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_user_id,
                    purpose, access_scope, reason, related_support_case_id,
                    status, requested_at, approved_at, opened_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, 'SUPPORT', '{"domains":["MEASUREMENT"]}'::jsonb,
                    'Điều tra hỗ trợ thường', ?,
                    'ACTIVE'::fitness.privileged_access_status, now(), now(), now(), now() + interval '1 hour'
                )
                """, supportPurposePrivId, adminId, adminId, studentId, supportCaseId);

        String correctionWithSupportPurpose = """
                {
                    "supportCaseId": "%s",
                    "targetType": "MEASUREMENT",
                    "targetId": "%s",
                    "reason": "Thử sửa với session purpose là SUPPORT",
                    "proposedData": {"value": 65.0, "unit": "kg"},
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, measurementId, supportPurposePrivId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionWithSupportPurpose))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        // 2. Privileged session with accessScope = {"domains":["USER_PROFILE"]} attempting MEASUREMENT -> 403 Forbidden
        UUID userProfileScopePrivId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_user_id,
                    purpose, access_scope, reason, related_support_case_id,
                    status, requested_at, approved_at, opened_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, 'DATA_CORRECTION', '{"domains":["USER_PROFILE"]}'::jsonb,
                    'Chỉ cho phép hồ sơ người dùng', ?,
                    'ACTIVE'::fitness.privileged_access_status, now(), now(), now(), now() + interval '1 hour'
                )
                """, userProfileScopePrivId, adminId, adminId, studentId, supportCaseId);

        String correctionWithOutOfScopeDomain = """
                {
                    "supportCaseId": "%s",
                    "targetType": "MEASUREMENT",
                    "targetId": "%s",
                    "reason": "Thử sửa số đo với session chỉ có domain USER_PROFILE",
                    "proposedData": {"value": 65.0, "unit": "kg"},
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, measurementId, userProfileScopePrivId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionWithOutOfScopeDomain))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        // 3. Resource-targeted session with mismatched resource ID -> 403 Forbidden
        UUID otherMeasurementId = UUID.randomUUID();
        UUID resourcePrivId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_resource_type, target_resource_id,
                    purpose, access_scope, reason, related_support_case_id,
                    status, requested_at, approved_at, opened_at, expires_at
                ) VALUES (
                    ?, ?, ?, 'MEASUREMENT', ?,
                    'DATA_CORRECTION', '{"domains":["MEASUREMENT"]}'::jsonb,
                    'Chỉ cho phép sửa measurement khác', ?,
                    'ACTIVE'::fitness.privileged_access_status, now(), now(), now(), now() + interval '1 hour'
                )
                """, resourcePrivId, adminId, adminId, otherMeasurementId, supportCaseId);

        String correctionWithMismatchedResource = """
                {
                    "supportCaseId": "%s",
                    "targetType": "MEASUREMENT",
                    "targetId": "%s",
                    "reason": "Thử sửa measurement khác với session resource ID",
                    "proposedData": {"value": 65.0, "unit": "kg"},
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, measurementId, resourcePrivId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionWithMismatchedResource))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("P2.A Unit Validation: Measurement correction rejects unknown unit and dimension-incompatible unit with HTTP 400")
    void testGovernedDataCorrection_UnitValidation() throws Exception {
        UUID adminId = createAdmin("unit_admin@fitcoach.vn");
        UUID studentId = createStudent("unit_student@fitcoach.vn", "Hoàng Unit");
        UUID measurementId = createMeasurement(studentId, "WEIGHT", 80.0, "KG");

        UUID supportCaseId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-UNIT-1', ?, 'Sai số đo', 'Nhầm cân nặng', 'MEDIUM'::fitness.severity_level, 'OPEN', now(), now())
                """, supportCaseId, studentId);

        UUID privAccessId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_user_id,
                    purpose, access_scope, reason, related_support_case_id,
                    status, requested_at, approved_at, opened_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, 'DATA_CORRECTION', '{"domains":["MEASUREMENT"]}'::jsonb,
                    'Sửa số đo học viên', ?,
                    'ACTIVE'::fitness.privileged_access_status, now(), now(), now(), now() + interval '1 hour'
                )
                """, privAccessId, adminId, adminId, studentId, supportCaseId);

        // 1. Unknown unit code -> 400 Bad Request
        String unknownUnitCorrection = """
                {
                    "supportCaseId": "%s",
                    "targetType": "MEASUREMENT",
                    "targetId": "%s",
                    "reason": "Điều chỉnh số đo với đơn vị không tồn tại",
                    "proposedData": {"value": 75.0, "unit": "UNKNOWN_CUSTOM_UNIT"},
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, measurementId, privAccessId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownUnitCorrection))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // 2. Incompatible unit (e.g. CM length unit for WEIGHT metric) -> 400 Bad Request
        String incompatibleUnitCorrection = """
                {
                    "supportCaseId": "%s",
                    "targetType": "MEASUREMENT",
                    "targetId": "%s",
                    "reason": "Điều chỉnh cân nặng nhưng dùng đơn vị cm",
                    "proposedData": {"value": 75.0, "unit": "cm"},
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, measurementId, privAccessId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incompatibleUnitCorrection))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // 3. Valid compatible unit ("kg") -> 201 Created
        String validUnitCorrection = """
                {
                    "supportCaseId": "%s",
                    "targetType": "MEASUREMENT",
                    "targetId": "%s",
                    "reason": "Điều chỉnh cân nặng đúng đơn vị kg",
                    "proposedData": {"value": 76.5, "unit": "kg"},
                    "privilegedAccessRequestId": "%s",
                    "stepUpPassword": "%s"
                }
                """.formatted(supportCaseId, measurementId, privAccessId, DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/data-corrections")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUnitCorrection))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetType").value("MEASUREMENT"))
                .andExpect(jsonPath("$.targetId").value(measurementId.toString()));
    }

    @Test
    @DisplayName("P2.B JSON Metadata Audit: Support case note and status changes serialize special characters safely into valid JSON")
    void testSupportCase_SpecialCharactersInAuditMetadata() throws Exception {
        UUID adminId = createAdmin("json_admin@fitcoach.vn");
        UUID requesterId = createUser("json_user@fitcoach.vn", "Đỗ Văn JSON");

        // 1. Create support case
        String createJson = """
                {
                    "requesterId": "%s",
                    "subject": "Vấn đề tài khoản và số đo",
                    "description": "Mô tả chi tiết hỗ trợ",
                    "priority": "HIGH"
                }
                """.formatted(requesterId);

        MvcResult createRes = mockMvc.perform(post("/api/v1/admin/support-cases")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn();

        String caseIdStr = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();
        UUID caseId = UUID.fromString(caseIdStr);

        // 2. Add note with newlines, backslashes, double quotes, and Vietnamese diacritics
        String noteContent = "Ghi chú kỹ thuật: C:\\system\\config.json\nDòng thứ 2: \"Đã giải quyết xong\"\nTiếng Việt có dấu: Hệ thống Huấn luyện viên thể hình";
        String noteJson = """
                {
                    "note": %s
                }
                """.formatted(objectMapper.writeValueAsString(noteContent));

        mockMvc.perform(post("/api/v1/admin/support-cases/" + caseId + "/notes")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noteJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray());

        // 3. Update status with special characters in note
        String statusNoteContent = "Chuyển trạng thái:\nĐường dẫn: C:\\logs\\audit.log\n\"Hoàn thành xác minh\"";
        String statusJson = """
                {
                    "status": "IN_PROGRESS",
                    "note": %s
                }
                """.formatted(objectMapper.writeValueAsString(statusNoteContent));

        mockMvc.perform(post("/api/v1/admin/support-cases/" + caseId + "/status")
                        .with(adminJwt(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("IN_PROGRESS"));

        // 4. Query audit logs to ensure valid JSON parsing and search works
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .with(adminJwt(adminId))
                        .param("targetType", "SUPPORT_CASE")
                        .param("targetId", caseId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].metadataJson").isNotEmpty());
    }

    @Test
    @DisplayName("P2.C Collision-Free Support Cases: Support case numbers are sequence-backed and collision-free")
    void testSupportCase_ConcurrentCreationNoCollision() throws Exception {
        UUID adminId = createAdmin("seq_admin@fitcoach.vn");
        UUID requesterId = createUser("seq_user@fitcoach.vn", "Vũ Sequence");

        Set<String> generatedCaseNumbers = Collections.synchronizedSet(new java.util.HashSet<>());
        int count = 10;

        for (int i = 0; i < count; i++) {
            String createJson = """
                    {
                        "requesterId": "%s",
                        "subject": "Case tuần tự số %d",
                        "description": "Mô tả trường hợp kiểm thử sequence",
                        "priority": "LOW"
                    }
                    """.formatted(requesterId, i);

            MvcResult res = mockMvc.perform(post("/api/v1/admin/support-cases")
                            .with(adminJwt(adminId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createJson))
                    .andExpect(status().isCreated())
                    .andReturn();

            String caseNumber = objectMapper.readTree(res.getResponse().getContentAsString())
                    .get("caseNumber").asText();

            assertThat(caseNumber).startsWith("SUP-");
            boolean added = generatedCaseNumbers.add(caseNumber);
            assertThat(added)
                    .withFailMessage("Collision detected for case number: %s", caseNumber)
                    .isTrue();
        }

        assertThat(generatedCaseNumbers).hasSize(count);
    }
}
