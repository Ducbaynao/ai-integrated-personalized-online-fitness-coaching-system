package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.notification.InAppNotificationService;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleChangeProposalUseCase;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleChangeProposalUseCase.*;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.*;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.Destination;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleChangeProposalIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("schedule_change_proposals_b06").withUsername("fitness_app")
            .withPassword("testpass123");
    static { postgres.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired ScheduleChangeProposalUseCase proposals;
    @Autowired WorkoutScheduleBatchUseCase batches;
    @Autowired WorkoutScheduleChangeUseCase changes;
    @Autowired WorkoutExecutionCommandUseCase execution;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired CoachingSharingUseCase sharing;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired InAppNotificationService notifications;

    @Test void createListDetailWithdrawAndReplayThroughHttp() throws Exception {
        Fixture f = fixture();
        UUID occurrence = occurrence(f);
        String body = """
                {"occurrenceId":"%s","expectedOccurrenceVersion":0,"changeType":"CANCEL",
                 "reason":"Đổi kế hoạch","commandKey":"create-http-%s"}
                """.formatted(occurrence, occurrence);
        String path = "/api/v1/students/{studentId}/planned-workout-change-proposals";
        String response = mvc.perform(post(path, f.student()).contentType("application/json").content(body)
                .with(jwt().jwt(t -> t.subject(f.trainer().toString()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.proposal.status").value("PENDING"))
                .andExpect(jsonPath("$.proposal.sourcePlanName").value("Trainer plan"))
                .andExpect(jsonPath("$.proposal.sourceSessionName").value("Session"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(response.split("\\\"proposalId\\\":\\\"")[1].split("\\\"")[0]);
        assertThat(notifications.list(f.student(), 0, 20).items())
                .anySatisfy(item -> assertThat(item.eventType()).isEqualTo("WORKOUT_CHANGE_PROPOSAL_PENDING"));
        mvc.perform(post(path, f.student()).contentType("application/json").content(body)
                .with(jwt().jwt(t -> t.subject(f.trainer().toString()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(get(path, f.student()).with(jwt().jwt(t -> t.subject(f.trainer().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/api/v1/planned-workout-change-proposals/me")
                .with(jwt().jwt(t -> t.subject(f.student().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].proposalId").value(id.toString()));
        mvc.perform(get("/api/v1/planned-workout-change-proposals/{id}", id)
                .with(jwt().jwt(t -> t.subject(f.trainer().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.proposalVersion").value(0));
        mvc.perform(post("/api/v1/planned-workout-change-proposals/{id}/withdraw", id)
                .contentType("application/json").content("{\"expectedProposalVersion\":0,\"commandKey\":\"withdraw-http\"}")
                .with(jwt().jwt(t -> t.subject(f.trainer().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.proposal.status").value("WITHDRAWN"));
        assertThat(notifications.list(f.student(), 0, 20).items())
                .anySatisfy(item -> assertThat(item.eventType()).isEqualTo("WORKOUT_CHANGE_PROPOSAL_WITHDRAWN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_change_proposal_history WHERE proposal_id=?",
                Integer.class, id)).isEqualTo(2);
    }

    @Test void studentAcceptRescheduleIsAtomicAndStaleProposalRemainsPending() {
        Fixture f = fixture();
        UUID occurrence = occurrence(f);
        Create command = new Create(f.trainer(), f.student(), occurrence, 0, "RESCHEDULE",
                destination(start().plusDays(1)), null, "move-propose-" + occurrence);
        Proposal proposal = proposals.create(command).proposal();
        var accepted = proposals.decide(new Decide(f.student(), proposal.proposalId(), 0, "ACCEPT", null,
                "move-accept-" + occurrence));
        assertThat(accepted.proposal().status()).isEqualTo("ACCEPTED");
        assertThat(accepted.appliedChange().replacementOccurrenceId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.planned_workouts WHERE id=?",
                String.class, occurrence)).isEqualTo("RESCHEDULED");
        assertThat(proposals.decide(new Decide(f.student(), proposal.proposalId(), 0, "ACCEPT", null,
                "move-accept-" + occurrence)).replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                Integer.class, occurrence)).isOne();

        Fixture stale = fixture();
        UUID old = occurrence(stale);
        Proposal pending = proposals.create(new Create(stale.trainer(), stale.student(), old, 0,
                "CANCEL", null, null, "stale-propose-" + old)).proposal();
        changes.cancel(new WorkoutScheduleChangeUseCase.Cancel(old, stale.student(), 0, null,
                "direct-cancel-" + old));
        assertCode("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_STALE", () -> proposals.decide(new Decide(
                stale.student(), pending.proposalId(), 0, "ACCEPT", null, "stale-accept-" + old)));
        assertThat(proposals.detail(stale.student(), pending.proposalId()).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_change_proposal_receipts WHERE actor_id=? AND command_key=?",
                Integer.class, stale.student(), "stale-accept-" + old)).isZero();
    }

    @Test void trainerLossConcealsReadAndAcceptButStudentCanReject() throws Exception {
        Fixture f = fixture();
        UUID occurrence = occurrence(f);
        Proposal pending = proposals.create(new Create(f.trainer(), f.student(), occurrence, 0,
                "CANCEL", null, null, "loss-propose-" + occurrence)).proposal();
        lifecycle.relationshipAction(f.student(), f.relationship(), "END", 1, "Coaching ended", UUID.randomUUID());
        mvc.perform(get("/api/v1/planned-workout-change-proposals/{id}", pending.proposalId())
                .with(jwt().jwt(t -> t.subject(f.trainer().toString())))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/students/{id}/planned-workout-change-proposals", f.student())
                .with(jwt().jwt(t -> t.subject(f.trainer().toString())))).andExpect(status().isNotFound());
        assertCode("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_STALE", () -> proposals.decide(new Decide(
                f.student(), pending.proposalId(), 0, "ACCEPT", null, "loss-accept-" + occurrence)));
        assertThat(proposals.decide(new Decide(f.student(), pending.proposalId(), 0, "REJECT", null,
                "loss-reject-" + occurrence)).proposal().status()).isEqualTo("REJECTED");
    }

    @Test void viewCanReadButCannotCreateOrWithdrawAndForeignStudentIsConcealed() throws Exception {
        Fixture f = fixture(), other = fixture();
        UUID occurrence = occurrence(f);
        Proposal p = proposals.create(new Create(f.trainer(), f.student(), occurrence, 0,
                "CANCEL", null, null, "view-propose-" + occurrence)).proposal();
        assertCode("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_NOT_FOUND", () -> proposals.detail(other.student(), p.proposalId()));
        sharing.grantOrReplace(f.student(), f.relationship(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, 0L, UUID.randomUUID());
        assertThat(proposals.listForTrainer(f.trainer(), f.student(), null, 0, 20).totalItems()).isOne();
        assertCode("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_NOT_FOUND", () -> proposals.withdraw(
                new Withdraw(f.trainer(), p.proposalId(), 0, "view-withdraw-" + occurrence)));
        assertCode("WORKOUT_SCHEDULE_CHANGE_ACCESS_DENIED", () -> proposals.create(new Create(
                f.trainer(), f.student(), occurrence, 0, "CANCEL", null, null, "view-create-" + occurrence)));
    }

    @Test void appointmentUnknownEndAndOverlapRollbackAcceptButRejectRemainsAvailable() {
        Fixture linked = fixture();
        UUID old = occurrence(linked);
        Proposal linkedProposal = proposals.create(new Create(linked.trainer(), linked.student(), old, 0,
                "CANCEL", null, null, "linked-propose-" + old)).proposal();
        UUID appointment = UUID.randomUUID();
        LegacyAppointmentFixture.insert(jdbc, """
                INSERT INTO fitness.appointments(id,student_id,planned_workout_id,start_at,end_at,
                    display_timezone,status,created_by)
                VALUES (?,?,?,?,?,'Etc/UTC','CANCELLED',?)
                """, appointment, linked.student(), old, Timestamp.from(start().toInstant(ZoneOffset.UTC)),
                Timestamp.from(start().plusHours(1).toInstant(ZoneOffset.UTC)), linked.student());
        assertCode("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", () -> proposals.decide(new Decide(
                linked.student(), linkedProposal.proposalId(), 0, "ACCEPT", null, "linked-accept-" + old)));
        assertThat(proposals.detail(linked.student(), linkedProposal.proposalId()).status()).isEqualTo("PENDING");
        assertThat(proposals.decide(new Decide(linked.student(), linkedProposal.proposalId(), 0,
                "REJECT", null, "linked-reject-" + old)).proposal().status()).isEqualTo("REJECTED");

        Fixture unknown = fixture();
        UUID original = occurrence(unknown);
        Proposal proposed = proposals.create(new Create(unknown.trainer(), unknown.student(), original, 0,
                "RESCHEDULE", destination(start().plusDays(2)), null, "unknown-propose-" + original)).proposal();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,planned_start_at,original_planned_start_at,created_by)
                VALUES (?,?,?,clock_timestamp()+interval '3 days',clock_timestamp()+interval '3 days',?)
                """, UUID.randomUUID(), unknown.student(), unknown.session(), unknown.student());
        assertCode("WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN", () -> proposals.decide(new Decide(
                unknown.student(), proposed.proposalId(), 0, "ACCEPT", null, "unknown-accept-" + original)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                Integer.class, original)).isZero();
        assertThat(proposals.detail(unknown.student(), proposed.proposalId()).status()).isEqualTo("PENDING");
    }

    @Test void staleVersionAndPayloadConflictDoNotCommitSecondEffect() {
        Fixture f = fixture();
        UUID old = occurrence(f);
        Create command = new Create(f.trainer(), f.student(), old, 0, "CANCEL", null,
                null, "collision-" + old);
        Proposal p = proposals.create(command).proposal();
        assertCode("WORKOUT_SCHEDULE_IDEMPOTENCY_CONFLICT", () -> proposals.create(new Create(
                f.trainer(), f.student(), old, 0, "CANCEL", null, "changed", command.commandKey())));
        assertCode("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_VERSION_CONFLICT", () -> proposals.decide(new Decide(
                f.student(), p.proposalId(), 1, "REJECT", null, "wrong-version-" + old)));
        assertThat(proposals.decide(new Decide(f.student(), p.proposalId(), 0, "REJECT", null,
                "reject-" + old)).proposal().status()).isEqualTo("REJECTED");
        assertCode("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_STATE_CONFLICT", () -> proposals.withdraw(new Withdraw(
                f.trainer(), p.proposalId(), 0, "late-withdraw-" + old)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_occurrence_changes WHERE previous_occurrence_id=?",
                Integer.class, old)).isZero();
    }

    @Test void competingAcceptAndDirectChangeHaveOnlyOneOccurrenceEffect() throws Exception {
        Fixture f = fixture();
        UUID old = occurrence(f);
        Proposal p = proposals.create(new Create(f.trainer(), f.student(), old, 0, "RESCHEDULE",
                destination(start().plusDays(2)), null, "race-propose-" + old)).proposal();
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch gate = new CountDownLatch(1);
            Future<String> accept = pool.submit(() -> {
                gate.await();
                try { proposals.decide(new Decide(f.student(), p.proposalId(), 0,
                        "ACCEPT", null, "race-accept-" + old)); return "ACCEPTED"; }
                catch (WorkoutScheduleFailure failure) { return failure.code(); }
            });
            Future<String> direct = pool.submit(() -> {
                gate.await();
                try { changes.reschedule(new WorkoutScheduleChangeUseCase.Reschedule(old, f.student(), 0,
                        destination(start().plusDays(3)), null, "race-direct-" + old)); return "MOVED"; }
                catch (WorkoutScheduleFailure failure) { return failure.code(); }
            });
            gate.countDown();
            String a = accept.get(20, TimeUnit.SECONDS), d = direct.get(20, TimeUnit.SECONDS);
            assertThat(List.of(a, d)).containsAnyOf("ACCEPTED", "MOVED");
            assertThat(List.of(a, d).stream().filter(s -> s.equals("ACCEPTED") || s.equals("MOVED")).count()).isOne();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                Integer.class, old)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_occurrence_changes WHERE previous_occurrence_id=?",
                Integer.class, old)).isOne();
    }

    @Test void competingAcceptCommandsApplyOneChangeAndOneReceipt() throws Exception {
        Fixture f = fixture();
        UUID old = occurrence(f);
        Proposal p = proposals.create(new Create(f.trainer(), f.student(), old, 0, "RESCHEDULE",
                destination(start().plusDays(2)), null, "double-propose-" + old)).proposal();
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch gate = new CountDownLatch(1);
            Future<String> first = pool.submit(() -> acceptAfterGate(f, p, old, "first", gate));
            Future<String> second = pool.submit(() -> acceptAfterGate(f, p, old, "second", gate));
            gate.countDown();
            var outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).contains("ACCEPTED");
            assertThat(outcomes.stream().filter("ACCEPTED"::equals).count()).isOne();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                Integer.class, old)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_change_proposal_receipts WHERE actor_id=? AND command_name='DECIDE'",
                Integer.class, f.student())).isOne();
    }

    @Test void competingAcceptAndStartCannotBothSucceed() throws Exception {
        Fixture f = fixture(true);
        UUID old = occurrence(f);
        Proposal p = proposals.create(new Create(f.trainer(), f.student(), old, 0, "RESCHEDULE",
                destination(start().plusDays(2)), null, "start-propose-" + old)).proposal();
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch gate = new CountDownLatch(1);
            Future<String> accept = pool.submit(() -> acceptAfterGate(f, p, old, "with-start", gate));
            Future<String> startCommand = pool.submit(() -> {
                gate.await();
                try { execution.start(new WorkoutExecutionCommandUseCase.StartCommand(old, f.student(), 0,
                        "start-race-" + old)); return "STARTED"; }
                catch (WorkoutExecutionFailure failure) { return failure.error().name(); }
            });
            gate.countDown();
            String a = accept.get(20, TimeUnit.SECONDS), s = startCommand.get(20, TimeUnit.SECONDS);
            assertThat(List.of(a, s).stream().filter(value -> value.equals("ACCEPTED") || value.equals("STARTED")).count())
                    .isOne();
        }
        int successors = jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                Integer.class, old);
        int logs = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs WHERE planned_workout_id=?",
                Integer.class, old);
        assertThat(successors + logs).isOne();
    }

    @Test void acceptWinningPreventsAppointmentLinkWithoutPartialWriterCommit() throws Exception {
        Fixture f = fixture();
        UUID old = occurrence(f);
        Proposal p = proposals.create(new Create(f.trainer(), f.student(), old, 0, "RESCHEDULE",
                destination(start().plusDays(2)), null, "appointment-accept-first-propose-" + old)).proposal();
        UUID appointment = UUID.randomUUID();
        String decisionKey = "appointment-accept-first-decision-" + old;
        CountDownLatch acceptedInsideTransaction = new CountDownLatch(1);
        CountDownLatch releaseAcceptCommit = new CountDownLatch(1);
        CountDownLatch writerInvoked = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpOutcome> accept = pool.submit(() -> inTransaction(() -> {
                HttpOutcome response = acceptHttp(f.student(), p.proposalId(), decisionKey);
                assertThat(response.status()).isEqualTo(200);
                acceptedInsideTransaction.countDown();
                await(releaseAcceptCommit);
                return response;
            }));
            assertThat(acceptedInsideTransaction.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> writer = pool.submit(() -> {
                writerInvoked.countDown();
                try { inTransaction(() -> { insertAppointment(f.student(), old, appointment); return null; });
                    return "LINKED"; }
                catch (DataAccessException rejected) { return sqlState(rejected); }
            });
            assertThat(writerInvoked.await(10, TimeUnit.SECONDS)).isTrue();
            releaseAcceptCommit.countDown();
            assertThat(accept.get(20, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(writer.get(20, TimeUnit.SECONDS)).isEqualTo("P0001");
        }
        assertThat(proposals.detail(f.student(), p.proposalId()).status()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.planned_workouts WHERE id=?",
                String.class, old)).isEqualTo("RESCHEDULED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                Integer.class, old)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_occurrence_changes WHERE previous_occurrence_id=?",
                Integer.class, old)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments WHERE id=? OR planned_workout_id=?",
                Integer.class, appointment, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_change_proposal_receipts WHERE actor_id=? AND command_key=?",
                Integer.class, f.student(), decisionKey)).isOne();
    }

    @Test void appointmentWinningReturnsHttp409AndKeepsProposalPendingForRejection() throws Exception {
        Fixture f = fixture();
        UUID old = occurrence(f);
        Proposal p = proposals.create(new Create(f.trainer(), f.student(), old, 0, "RESCHEDULE",
                destination(start().plusDays(2)), null, "appointment-writer-first-propose-" + old)).proposal();
        UUID appointment = UUID.randomUUID();
        String decisionKey = "appointment-writer-first-decision-" + old;
        CountDownLatch linkedInsideTransaction = new CountDownLatch(1);
        CountDownLatch releaseWriterCommit = new CountDownLatch(1);
        CountDownLatch acceptInvoked = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> writer = pool.submit(() -> inTransaction(() -> {
                insertAppointment(f.student(), old, appointment);
                linkedInsideTransaction.countDown();
                await(releaseWriterCommit);
                return null;
            }));
            assertThat(linkedInsideTransaction.await(10, TimeUnit.SECONDS)).isTrue();
            Future<HttpOutcome> accept = pool.submit(() -> {
                acceptInvoked.countDown();
                return acceptHttp(f.student(), p.proposalId(), decisionKey);
            });
            assertThat(acceptInvoked.await(10, TimeUnit.SECONDS)).isTrue();
            releaseWriterCommit.countDown();
            writer.get(20, TimeUnit.SECONDS);
            HttpOutcome response = accept.get(20, TimeUnit.SECONDS);
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.body()).contains("WORKOUT_SCHEDULE_APPOINTMENT_LINKED");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments WHERE id=? AND planned_workout_id=?",
                Integer.class, appointment, old)).isOne();
        assertThat(proposals.detail(f.student(), p.proposalId()).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.planned_workouts WHERE id=?",
                String.class, old)).isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                Integer.class, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_occurrence_changes WHERE previous_occurrence_id=?",
                Integer.class, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_change_receipts WHERE actor_id=? AND command_name='RESCHEDULE'",
                Integer.class, f.student())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_change_proposal_receipts WHERE actor_id=? AND command_key=?",
                Integer.class, f.student(), decisionKey)).isZero();
        assertThat(proposals.decide(new Decide(f.student(), p.proposalId(), 0, "REJECT", null,
                "appointment-reject-" + old)).proposal().status()).isEqualTo("REJECTED");
    }

    private HttpOutcome acceptHttp(UUID student, UUID proposal, String key) {
        try {
            var response = mvc.perform(post("/api/v1/planned-workout-change-proposals/{id}/decisions", proposal)
                    .contentType("application/json")
                    .content("{\"expectedProposalVersion\":0,\"decision\":\"ACCEPT\",\"commandKey\":\"" + key + "\"}")
                    .with(jwt().jwt(t -> t.subject(student.toString())))).andReturn().getResponse();
            return new HttpOutcome(response.getStatus(), response.getContentAsString());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private void insertAppointment(UUID student, UUID occurrence, UUID appointment) {
        LegacyAppointmentFixture.insert(jdbc, """
                INSERT INTO fitness.appointments(id,student_id,planned_workout_id,start_at,end_at,
                    display_timezone,status,created_by)
                VALUES (?,?,?,?,?,'Etc/UTC','CANCELLED',?)
                """, appointment, student, occurrence, Timestamp.from(start().toInstant(ZoneOffset.UTC)),
                Timestamp.from(start().plusHours(1).toInstant(ZoneOffset.UTC)), student);
    }
    private <T> T inTransaction(Callable<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
        return transaction.execute(status -> {
            try { return action.call(); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Commit release timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql) return sql.getSQLState();
        throw new AssertionError("Expected PostgreSQL SQLSTATE", failure);
    }
    private record HttpOutcome(int status, String body) {}

    private String acceptAfterGate(Fixture f, Proposal p, UUID old, String suffix, CountDownLatch gate)
            throws InterruptedException {
        gate.await();
        try { proposals.decide(new Decide(f.student(), p.proposalId(), 0, "ACCEPT", null,
                "accept-" + suffix + old)); return "ACCEPTED"; }
        catch (WorkoutScheduleFailure failure) { return failure.code(); }
    }

    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code").isEqualTo(code);
    }
    private UUID occurrence(Fixture f) {
        return batches.confirmDirect(new ConfirmBatch(f.student(), f.plan(), f.version(), 1, anchor(),
                "Etc/UTC", List.of(new Item(UUID.randomUUID(), f.session(), 1, 1, 1,
                start(), start().plusHours(1), "+00:00", "+00:00")), "batch-" + UUID.randomUUID()))
                .items().getFirst().occurrenceId();
    }
    private Fixture fixture() { return fixture(false); }
    private Fixture fixture(boolean startable) {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        var pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        var active = lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT", 0,
                null, UUID.randomUUID());
        sharing.grantOrReplace(student, active.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.CONTRIBUTE, null, null, null, null, UUID.randomUUID());
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID(), session = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,name,source,status,created_by,decision_owner_type,decision_owner_id,coaching_period_id)
                VALUES (?,?,'Trainer plan','TRAINER','DRAFT',?,'TRAINER',?,?)
                """, plan, student, trainer, trainer, active.currentPeriod().id());
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions(id,workout_plan_id,version_number,change_level,created_by)
                VALUES (?,?,1,'INITIAL',?)
                """, version, plan, trainer);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id,workout_plan_version_id,week_number,day_number,sequence_number,name,supervision_requirement)
                VALUES (?,?,1,1,1,'Session','SELF_ALLOWED')
                """, session, version);
        if (startable) {
            UUID exercise = UUID.randomUUID(), variation = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                    exercise, "proposal-race-exercise-" + exercise, "Race exercise", trainer);
            jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                    variation, exercise, "proposal-race-variation-" + variation, "Race variation");
            jdbc.update("""
                    INSERT INTO fitness.workout_plan_session_exercises
                    (id,workout_plan_session_id,exercise_variation_id,sequence_number,target_sets,target_reps_min,target_reps_max)
                    VALUES (?,?,?,1,1,8,12)
                    """, UUID.randomUUID(), session, variation);
        }
        jdbc.update("""
                UPDATE fitness.workout_plan_versions SET effective_from=clock_timestamp(),locked_at=clock_timestamp(),
                    locked_by=?,lock_reason='ACTIVATED' WHERE id=?
                """, trainer, version);
        jdbc.update("""
                UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1,
                    assigned_by=?,assigned_at=clock_timestamp() WHERE id=?
                """, trainer, plan);
        return new Fixture(student, trainer, active.relationship().id(), plan, version, session);
    }
    private UUID person(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'hash','Person','ACTIVE','vi-VN','Etc/UTC')
                """, id, id + "@example.com");
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?
                """, id, id, role);
        if ("STUDENT".equals(role)) jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        else jdbc.update("""
                INSERT INTO fitness.trainer_profiles
                (user_id,verification_status,verified_at,is_accepting_students,is_active)
                VALUES (?,'VERIFIED',clock_timestamp(),true,true)
                """, id);
        return id;
    }
    private static LocalDate anchor() {
        return LocalDate.now(ZoneOffset.UTC).plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
    private static LocalDateTime start() { return anchor().atTime(10, 0); }
    private static Destination destination(LocalDateTime at) {
        return new Destination(at, at.plusHours(1), "Etc/UTC", "+00:00", "+00:00");
    }
    private record Fixture(UUID student, UUID trainer, UUID relationship, UUID plan, UUID version, UUID session) {}
}
