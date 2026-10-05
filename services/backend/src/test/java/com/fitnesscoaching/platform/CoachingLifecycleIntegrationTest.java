package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.CoachingFailure;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingStore;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Outcome;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerAvailabilityQuery;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CoachingLifecycleIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("coaching_lifecycle").withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean AuditService audit;
    @MockitoSpyBean CoachingStore coachingStore;
    @MockitoSpyBean TrainerAvailabilityQuery availability;
    @Autowired javax.sql.DataSource dataSource;

    @BeforeEach
    void clearSpies() { reset(audit, coachingStore, availability); }

    private UUID person(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users (id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'test-hash','Test Person','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')
                """, id, id + "@example.com");
        jdbc.update("""
                INSERT INTO fitness.user_roles (user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,now() FROM fitness.roles WHERE code=?
                """, id, id, role);
        if (role.equals("STUDENT")) {
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        } else {
            jdbc.update("""
                    INSERT INTO fitness.trainer_profiles(user_id,verification_status,verified_at,is_accepting_students,is_active)
                    VALUES (?,'VERIFIED',now(),true,true)
                    """, id);
        }
        return id;
    }

    private UUID dualRolePerson() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users (id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'test-hash','Dual Role Person','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')
                """, id, id + "@example.com");
        jdbc.update("""
                INSERT INTO fitness.user_roles (user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,now() FROM fitness.roles WHERE code IN ('STUDENT','TRAINER')
                """, id, id);
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        jdbc.update("""
                INSERT INTO fitness.trainer_profiles(user_id,verification_status,verified_at,is_accepting_students,is_active)
                VALUES (?,'VERIFIED',now(),true,true)
                """, id);
        return id;
    }

    private Outcome accepted(UUID student, UUID trainer) {
        Outcome pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        return lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT", 0, null, UUID.randomUUID());
    }

    @Test
    void bilateralLifecyclePreservesPeriodsAndIdempotentReplay() {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        UUID requestKey = UUID.randomUUID();
        Outcome requested = lifecycle.initiate(student, student, trainer, requestKey);
        assertThat(requested.relationship().directionFor(student)).isEqualTo("OUTGOING");
        assertThat(lifecycle.initiate(student, student, trainer, requestKey)).isEqualTo(requested);
        UUID anotherTrainer = person("TRAINER");
        assertThatThrownBy(() -> lifecycle.initiate(student, student, anotherTrainer, requestKey))
                .isInstanceOf(CoachingFailure.class).extracting("code")
                .isEqualTo("COACHING_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> lifecycle.relationshipAction(student, requested.relationship().id(),
                "ACCEPT", 0, null, UUID.randomUUID())).isInstanceOf(CoachingFailure.class)
                .extracting("code").isEqualTo("COACHING_COUNTERPARTY_REQUIRED");
        Outcome active = lifecycle.relationshipAction(trainer, requested.relationship().id(),
                "ACCEPT", 0, null, UUID.randomUUID());
        assertThat(active.currentPeriod().mode()).isEqualTo("HUMAN_COACH");
        assertThatThrownBy(() -> lifecycle.relationshipAction(trainer, requested.relationship().id(),
                "PAUSE", 0, null, UUID.randomUUID())).isInstanceOf(CoachingFailure.class)
                .extracting("code").isEqualTo("COACHING_VERSION_CONFLICT");
        Outcome paused = lifecycle.relationshipAction(student, requested.relationship().id(),
                "PAUSE", 1, null, UUID.randomUUID());
        assertThat(paused.currentPeriod().mode()).isEqualTo("SELF_DIRECTED");
        Outcome resume = lifecycle.createResume(trainer, requested.relationship().id(), 2, null, UUID.randomUUID());
        assertThat(resume.relationship().status()).isEqualTo("PAUSED");
        assertThat(resume.resume().status()).isEqualTo("PENDING");
        assertThatThrownBy(() -> lifecycle.resumeAction(trainer, requested.relationship().id(), resume.resume().id(),
                "ACCEPT", 2, 0, UUID.randomUUID())).isInstanceOf(CoachingFailure.class)
                .extracting("code").isEqualTo("COACHING_COUNTERPARTY_REQUIRED");
        Outcome resumed = lifecycle.resumeAction(student, requested.relationship().id(), resume.resume().id(),
                "ACCEPT", 2, 0, UUID.randomUUID());
        assertThat(resumed.relationship().status()).isEqualTo("ACTIVE");
        assertThat(resumed.resume().status()).isEqualTo("ACCEPTED");
        assertThat(resumed.currentPeriod().id()).isNotEqualTo(active.currentPeriod().id());
        Outcome ended = lifecycle.relationshipAction(trainer, requested.relationship().id(),
                "END", 3, "done", UUID.randomUUID());
        assertThat(ended.currentPeriod()).isNull();
        assertThat(lifecycle.current(student).currentPeriod().mode()).isEqualTo("SELF_DIRECTED");
        assertThat(lifecycle.periods(student, 0, 10)).hasSize(4);
        assertThat(lifecycle.history(student, requested.relationship().id(), 0, 10)).hasSize(5);
    }

    @Test
    void invitationAndResumeRejectionCancellationAndEndInvalidation() {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome invitation = lifecycle.initiate(trainer, student, trainer, UUID.randomUUID());
        assertThat(invitation.relationship().directionFor(student)).isEqualTo("INCOMING");
        assertThat(lifecycle.pending(student, "INCOMING", 0, 20)).extracting("id")
                .contains(invitation.relationship().id());
        lifecycle.relationshipAction(student, invitation.relationship().id(), "REJECT", 0, null, UUID.randomUUID());
        Outcome pending = lifecycle.initiate(trainer, student, trainer, UUID.randomUUID());
        lifecycle.relationshipAction(trainer, pending.relationship().id(), "CANCEL", 0, null, UUID.randomUUID());
        Outcome active = accepted(student, trainer);
        Outcome paused = lifecycle.relationshipAction(trainer, active.relationship().id(), "PAUSE", 1, null, UUID.randomUUID());
        Outcome resume = lifecycle.createResume(student, active.relationship().id(), 2, null, UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO fitness.coaching_resume_requests(relationship_id,requested_by)
                VALUES (?,?)
                """, active.relationship().id(), trainer)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> lifecycle.createResume(trainer, active.relationship().id(), 2, null, UUID.randomUUID()))
                .isInstanceOf(CoachingFailure.class).extracting("code")
                .isEqualTo("COACHING_RESUME_ALREADY_PENDING");
        lifecycle.resumeAction(trainer, active.relationship().id(), resume.resume().id(), "REJECT", 2, 0, UUID.randomUUID());
        Outcome second = lifecycle.createResume(trainer, active.relationship().id(), 2, null, UUID.randomUUID());
        lifecycle.resumeAction(trainer, active.relationship().id(), second.resume().id(), "CANCEL", 2, 0, UUID.randomUUID());
        Outcome third = lifecycle.createResume(student, active.relationship().id(), 2, null, UUID.randomUUID());
        lifecycle.relationshipAction(student, active.relationship().id(), "END", 2, "finished", UUID.randomUUID());
        assertThat(lifecycle.detail(student, active.relationship().id()).relationship().status()).isEqualTo("ENDED");
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.coaching_resume_requests WHERE id=?", String.class,
                third.resume().id())).isEqualTo("CANCELLED");
        assertThat(lifecycle.resumeHistory(student, active.relationship().id(), 0, 20))
                .extracting("status").containsExactlyInAnyOrder("REJECTED", "CANCELLED", "CANCELLED");
        assertThatThrownBy(() -> lifecycle.resumeAction(trainer, active.relationship().id(), third.resume().id(),
                "ACCEPT", 2, 0, UUID.randomUUID())).isInstanceOf(CoachingFailure.class)
                .extracting("code").isEqualTo("COACHING_VERSION_CONFLICT");
        assertThat(paused.currentPeriod()).isNull();
        assertThat(lifecycle.current(student).currentPeriod().mode()).isEqualTo("SELF_DIRECTED");
        assertThatThrownBy(() -> lifecycle.detail(trainer, active.relationship().id()))
                .isInstanceOf(CoachingFailure.class).extracting("code")
                .isEqualTo("COACHING_RELATIONSHIP_NOT_FOUND");
    }

    @Test
    void databaseConstraintsAndConcealmentHold() throws Exception {
        UUID student = person("STUDENT"), trainerA = person("TRAINER"), trainerB = person("TRAINER");
        Outcome first = accepted(student, trainerA);
        Outcome second = lifecycle.initiate(student, student, trainerB, UUID.randomUUID());
        assertThatThrownBy(() -> lifecycle.relationshipAction(trainerB, second.relationship().id(),
                "ACCEPT", 0, null, UUID.randomUUID())).isInstanceOf(CoachingFailure.class)
                .extracting("code").isEqualTo("COACHING_STUDENT_ALREADY_ASSIGNED");
        assertThatThrownBy(() -> lifecycle.initiate(student, student, trainerB, UUID.randomUUID()))
                .isInstanceOf(CoachingFailure.class).extracting("code")
                .isEqualTo("COACHING_REQUEST_ALREADY_PENDING");
        assertThatThrownBy(() -> lifecycle.detail(trainerB, first.relationship().id()))
                .isInstanceOf(CoachingFailure.class).extracting("code")
                .isEqualTo("COACHING_RELATIONSHIP_NOT_FOUND");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM fitness.coaching_relationships WHERE id=?",
                first.relationship().id())).isInstanceOf(DataAccessException.class);
        mvc.perform(get("/api/v1/coaching/relationships/me/current")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/coaching/relationships/" + first.relationship().id())
                        .with(jwt().jwt(j -> j.subject(trainerB.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COACHING_RELATIONSHIP_NOT_FOUND"));
        mvc.perform(post("/api/v1/coaching/relationships/requests")
                        .with(jwt().jwt(j -> j.subject(student.toString())))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void commandResponsesAndReceiptsNeverExposeAnotherTrainersOrRevokedPeriod() throws Exception {
        UUID student = person("STUDENT"), trainerA = person("TRAINER"), trainerB = person("TRAINER");
        Outcome active = accepted(student, trainerA);
        UUID inviteKey = UUID.randomUUID();
        String inviteBody = "{\"counterpartyId\":\"%s\",\"commandKey\":\"%s\"}"
                .formatted(student, inviteKey);
        var invitation = mvc.perform(post("/api/v1/coaching/relationships/invitations")
                        .with(jwt().jwt(j -> j.subject(trainerB.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(inviteBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentPeriod").isEmpty())
                .andReturn();
        String invitationJson = invitation.getResponse().getContentAsString();
        assertThat(invitationJson).doesNotContain(active.relationship().id().toString(),
                trainerA.toString(), active.currentPeriod().id().toString());
        UUID invitationId = UUID.fromString(mapper.readTree(invitationJson)
                .get("relationship").get("id").asText());
        mvc.perform(post("/api/v1/coaching/relationships/invitations")
                        .with(jwt().jwt(j -> j.subject(trainerB.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(inviteBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentPeriod").isEmpty());
        assertReceiptHasNoPeriod(trainerB, inviteKey);

        UUID cancelKey = UUID.randomUUID();
        String cancelBody = "{\"expectedVersion\":0,\"commandKey\":\"%s\"}".formatted(cancelKey);
        for (int attempt = 0; attempt < 2; attempt++) {
            var cancelled = mvc.perform(post("/api/v1/coaching/relationships/{id}/cancel", invitationId)
                            .with(jwt().jwt(j -> j.subject(trainerB.toString())))
                            .contentType(MediaType.APPLICATION_JSON).content(cancelBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currentPeriod").isEmpty())
                    .andReturn();
            assertThat(cancelled.getResponse().getContentAsString())
                    .doesNotContain(active.relationship().id().toString(), trainerA.toString(),
                            active.currentPeriod().id().toString());
        }
        assertReceiptHasNoPeriod(trainerB, cancelKey);

        UUID studentRequestKey = UUID.randomUUID();
        Outcome studentRequest = lifecycle.initiate(student, student, trainerB, studentRequestKey);
        assertThat(studentRequest.currentPeriod().id()).isEqualTo(active.currentPeriod().id());
        assertReceiptHasNoPeriod(student, studentRequestKey);

        UUID pauseKey = UUID.randomUUID();
        Outcome paused = lifecycle.relationshipAction(trainerA, active.relationship().id(),
                "PAUSE", 1, null, pauseKey);
        assertThat(paused.currentPeriod()).isNull();
        assertThat(lifecycle.relationshipAction(trainerA, active.relationship().id(),
                "PAUSE", 1, null, pauseKey).currentPeriod()).isNull();
        assertThat(lifecycle.current(student).currentPeriod().mode()).isEqualTo("SELF_DIRECTED");
        assertReceiptHasNoPeriod(trainerA, pauseKey);
        UUID endKey = UUID.randomUUID();
        Outcome ended = lifecycle.relationshipAction(trainerA, active.relationship().id(),
                "END", 2, "finished", endKey);
        assertThat(ended.currentPeriod()).isNull();
        assertReceiptHasNoPeriod(trainerA, endKey);
    }

    @Test
    void replayRechecksCurrentCapabilityAndRedactsPreviouslyVisiblePeriod() {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        UUID acceptKey = UUID.randomUUID();
        Outcome active = lifecycle.relationshipAction(trainer, pending.relationship().id(),
                "ACCEPT", 0, null, acceptKey);
        assertThat(active.currentPeriod()).isNotNull();
        assertReceiptHasNoPeriod(trainer, acceptKey);
        lifecycle.relationshipAction(student, pending.relationship().id(),
                "PAUSE", 1, null, UUID.randomUUID());
        assertThat(lifecycle.relationshipAction(trainer, pending.relationship().id(),
                "ACCEPT", 0, null, acceptKey).currentPeriod()).isNull();
        jdbc.update("""
                UPDATE fitness.user_roles SET revoked_at=now()
                WHERE user_id=? AND role_id=(SELECT id FROM fitness.roles WHERE code='TRAINER')
                """, trainer);
        assertThatThrownBy(() -> lifecycle.relationshipAction(trainer, pending.relationship().id(),
                "ACCEPT", 0, null, acceptKey))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Trainer capability unavailable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_relationship_status_history WHERE relationship_id=?",
                Integer.class, pending.relationship().id())).isEqualTo(3);
    }

    @Test
    void finiteFutureEndedPeriodsAreEffectiveAndCanBeClosedForTransitions() {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        UUID selfId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,ended_at,created_by)
                VALUES (?,?,'SELF_DIRECTED',now()-interval '1 day',now()+interval '1 day',?)
                """, selfId, student, student);
        assertThat(lifecycle.current(student).currentPeriod().id()).isEqualTo(selfId);
        Outcome pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        Outcome accepted = lifecycle.relationshipAction(trainer, pending.relationship().id(),
                "ACCEPT", 0, null, UUID.randomUUID());
        assertThat(accepted.currentPeriod().mode()).isEqualTo("HUMAN_COACH");
        assertThat(jdbc.queryForObject("SELECT ended_at FROM fitness.coaching_periods WHERE id=?",
                Timestamp.class, selfId).toInstant()).isBefore(Instant.now());
        jdbc.update("UPDATE fitness.coaching_periods SET ended_at=now()+interval '1 day' WHERE id=?",
                accepted.currentPeriod().id());
        assertThat(lifecycle.detail(trainer, pending.relationship().id()).currentPeriod().id())
                .isEqualTo(accepted.currentPeriod().id());
        Outcome paused = lifecycle.relationshipAction(trainer, pending.relationship().id(),
                "PAUSE", 1, null, UUID.randomUUID());
        assertThat(paused.currentPeriod()).isNull();
        assertThat(lifecycle.current(student).currentPeriod().mode()).isEqualTo("SELF_DIRECTED");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE fitness.coaching_periods SET ended_at=now()+interval '1 day' WHERE id=?
                """, accepted.currentPeriod().id())).isInstanceOf(DataAccessException.class);
        UUID pausedSelfId = lifecycle.current(student).currentPeriod().id();
        jdbc.update("UPDATE fitness.coaching_periods SET ended_at=now()+interval '1 day' WHERE id=?", pausedSelfId);
        Outcome resume = lifecycle.createResume(trainer, pending.relationship().id(), 2, null, UUID.randomUUID());
        Outcome resumed = lifecycle.resumeAction(student, pending.relationship().id(), resume.resume().id(),
                "ACCEPT", 2, 0, UUID.randomUUID());
        assertThat(resumed.currentPeriod().mode()).isEqualTo("HUMAN_COACH");
        assertThat(resumed.currentPeriod().id()).isNotEqualTo(accepted.currentPeriod().id());
        assertThat(lifecycle.periods(student, 0, 10)).hasSize(4);
    }

    @Test
    void periodExpiringAfterSelectionReturnsStableConflictAndRollsBackEverything() throws Exception {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome active = accepted(student, trainer);
        UUID commandKey = UUID.randomUUID();
        jdbc.update("UPDATE fitness.coaching_periods SET ended_at=clock_timestamp()+interval '1 day' WHERE id=?",
                active.currentPeriod().id());
        reset(audit);
        doAnswer(invocation -> {
            expirePeriodInSeparateTransaction(active.currentPeriod().id());
            return invocation.callRealMethod();
        }).when(coachingStore).closePeriod(any(), any());

        mvc.perform(post("/api/v1/coaching/relationships/{id}/pause", active.relationship().id())
                        .with(jwt().jwt(j -> j.subject(trainer.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"commandKey\":\"%s\"}".formatted(commandKey)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COACHING_PERIOD_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Coaching operation could not be completed"));

        assertThat(jdbc.queryForObject("SELECT status FROM fitness.coaching_relationships WHERE id=?",
                String.class, active.relationship().id())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_relationship_status_history WHERE relationship_id=?",
                Integer.class, active.relationship().id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_periods WHERE student_id=?",
                Integer.class, student)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_command_receipts WHERE actor_id=? AND command_key=?",
                Integer.class, trainer, commandKey)).isZero();
        org.mockito.Mockito.verifyNoInteractions(audit);
    }

    @Test
    void legacyStatusHistoryWithNullActorIsReturnedAsExplicitNull() throws Exception {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome active = accepted(student, trainer);
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationship_status_history
                  (id,relationship_id,from_status,to_status,changed_by,reason,changed_at)
                VALUES (?,?,'ACTIVE','ACTIVE',NULL,'legacy import',clock_timestamp()+interval '1 second')
                """, eventId, active.relationship().id());

        mvc.perform(get("/api/v1/coaching/relationships/{id}/history", active.relationship().id())
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(eventId.toString()))
                .andExpect(jsonPath("$.items[0].changedBy").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void trainerAvailabilityAndAcceptanceSerializeInBothCommitOrders() throws Exception {
        UUID losingStudent = person("STUDENT"), losingTrainer = person("TRAINER");
        Outcome losingPending = lifecycle.initiate(losingStudent, losingStudent, losingTrainer, UUID.randomUUID());
        UUID losingKey = UUID.randomUUID();
        CountDownLatch writerUpdated = new CountDownLatch(1), releaseWriter = new CountDownLatch(1);
        AtomicInteger writerPid = new AtomicInteger(), acceptancePid = new AtomicInteger();
        CountDownLatch acceptanceLockedStudent = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (losingStudent.equals(invocation.getArgument(0))) {
                acceptancePid.set(currentBackendPid());
                acceptanceLockedStudent.countDown();
            }
            return result;
        }).when(coachingStore).lockStudent(losingStudent);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> updateAvailabilityInTransaction(
                    losingTrainer, false, new CountDownLatch(0), writerUpdated, releaseWriter,
                    writerPid, new AtomicReference<>()));
            assertThat(writerUpdated.await(5, TimeUnit.SECONDS)).isTrue();
            var acceptance = pool.submit(() -> attemptAccept(losingTrainer, losingPending.relationship().id(), losingKey));
            try {
                assertThat(acceptanceLockedStudent.await(5, TimeUnit.SECONDS)).isTrue();
                awaitBlockedBy(acceptancePid.get(), writerPid.get());
            } finally {
                releaseWriter.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
            assertThat(acceptance.get(10, TimeUnit.SECONDS)).isEqualTo("TRAINER_NOT_ELIGIBLE");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.coaching_relationships WHERE id=?",
                String.class, losingPending.relationship().id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_relationship_status_history WHERE relationship_id=?",
                Integer.class, losingPending.relationship().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_periods WHERE student_id=?",
                Integer.class, losingStudent)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_command_receipts WHERE actor_id=? AND command_key=?",
                Integer.class, losingTrainer, losingKey)).isZero();

        UUID winningStudent = person("STUDENT"), winningTrainer = person("TRAINER");
        Outcome winningPending = lifecycle.initiate(winningStudent, winningStudent, winningTrainer, UUID.randomUUID());
        CountDownLatch acceptHasLocks = new CountDownLatch(1), releaseAccept = new CountDownLatch(1);
        AtomicInteger winningAcceptancePid = new AtomicInteger();
        doAnswer(invocation -> {
            boolean result = (boolean) invocation.callRealMethod();
            winningAcceptancePid.set(currentBackendPid());
            acceptHasLocks.countDown();
            assertThat(releaseAccept.await(5, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(availability).isAcceptingStudents(winningTrainer);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var acceptance = pool.submit(() -> attemptAccept(
                    winningTrainer, winningPending.relationship().id(), UUID.randomUUID()));
            assertThat(acceptHasLocks.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch writerStarted = new CountDownLatch(1);
            AtomicInteger waitingWriterPid = new AtomicInteger();
            var writer = pool.submit(() -> updateAvailabilityInTransaction(
                    winningTrainer, false, writerStarted, new CountDownLatch(0), new CountDownLatch(0),
                    waitingWriterPid, new AtomicReference<>()));
            try {
                assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                awaitBlockedBy(waitingWriterPid.get(), winningAcceptancePid.get());
            } finally {
                releaseAccept.countDown();
            }
            assertThat(acceptance.get(10, TimeUnit.SECONDS)).isEqualTo("ACTIVE");
            writer.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.coaching_relationships WHERE id=?",
                String.class, winningPending.relationship().id())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT is_accepting_students FROM fitness.trainer_profiles WHERE user_id=?",
                Boolean.class, winningTrainer)).isFalse();
    }

    private Void updateAvailabilityInTransaction(UUID trainer, boolean accepting,
                                                  CountDownLatch started, CountDownLatch updated,
                                                  CountDownLatch release, AtomicInteger backendPid,
                                                  AtomicReference<Instant> beforeCommit) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement pidStatement = connection.createStatement();
                 ResultSet result = pidStatement.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(result.next()).isTrue();
                backendPid.set(result.getInt(1));
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE fitness.trainer_profiles SET is_accepting_students=? WHERE user_id=?")) {
                statement.setBoolean(1, accepting);
                statement.setObject(2, trainer);
                started.countDown();
                statement.executeUpdate();
                updated.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            try (Statement timeStatement = connection.createStatement();
                 ResultSet result = timeStatement.executeQuery("SELECT clock_timestamp()")) {
                assertThat(result.next()).isTrue();
                beforeCommit.set(result.getTimestamp(1).toInstant());
            }
            connection.commit();
        }
        return null;
    }

    private int currentBackendPid() {
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private void awaitBlockedBy(int waitingPid, int blockerPid) throws InterruptedException {
        assertThat(waitingPid).isPositive();
        assertThat(blockerPid).isPositive();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity activity
                        WHERE activity.pid = ? AND activity.wait_event_type = 'Lock'
                          AND CAST(? AS integer) = ANY(pg_blocking_pids(activity.pid))
                    )
                    """, Boolean.class, waitingPid, blockerPid);
            if (Boolean.TRUE.equals(blocked)) { return; }
            Thread.sleep(25);
        }
        throw new AssertionError("PostgreSQL backend " + waitingPid
                + " did not enter a lock wait behind backend " + blockerPid);
    }

    private void expirePeriodInSeparateTransaction(UUID periodId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE fitness.coaching_periods SET ended_at=clock_timestamp()-interval '1 millisecond' WHERE id=?")) {
            statement.setObject(1, periodId);
            statement.executeUpdate();
        }
    }

    private void assertReceiptHasNoPeriod(UUID actor, UUID key) {
        String json = jdbc.queryForObject("""
                SELECT response_json::text FROM fitness.coaching_command_receipts
                WHERE actor_id=? AND command_key=?
                """, String.class, actor, key);
        assertThat(mapper.readTree(json).get("currentPeriod").isNull()).isTrue();
    }

    @Test
    void oppositeDualRoleInvitationAcceptsDoNotDeadlockOnImplicitForeignKeyLocks() throws Exception {
        UUID userA = dualRolePerson(), userB = dualRolePerson();
        Outcome userBInvitesA = lifecycle.initiate(userB, userA, userB, UUID.randomUUID());
        Outcome userAInvitesB = lifecycle.initiate(userA, userB, userA, UUID.randomUUID());
        UUID acceptAKey = UUID.randomUUID(), acceptBKey = UUID.randomUUID();
        CyclicBarrier bothHoldTrainerCapabilityLocks = new CyclicBarrier(2);
        reset(availability);
        doAnswer(invocation -> {
            UUID trainerId = invocation.getArgument(0);
            boolean eligible = (boolean) invocation.callRealMethod();
            if (trainerId.equals(userA) || trainerId.equals(userB)) {
                bothHoldTrainerCapabilityLocks.await(5, TimeUnit.SECONDS);
            }
            return eligible;
        }).when(availability).isAcceptingStudents(any());

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> attemptAccept(userA, userBInvitesA.relationship().id(), acceptAKey));
            var second = pool.submit(() -> attemptAccept(userB, userAInvitesB.relationship().id(), acceptBKey));
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo("ACTIVE");
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo("ACTIVE");
        }

        for (Outcome invitation : java.util.List.of(userBInvitesA, userAInvitesB)) {
            UUID relationshipId = invitation.relationship().id();
            UUID studentId = invitation.relationship().studentId();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM fitness.coaching_relationships WHERE id=?",
                    String.class, relationshipId)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.coaching_relationship_status_history
                    WHERE relationship_id=?
                    """, Integer.class, relationshipId)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.coaching_periods
                    WHERE student_id=? AND mode='HUMAN_COACH' AND ended_at IS NULL
                    """, Integer.class, studentId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.audit_logs
                    WHERE target_id=? AND action='COACHING_RELATIONSHIP_ACCEPT'
                    """, Integer.class, relationshipId)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.coaching_command_receipts
                WHERE (actor_id=? AND command_key=?) OR (actor_id=? AND command_key=?)
                """, Integer.class, userA, acceptAKey, userB, acceptBKey)).isEqualTo(2);
    }

    @Test
    void resumeSamplesBoundaryAfterWaitingAvailabilityWriterCommits() throws Exception {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome active = accepted(student, trainer);
        lifecycle.relationshipAction(student, active.relationship().id(), "PAUSE", 1, null, UUID.randomUUID());
        Outcome resume = lifecycle.createResume(trainer, active.relationship().id(), 2, null, UUID.randomUUID());
        UUID commandKey = UUID.randomUUID();
        CountDownLatch writerUpdated = new CountDownLatch(1), releaseWriter = new CountDownLatch(1);
        CountDownLatch resumeLockedStudent = new CountDownLatch(1);
        AtomicInteger writerPid = new AtomicInteger(), resumePid = new AtomicInteger();
        AtomicReference<Instant> writerBeforeCommit = new AtomicReference<>();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (student.equals(invocation.getArgument(0))) {
                resumePid.set(currentBackendPid());
                resumeLockedStudent.countDown();
            }
            return result;
        }).when(coachingStore).lockStudent(student);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> updateAvailabilityInTransaction(
                    trainer, true, new CountDownLatch(0), writerUpdated, releaseWriter,
                    writerPid, writerBeforeCommit));
            assertThat(writerUpdated.await(5, TimeUnit.SECONDS)).isTrue();
            var acceptance = pool.submit(() -> attemptResume(
                    student, active.relationship().id(), resume.resume().id(), commandKey));
            try {
                assertThat(resumeLockedStudent.await(5, TimeUnit.SECONDS)).isTrue();
                awaitBlockedBy(resumePid.get(), writerPid.get());
            } finally {
                releaseWriter.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
            assertThat(acceptance.get(10, TimeUnit.SECONDS)).isEqualTo("ACTIVE");
        }

        Instant resumedAt = jdbc.queryForObject("""
                SELECT started_at FROM fitness.coaching_periods
                WHERE student_id=? AND mode='HUMAN_COACH'
                ORDER BY started_at DESC, id DESC LIMIT 1
                """, Timestamp.class, student).toInstant();
        assertThat(writerBeforeCommit.get()).isNotNull();
        assertThat(resumedAt).isAfter(writerBeforeCommit.get());
    }

    @Test
    void resumeRollsBackCleanlyAfterWaitingWriterMakesTrainerIneligible() throws Exception {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome active = accepted(student, trainer);
        lifecycle.relationshipAction(student, active.relationship().id(), "PAUSE", 1, null, UUID.randomUUID());
        Outcome resume = lifecycle.createResume(trainer, active.relationship().id(), 2, null, UUID.randomUUID());
        int historyBefore = jdbc.queryForObject("""
                SELECT count(*) FROM fitness.coaching_relationship_status_history WHERE relationship_id=?
                """, Integer.class, active.relationship().id());
        int periodsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.coaching_periods WHERE student_id=?", Integer.class, student);
        UUID commandKey = UUID.randomUUID();
        reset(audit);
        CountDownLatch writerUpdated = new CountDownLatch(1), releaseWriter = new CountDownLatch(1);
        CountDownLatch resumeLockedStudent = new CountDownLatch(1);
        AtomicInteger writerPid = new AtomicInteger(), resumePid = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (student.equals(invocation.getArgument(0))) {
                resumePid.set(currentBackendPid());
                resumeLockedStudent.countDown();
            }
            return result;
        }).when(coachingStore).lockStudent(student);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> updateAvailabilityInTransaction(
                    trainer, false, new CountDownLatch(0), writerUpdated, releaseWriter,
                    writerPid, new AtomicReference<>()));
            assertThat(writerUpdated.await(5, TimeUnit.SECONDS)).isTrue();
            var acceptance = pool.submit(() -> attemptResume(
                    student, active.relationship().id(), resume.resume().id(), commandKey));
            try {
                assertThat(resumeLockedStudent.await(5, TimeUnit.SECONDS)).isTrue();
                awaitBlockedBy(resumePid.get(), writerPid.get());
            } finally {
                releaseWriter.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
            assertThat(acceptance.get(10, TimeUnit.SECONDS)).isEqualTo("TRAINER_NOT_ELIGIBLE");
        }

        assertThat(jdbc.queryForObject(
                "SELECT status FROM fitness.coaching_relationships WHERE id=?",
                String.class, active.relationship().id())).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.coaching_relationship_status_history WHERE relationship_id=?
                """, Integer.class, active.relationship().id())).isEqualTo(historyBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM fitness.coaching_periods WHERE student_id=?",
                Integer.class, student)).isEqualTo(periodsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM fitness.coaching_resume_requests WHERE id=?",
                String.class, resume.resume().id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.coaching_command_receipts WHERE actor_id=? AND command_key=?
                """, Integer.class, student, commandKey)).isZero();
        org.mockito.Mockito.verifyNoInteractions(audit);
    }

    @Test
    void trainerVerificationWriterSerializesBeforeAcceptanceRecheck() throws Exception {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        UUID commandKey = UUID.randomUUID();
        CountDownLatch writerUpdated = new CountDownLatch(1), releaseWriter = new CountDownLatch(1);
        CountDownLatch acceptanceLockedStudent = new CountDownLatch(1);
        AtomicInteger writerPid = new AtomicInteger(), acceptancePid = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (student.equals(invocation.getArgument(0))) {
                acceptancePid.set(currentBackendPid());
                acceptanceLockedStudent.countDown();
            }
            return result;
        }).when(coachingStore).lockStudent(student);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> updateVerificationInTransaction(
                    trainer, writerUpdated, releaseWriter, writerPid));
            assertThat(writerUpdated.await(5, TimeUnit.SECONDS)).isTrue();
            var acceptance = pool.submit(() -> attemptAccept(trainer, pending.relationship().id(), commandKey));
            try {
                assertThat(acceptanceLockedStudent.await(5, TimeUnit.SECONDS)).isTrue();
                awaitBlockedBy(acceptancePid.get(), writerPid.get());
            } finally {
                releaseWriter.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
            assertThat(acceptance.get(10, TimeUnit.SECONDS)).isEqualTo("TRAINER_NOT_ELIGIBLE");
        }

        assertThat(jdbc.queryForObject(
                "SELECT status FROM fitness.coaching_relationships WHERE id=?",
                String.class, pending.relationship().id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.coaching_command_receipts WHERE actor_id=? AND command_key=?
                """, Integer.class, trainer, commandKey)).isZero();
    }

    private Void updateVerificationInTransaction(UUID trainer, CountDownLatch updated,
                                                 CountDownLatch release, AtomicInteger backendPid) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement pidStatement = connection.createStatement();
                 ResultSet result = pidStatement.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(result.next()).isTrue();
                backendPid.set(result.getInt(1));
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE fitness.trainer_profiles
                    SET verification_status='SUSPENDED'::fitness.trainer_verification_state
                    WHERE user_id=?
                    """)) {
                statement.setObject(1, trainer);
                statement.executeUpdate();
                updated.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            connection.commit();
        }
        return null;
    }

    @Test
    void competingAcceptsHaveOneWinner() throws Exception {
        UUID student = person("STUDENT"), trainerA = person("TRAINER"), trainerB = person("TRAINER");
        Outcome a = lifecycle.initiate(student, student, trainerA, UUID.randomUUID());
        Outcome b = lifecycle.initiate(student, student, trainerB, UUID.randomUUID());
        CountDownLatch gate = new CountDownLatch(1);
        Callable<String> acceptA = () -> { gate.await(); return attemptAccept(trainerA, a.relationship().id()); };
        Callable<String> acceptB = () -> { gate.await(); return attemptAccept(trainerB, b.relationship().id()); };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(acceptA); var two = pool.submit(acceptB); gate.countDown();
            assertThat(java.util.List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACTIVE", "COACHING_STUDENT_ALREADY_ASSIGNED");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_relationships WHERE student_id=? AND status='ACTIVE'",
                Integer.class, student)).isEqualTo(1);
    }

    @Test
    void resumeAcceptanceRacingEndHasOneAuthorityOutcome() throws Exception {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome active = accepted(student, trainer);
        lifecycle.relationshipAction(student, active.relationship().id(), "PAUSE", 1, null, UUID.randomUUID());
        Outcome resume = lifecycle.createResume(trainer, active.relationship().id(), 2, null, UUID.randomUUID());
        CountDownLatch gate = new CountDownLatch(1);
        Callable<String> accept = () -> {
            gate.await();
            try { return lifecycle.resumeAction(student, active.relationship().id(), resume.resume().id(),
                    "ACCEPT", 2, 0, UUID.randomUUID()).relationship().status(); }
            catch (CoachingFailure ex) { return ex.code(); }
        };
        Callable<String> end = () -> {
            gate.await();
            try { return lifecycle.relationshipAction(trainer, active.relationship().id(),
                    "END", 2, "finished", UUID.randomUUID()).relationship().status(); }
            catch (CoachingFailure ex) { return ex.code(); }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(accept); var two = pool.submit(end); gate.countDown();
            var results = java.util.List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
            assertThat(results).contains("COACHING_VERSION_CONFLICT");
            assertThat(results).anyMatch(value -> value.equals("ACTIVE") || value.equals("ENDED"));
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.coaching_periods WHERE student_id=? AND ended_at IS NULL
                """, Integer.class, student)).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackRelationshipAndHistory() {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        UUID key = UUID.randomUUID();
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordAudit(any());
        assertThatThrownBy(() -> lifecycle.initiate(student, student, trainer, key))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_relationships WHERE student_id=?",
                Integer.class, student)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_command_receipts WHERE actor_id=? AND command_key=?",
                Integer.class, student, key)).isZero();
    }

    @Test
    void webRequestAcceptReplayAndStableConflict() throws Exception {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        String requestBody = "{\"counterpartyId\":\"%s\",\"commandKey\":\"%s\"}"
                .formatted(trainer, UUID.randomUUID());
        UUID otherTrainer = person("TRAINER");
        mvc.perform(post("/api/v1/coaching/relationships/requests")
                        .with(jwt().jwt(j -> j.subject(trainer.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartyId\":\"%s\",\"commandKey\":\"%s\"}"
                                .formatted(otherTrainer, UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("STUDENT_CAPABILITY_UNAVAILABLE"));
        var created = mvc.perform(post("/api/v1/coaching/relationships/requests")
                        .with(jwt().jwt(j -> j.subject(student.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relationship.status").value("PENDING"))
                .andExpect(jsonPath("$.relationship.direction").value("OUTGOING"))
                .andReturn();
        UUID relId = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString())
                .get("relationship").get("id").asText());
        UUID acceptKey = UUID.randomUUID();
        String acceptBody = "{\"expectedVersion\":0,\"commandKey\":\"%s\"}"
                .formatted(acceptKey);
        mvc.perform(post("/api/v1/coaching/relationships/{id}/accept", relId)
                        .with(jwt().jwt(j -> j.subject(trainer.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(acceptBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationship.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentPeriod.mode").value("HUMAN_COACH"));
        mvc.perform(post("/api/v1/coaching/relationships/{id}/accept", relId)
                        .with(jwt().jwt(j -> j.subject(trainer.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(acceptBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationship.version").value(1));
        mvc.perform(post("/api/v1/coaching/relationships/{id}/accept", relId)
                        .with(jwt().jwt(j -> j.subject(trainer.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"commandKey\":\"%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COACHING_VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_relationship_status_history WHERE relationship_id=?",
                Integer.class, relId)).isEqualTo(2);
    }

    @Test
    void everyPaginatedCoachingQueryUsesCommonValidationEnvelope() throws Exception {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        UUID relationshipId = accepted(student, trainer).relationship().id();
        String[] paths = {
                "/api/v1/coaching/relationships/me/pending",
                "/api/v1/coaching/relationships/" + relationshipId + "/history",
                "/api/v1/coaching/periods/me",
                "/api/v1/coaching/relationships/" + relationshipId + "/resume-requests"
        };
        String[] invalidQueries = { "page=-1", "size=0", "size=101", "page=1000001" };
        for (String path : paths) {
            for (String query : invalidQueries) {
                mvc.perform(get(path + "?" + query)
                                .with(jwt().jwt(j -> j.subject(student.toString()))))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.message").value("Request validation failed"))
                        .andExpect(jsonPath("$.timestamp").exists())
                        .andExpect(jsonPath("$.fieldErrors").isArray());
            }
            mvc.perform(get(path + "?page=0&size=1")
                            .with(jwt().jwt(j -> j.subject(student.toString()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(1));
        }
        mvc.perform(get("/api/v1/coaching/relationships/me/current")
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/coaching/relationships/{id}", relationshipId)
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isOk());
    }

    @Test
    void lostTrainerEligibilityDeniesActiveReadButDoesNotBlockRevocation() {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome active = accepted(student, trainer);
        jdbc.update("UPDATE fitness.trainer_profiles SET is_active=false WHERE user_id=?", trainer);
        assertThatThrownBy(() -> lifecycle.detail(trainer, active.relationship().id()))
                .isInstanceOf(CoachingFailure.class).extracting("code").isEqualTo("TRAINER_NOT_ELIGIBLE");
        Outcome paused = lifecycle.relationshipAction(student, active.relationship().id(),
                "PAUSE", 1, null, UUID.randomUUID());
        assertThat(paused.currentPeriod().mode()).isEqualTo("SELF_DIRECTED");
        Outcome ended = lifecycle.relationshipAction(student, active.relationship().id(),
                "END", 2, "eligibility lost", UUID.randomUUID());
        assertThat(ended.relationship().status()).isEqualTo("ENDED");
    }

    @Test
    void legacyActiveRelationshipWithoutPeriodCanBePausedWithoutInventingHistory() {
        UUID student = person("STUDENT"), trainer = person("TRAINER"), relId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                  (id,student_id,trainer_id,status,requested_by,accepted_at,started_at)
                VALUES (?,?,?,'ACTIVE',?,now(),now())
                """, relId, student, trainer, student);
        Outcome paused = lifecycle.relationshipAction(student, relId, "PAUSE", 0, null, UUID.randomUUID());
        assertThat(paused.currentPeriod().mode()).isEqualTo("SELF_DIRECTED");
        assertThat(lifecycle.periods(student, 0, 10)).hasSize(1);
        UUID anotherStudent = person("STUDENT"), pausedRelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                  (id,student_id,trainer_id,status,requested_by,accepted_at,started_at)
                VALUES (?,?,?,'PAUSED',?,now(),now())
                """, pausedRelId, anotherStudent, trainer, anotherStudent);
        Outcome ended = lifecycle.relationshipAction(anotherStudent, pausedRelId,
                "END", 0, "legacy close", UUID.randomUUID());
        assertThat(ended.currentPeriod().mode()).isEqualTo("SELF_DIRECTED");
        assertThat(lifecycle.periods(anotherStudent, 0, 10)).hasSize(1);
    }

    private String attemptAccept(UUID trainer, UUID relationship) {
        return attemptAccept(trainer, relationship, UUID.randomUUID());
    }

    private String attemptAccept(UUID trainer, UUID relationship, UUID commandKey) {
        try { return lifecycle.relationshipAction(trainer, relationship, "ACCEPT", 0, null, commandKey)
                .relationship().status(); }
        catch (CoachingFailure ex) { return ex.code(); }
    }

    private String attemptResume(UUID actor, UUID relationship, UUID resume, UUID commandKey) {
        try {
            return lifecycle.resumeAction(actor, relationship, resume, "ACCEPT", 2, 0, commandKey)
                    .relationship().status();
        } catch (CoachingFailure ex) {
            return ex.code();
        }
    }
}
