package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.service.CoachingQrRateLimiter;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CoachingQrIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("coaching_qr").withUsername("fitness_app").withPassword("testpass123");
    static { postgres.start(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired CoachingAuthorityQuery authority;
    @Autowired CoachingQrRateLimiter rateLimiter;

    @BeforeEach
    void resetLimiter() {
        rateLimiter.resetForTesting();
    }

    @Test
    void getIsPureReadAndConcurrentPostIssuesSingleActiveQr() throws Exception {
        UUID student = person("STUDENT", "QR Student", "qr.student.concurrent@example.com");

        mvc.perform(get("/api/v1/coaching/qr/me").param("role", "STUDENT").with(jwtFor(student)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COACHING_QR_NOT_FOUND"));

        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return mvc.perform(post("/api/v1/coaching/qr/me")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"STUDENT\"}")
                                .with(jwtFor(student)))
                        .andReturn();
            });
        }
        List<Future<MvcResult>> futures = tasks.stream().map(pool::submit).toList();
        ready.await();
        start.countDown();
        List<String> codes = new ArrayList<>();
        int createdCount = 0;
        for (Future<MvcResult> future : futures) {
            MvcResult res = future.get();
            assertThat(res.getResponse().getStatus()).isIn(200, 201);
            if (res.getResponse().getStatus() == 201) {
                createdCount++;
            }
            JsonNode json = mapper.readTree(res.getResponse().getContentAsString());
            codes.add(json.get("publicCode").asText());
        }
        pool.shutdown();

        assertThat(createdCount).isEqualTo(1);
        assertThat(codes).hasSize(threads);
        assertThat(codes.stream().distinct().count()).isEqualTo(1);
        assertThat(codes.get(0)).matches("^[A-Za-z0-9_-]{32}$");

        Integer activeRows = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.coaching_qr_codes WHERE user_id = ? AND capability_role = 'STUDENT' AND status = 'ACTIVE'",
                Integer.class, student);
        assertThat(activeRows).isEqualTo(1);

        mvc.perform(get("/api/v1/coaching/qr/me").param("role", "STUDENT").with(jwtFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicCode").value(codes.get(0)))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void rotateMonotonicVersionIdempotencyAndReplayAfterSubsequentRotation() throws Exception {
        UUID trainer = person("TRAINER", "QR Rotate PT", "qr.rotate.pt@example.com");

        MvcResult issued = mvc.perform(post("/api/v1/coaching/qr/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TRAINER\"}")
                        .with(jwtFor(trainer)))
                .andExpect(status().isCreated())
                .andReturn();
        String code1 = mapper.readTree(issued.getResponse().getContentAsString()).get("publicCode").asText();

        UUID rotateKey1 = UUID.randomUUID();
        MvcResult rot1 = mvc.perform(post("/api/v1/coaching/qr/me/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TRAINER\",\"expectedVersion\":1,\"commandKey\":\"" + rotateKey1 + "\"}")
                        .with(jwtFor(trainer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andReturn();
        String code2 = mapper.readTree(rot1.getResponse().getContentAsString()).get("publicCode").asText();
        assertThat(code2).isNotEqualTo(code1);

        // Exact retry while Q2 is still ACTIVE returns Q2
        mvc.perform(post("/api/v1/coaching/qr/me/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TRAINER\",\"expectedVersion\":1,\"commandKey\":\"" + rotateKey1 + "\"}")
                        .with(jwtFor(trainer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicCode").value(code2))
                .andExpect(jsonPath("$.version").value(2));

        // Same commandKey with different payload -> 409 COACHING_IDEMPOTENCY_CONFLICT
        mvc.perform(post("/api/v1/coaching/qr/me/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TRAINER\",\"expectedVersion\":2,\"commandKey\":\"" + rotateKey1 + "\"}")
                        .with(jwtFor(trainer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COACHING_IDEMPOTENCY_CONFLICT"));

        // Stale expectedVersion -> 409 COACHING_QR_VERSION_CONFLICT
        mvc.perform(post("/api/v1/coaching/qr/me/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TRAINER\",\"expectedVersion\":1,\"commandKey\":\"" + UUID.randomUUID() + "\"}")
                        .with(jwtFor(trainer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COACHING_QR_VERSION_CONFLICT"));

        // Second rotation: Q2 -> Q3 (version 3)
        UUID rotateKey2 = UUID.randomUUID();
        mvc.perform(post("/api/v1/coaching/qr/me/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TRAINER\",\"expectedVersion\":2,\"commandKey\":\"" + rotateKey2 + "\"}")
                        .with(jwtFor(trainer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));

        // Replay of rotateKey1 after Q2 was revoked by Q3 MUST NOT return Q2 as active
        mvc.perform(post("/api/v1/coaching/qr/me/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TRAINER\",\"expectedVersion\":1,\"commandKey\":\"" + rotateKey1 + "\"}")
                        .with(jwtFor(trainer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COACHING_QR_VERSION_CONFLICT"));

        // Audit records exist and never leak raw publicCode
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND action IN ('COACHING_QR_ISSUED','COACHING_QR_ROTATED')",
                Integer.class, trainer);
        assertThat(auditCount).isEqualTo(3);
        Integer leaked = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id = ? AND (after_data::text LIKE ? OR metadata::text LIKE ?)",
                Integer.class, trainer, "%" + code2 + "%", "%" + code2 + "%");
        assertThat(leaked).isZero();
    }

    @Test
    void resolveBidirectionalConcealmentRoleCheckAndB03FlowWithoutHealthDataGrant() throws Exception {
        UUID student = person("STUDENT", "QR Flow Student", "qr.flow.student@example.com");
        UUID trainer = person("TRAINER", "QR Flow Trainer", "qr.flow.trainer@example.com");
        seedTrainerSpecialty(trainer, "Hypertrophy");

        String trainerCode = issueAndGetCode(trainer, "TRAINER");
        String studentCode = issueAndGetCode(student, "STUDENT");

        // Student resolves Trainer QR -> minimal profile + VERIFIED + specialties + NONE
        mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + trainerCode + "\",\"expectedTargetRole\":\"TRAINER\",\"payloadVersion\":1}")
                        .with(jwtFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counterpartyId").value(trainer.toString()))
                .andExpect(jsonPath("$.targetRole").value("TRAINER"))
                .andExpect(jsonPath("$.displayName").value("QR Flow Trainer"))
                .andExpect(jsonPath("$.avatarUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.trainerSummary.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.trainerSummary.specialties[0]").value("Hypertrophy"))
                .andExpect(jsonPath("$.trainerSummary.isAcceptingStudents").value(true))
                .andExpect(jsonPath("$.relationshipState.status").value("NONE"))
                .andExpect(jsonPath("$.relationshipState.canInitiate").value(true));

        // Resolve does NOT create relationship or grant health data
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM fitness.coaching_relationships WHERE student_id = ? AND trainer_id = ?",
                Integer.class, student, trainer)).isZero();

        // Trainer resolves Student QR
        mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + studentCode + "\",\"expectedTargetRole\":\"STUDENT\",\"payloadVersion\":1}")
                        .with(jwtFor(trainer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counterpartyId").value(student.toString()))
                .andExpect(jsonPath("$.targetRole").value("STUDENT"))
                .andExpect(jsonPath("$.trainerSummary").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.relationshipState.canInitiate").value(true));

        // Single-role student trying to resolve as TRAINER (expectedTargetRole=STUDENT) -> 403 TRAINER_CAPABILITY_UNAVAILABLE
        mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + studentCode + "\",\"expectedTargetRole\":\"STUDENT\",\"payloadVersion\":1}")
                        .with(jwtFor(student)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TRAINER_CAPABILITY_UNAVAILABLE"));

        // Dual-role user self-scan -> 400 COACHING_QR_SELF_SCAN
        UUID dual = person("STUDENT", "Dual User", "qr.dual@example.com");
        grantTrainerCapability(dual);
        String dualStudentCode = issueAndGetCode(dual, "STUDENT");
        mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + dualStudentCode + "\",\"expectedTargetRole\":\"STUDENT\",\"payloadVersion\":1}")
                        .with(jwtFor(dual)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COACHING_QR_SELF_SCAN"));

        // Role mismatch -> 409 COACHING_QR_ROLE_MISMATCH
        mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + dualStudentCode + "\",\"expectedTargetRole\":\"TRAINER\",\"payloadVersion\":1}")
                        .with(jwtFor(student)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COACHING_QR_ROLE_MISMATCH"));

        // Revoked role in DB while profile still exists -> concealed 404 COACHING_QR_NOT_FOUND
        jdbc.update("""
                UPDATE fitness.user_roles ur SET revoked_at = now()
                FROM fitness.roles r
                WHERE ur.role_id = r.id AND ur.user_id = ? AND r.code = 'STUDENT'
                """, dual);
        mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + dualStudentCode + "\",\"expectedTargetRole\":\"TRAINER\",\"payloadVersion\":1}")
                        .with(jwtFor(student)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COACHING_QR_NOT_FOUND"));

        // Send B03 request -> PENDING; resolve reflects PENDING + canInitiate=false; health data still denied
        var pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID()).relationship();
        mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + trainerCode + "\",\"expectedTargetRole\":\"TRAINER\",\"payloadVersion\":1}")
                        .with(jwtFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationshipState.status").value("PENDING"))
                .andExpect(jsonPath("$.relationshipState.relationshipId").value(pending.id().toString()))
                .andExpect(jsonPath("$.relationshipState.direction").value("OUTGOING"))
                .andExpect(jsonPath("$.relationshipState.canInitiate").value(false));

        assertThat(authority.evaluateCurrent(new AuthorityRequest(trainer, student, DataScope.WORKOUT_HISTORY, DataAccessLevel.VIEW)).allowed())
                .isFalse();

        // Accept -> ACTIVE + HUMAN_COACH period, health data still requires explicit sharing grant
        lifecycle.relationshipAction(trainer, pending.id(), "ACCEPT", pending.version(), null, UUID.randomUUID());
        assertThat(authority.evaluateCurrent(new AuthorityRequest(trainer, student, DataScope.WORKOUT_HISTORY, DataAccessLevel.VIEW)).allowed())
                .isFalse();
    }

    @Test
    void httpEndToEndBidirectionalQrResolveRequestInvitePendingAndDecision() throws Exception {
        UUID student = person("STUDENT", "HTTP Student", "qr.http.student@example.com");
        UUID trainer = person("TRAINER", "HTTP Trainer", "qr.http.trainer@example.com");
        String trainerCode = issueAndGetCode(trainer, "TRAINER");
        String studentCode = issueAndGetCode(student, "STUDENT");

        // 1. Student resolves Trainer QR via HTTP, sends request via HTTP, Trainer sees INCOMING and accepts via HTTP
        MvcResult resolvedPt = mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + trainerCode + "\",\"expectedTargetRole\":\"TRAINER\",\"payloadVersion\":1}")
                        .with(jwtFor(student)))
                .andExpect(status().isOk())
                .andReturn();
        String counterpartyTrainerId = mapper.readTree(resolvedPt.getResponse().getContentAsString()).get("counterpartyId").asText();

        MvcResult createdReq = mvc.perform(post("/api/v1/coaching/relationships/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartyId\":\"" + counterpartyTrainerId + "\",\"commandKey\":\"" + UUID.randomUUID() + "\"}")
                        .with(jwtFor(student)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relationship.status").value("PENDING"))
                .andReturn();
        JsonNode reqNode = mapper.readTree(createdReq.getResponse().getContentAsString()).get("relationship");
        String relId = reqNode.get("id").asText();
        long version = reqNode.get("version").asLong();

        mvc.perform(get("/api/v1/coaching/relationships/me/pending")
                        .param("direction", "INCOMING")
                        .with(jwtFor(trainer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(relId))
                .andExpect(jsonPath("$.items[0].direction").value("INCOMING"));

        mvc.perform(post("/api/v1/coaching/relationships/" + relId + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + version + ",\"commandKey\":\"" + UUID.randomUUID() + "\"}")
                        .with(jwtFor(trainer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationship.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentPeriod.mode").value("HUMAN_COACH"));

        // 2. Trainer resolves another Student QR via HTTP, sends invitation, Student rejects via HTTP
        UUID student2 = person("STUDENT", "HTTP Student 2", "qr.http.student2@example.com");
        String student2Code = issueAndGetCode(student2, "STUDENT");
        MvcResult resolvedStudent2 = mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + student2Code + "\",\"expectedTargetRole\":\"STUDENT\",\"payloadVersion\":1}")
                        .with(jwtFor(trainer)))
                .andExpect(status().isOk())
                .andReturn();
        String counterpartyStudent2Id = mapper.readTree(resolvedStudent2.getResponse().getContentAsString()).get("counterpartyId").asText();

        MvcResult createdInvite = mvc.perform(post("/api/v1/coaching/relationships/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartyId\":\"" + counterpartyStudent2Id + "\",\"commandKey\":\"" + UUID.randomUUID() + "\"}")
                        .with(jwtFor(trainer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relationship.status").value("PENDING"))
                .andReturn();
        JsonNode invNode = mapper.readTree(createdInvite.getResponse().getContentAsString()).get("relationship");
        String invRelId = invNode.get("id").asText();
        long invVersion = invNode.get("version").asLong();

        mvc.perform(post("/api/v1/coaching/relationships/" + invRelId + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + invVersion + ",\"commandKey\":\"" + UUID.randomUUID() + "\"}")
                        .with(jwtFor(student2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationship.status").value("REJECTED"));

        // Keep studentCode referenced to verify both directions issued
        assertThat(studentCode).hasSize(32);
    }

    @Test
    void resolveRateLimitReturns429WithRetryAfter() throws Exception {
        UUID student = person("STUDENT", "Rate Limit Student", "qr.ratelimit@example.com");
        String fakeCode = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/v1/coaching/qr/resolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"publicCode\":\"" + fakeCode + "\",\"expectedTargetRole\":\"TRAINER\",\"payloadVersion\":1}")
                            .with(jwtFor(student)))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + fakeCode + "\",\"expectedTargetRole\":\"TRAINER\",\"payloadVersion\":1}")
                        .with(jwtFor(student)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void inactiveTrainerQrIsConcealedAsNotFoundEvenBeforeRoleMismatch() throws Exception {
        UUID inactiveTrainer = person("TRAINER", "Inactive Trainer", "qr.inactivetrainer@example.com");
        String trainerCode = issueAndGetCode(inactiveTrainer, "TRAINER");
        jdbc.update("UPDATE fitness.trainer_profiles SET is_active = false WHERE user_id = ?", inactiveTrainer);

        UUID otherTrainer = person("TRAINER", "Other Trainer", "qr.othertrainer@example.com");
        UUID student = person("STUDENT", "Student Conceal", "qr.studentconceal@example.com");

        // Opposing trainer scanning with expectedTargetRole=STUDENT must get 404 (concealed), NOT 409 role-mismatch
        mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + trainerCode + "\",\"expectedTargetRole\":\"STUDENT\",\"payloadVersion\":1}")
                        .with(jwtFor(otherTrainer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COACHING_QR_NOT_FOUND"));

        // Student scanning with expectedTargetRole=TRAINER must also get 404 (concealed), NOT 403
        mvc.perform(post("/api/v1/coaching/qr/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicCode\":\"" + trainerCode + "\",\"expectedTargetRole\":\"TRAINER\",\"payloadVersion\":1}")
                        .with(jwtFor(student)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COACHING_QR_NOT_FOUND"));
    }

    private String issueAndGetCode(UUID userId, String role) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/coaching/qr/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role + "\"}")
                        .with(jwtFor(userId)))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(res.getResponse().getContentAsString()).get("publicCode").asText();
    }

    private UUID person(String role, String name, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status)
                VALUES (?, ?, 'hash', ?, 'ACTIVE'::fitness.account_status)
                """, id, email, name);
        jdbc.update("""
                INSERT INTO fitness.user_roles (user_id, role_id)
                SELECT ?, id FROM fitness.roles WHERE code = ?
                """, id, role);
        if ("STUDENT".equals(role)) {
            jdbc.update("INSERT INTO fitness.student_profiles (user_id) VALUES (?)", id);
        } else {
            grantTrainerCapability(id);
        }
        return id;
    }

    private void grantTrainerCapability(UUID id) {
        jdbc.update("""
                INSERT INTO fitness.user_roles (user_id, role_id)
                SELECT ?, id FROM fitness.roles WHERE code = 'TRAINER'
                ON CONFLICT DO NOTHING
                """, id);
        jdbc.update("""
                INSERT INTO fitness.trainer_profiles
                (user_id, verification_status, activity_status, is_accepting_students, is_active, years_experience)
                VALUES (?, 'VERIFIED'::fitness.trainer_verification_state, 'ACTIVE'::fitness.trainer_activity_status, true, true, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, id, new BigDecimal("3.5"));
    }

    private void seedTrainerSpecialty(UUID trainerId, String name) {
        String code = name.toUpperCase().replace(' ', '_');
        jdbc.update("""
                INSERT INTO fitness.trainer_specialties (code, name)
                VALUES (?, ?) ON CONFLICT (code) DO NOTHING
                """, code, name);
        jdbc.update("""
                INSERT INTO fitness.trainer_specialty_assignments (trainer_id, specialty_id, is_primary)
                SELECT ?, id, true FROM fitness.trainer_specialties WHERE code = ?
                """, trainerId, code);
    }

    private static RequestPostProcessor jwtFor(UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()));
    }
}
