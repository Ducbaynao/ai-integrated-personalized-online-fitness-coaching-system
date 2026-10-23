package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.notification.InAppNotificationService;
import com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentService;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.QualifyingWorkoutAppointmentQuery;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleChangeProposalUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Outcome;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase.*;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.Item;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleProposalIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("schedule_proposals_b06").withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired ScheduleProposalUseCase proposals;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired CoachingSharingUseCase sharing;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired InAppNotificationService notifications;
    @Autowired AppointmentService appointments;
    @Autowired WorkoutScheduleChangeUseCase changes;
    @Autowired WorkoutExecutionCommandUseCase execution;
    @Autowired WorkoutScheduleCalendarQuery calendar;
    @Autowired ScheduleChangeProposalUseCase changeProposals;

    @Test void linkedTrainerChangeProposalsPinAppointmentAndStudentDecidesBothTransitions() {
        LinkedFixture linked = linkedPendingProposal("linked-change-proposal");
        UUID old = proposals.decide(new Decide(linked.fixture.student(), linked.proposalId, 0,
                "ACCEPT", null, "linked-change-proposal-batch-accept"))
                .confirmedBatch().items().getFirst().occurrenceId();
        var movedProposal = changeProposals.create(new ScheduleChangeProposalUseCase.Create(
                linked.fixture.trainer(), linked.fixture.student(), old, 0, "RESCHEDULE",
                new WorkoutScheduleChangeUseCase.Destination(at(11), at(12), "Etc/UTC", "+00:00", "+00:00"),
                "Different time", "linked-change-proposal-move")).proposal();
        assertThat(movedProposal.appointmentId()).isEqualTo(linked.appointmentId);
        assertThat(movedProposal.observedAppointmentVersion()).isEqualTo(linked.appointmentVersion + 1);
        var moved = changeProposals.decide(new ScheduleChangeProposalUseCase.Decide(linked.fixture.student(),
                movedProposal.proposalId(), 0, "ACCEPT", null, "linked-change-proposal-move-accept"));
        UUID successor = moved.appliedChange().replacementOccurrenceId();
        assertThat(jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?",
                UUID.class, linked.appointmentId)).isEqualTo(successor);
        var cancelProposal = changeProposals.create(new ScheduleChangeProposalUseCase.Create(
                linked.fixture.trainer(), linked.fixture.student(), successor, 0, "CANCEL", null,
                "No workout", "linked-change-proposal-cancel")).proposal();
        assertThat(cancelProposal.appointmentId()).isEqualTo(linked.appointmentId);
        var cancelled = changeProposals.decide(new ScheduleChangeProposalUseCase.Decide(linked.fixture.student(),
                cancelProposal.proposalId(), 0, "ACCEPT", null, "linked-change-proposal-cancel-accept"));
        assertThat(cancelled.appliedChange().previousStatus()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?",
                UUID.class, linked.appointmentId)).isNull();
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.appointments WHERE id=?",
                String.class, linked.appointmentId)).isEqualTo("CONFIRMED");
        assertThat(changeProposals.decide(new ScheduleChangeProposalUseCase.Decide(linked.fixture.student(),
                cancelProposal.proposalId(), 0, "ACCEPT", null, "linked-change-proposal-cancel-accept"))
                .replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE related_entity_id=? "
                + "AND event_type='LINKED_WORKOUT_CANCELLED' AND recipient_user_id=?", Integer.class,
                successor, linked.fixture.trainer())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointment_events_v2 "
                + "WHERE appointment_id=? AND event_type='WORKOUT_LINK_RELEASED'", Integer.class,
                linked.appointmentId)).isOne();
        sharing.revoke(linked.fixture.student(), linked.fixture.relationship(), linked.fixture.permission(), 0,
                "Revoke schedule access", UUID.randomUUID());
        assertThat(notifications.list(linked.fixture.trainer(), 0, 20).items())
                .noneMatch(item -> item.eventType().equals("LINKED_WORKOUT_CANCELLED"));
    }
    @Autowired QualifyingWorkoutAppointmentQuery qualifying;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired tools.jackson.databind.ObjectMapper json;

    @Test void linkedStartWinsAgainstReschedule() throws Exception { linkedStartRace(true, false); }
    @Test void linkedRescheduleWinsAgainstStart() throws Exception { linkedStartRace(false, false); }
    @Test void linkedStartWinsAgainstCancel() throws Exception { linkedStartRace(true, true); }
    @Test void linkedCancelWinsAgainstStart() throws Exception { linkedStartRace(false, true); }

    private void linkedStartRace(boolean startWins, boolean cancel) throws Exception {
        String name = "linked-start-race-" + startWins + "-" + cancel;
        LinkedFixture linked = linkedPendingProposal(name, true);
        UUID old = proposals.decide(new Decide(linked.fixture.student(), linked.proposalId, 0,
                "ACCEPT", null, name + "-accept")).confirmedBatch().items().getFirst().occurrenceId();
        // Model a confirmed Appointment whose start has passed while the workout
        // remains well in the future. Both commands are then genuinely eligible;
        // no fixture is placed close to a clock boundary.
        jdbc.update("""
                UPDATE fitness.appointments
                SET start_at=clock_timestamp()-interval '2 hours',
                    local_start=(clock_timestamp()-interval '2 hours') AT TIME ZONE 'UTC',
                    version=version+1 WHERE id=?
                """, linked.appointmentId);
        assertThat(qualifying.existsForOccurrence(old)).isTrue();
        String startKey = name + "-start", changeKey = name + "-change";
        CountDownLatch firstFinished = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger(), secondPid = new AtomicInteger();
        HttpOutcome first, second;
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpOutcome> leading = pool.submit(() -> inHttpTransaction(startWins, () -> {
                firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                HttpOutcome outcome;
                try {
                    outcome = workoutDirect(linked.fixture.student(), old, startWins ? "START" :
                            cancel ? "CANCEL" : "RESCHEDULE", startWins ? startKey : changeKey);
                    assertThat(outcome.status()).isEqualTo(startWins ? 201 : cancel ? 200 : 201);
                } finally { firstFinished.countDown(); }
                await(releaseFirst);
                return outcome;
            }));
            assertThat(firstFinished.await(15, TimeUnit.SECONDS)).isTrue();
            if (leading.isDone()) leading.get(1, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT state FROM pg_stat_activity WHERE pid=?",
                    String.class, firstPid.get())).isEqualTo("idle in transaction");
            Future<HttpOutcome> waiting = pool.submit(() -> inHttpTransaction(!startWins, () -> {
                secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                secondEntered.countDown();
                return workoutHttp(linked.fixture.student(), old, startWins ?
                        cancel ? "CANCEL" : "RESCHEDULE" : "START", startWins ? changeKey : startKey);
            }));
            try {
                assertThat(secondEntered.await(15, TimeUnit.SECONDS)).isTrue();
                awaitBlockedBy(secondPid.get(), firstPid.get(), waiting);
            } finally { releaseFirst.countDown(); }
            first = leading.get(20, TimeUnit.SECONDS);
            second = waiting.get(20, TimeUnit.SECONDS);
        }
        assertThat(first.status()).isEqualTo(startWins ? 201 : cancel ? 200 : 201);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo(startWins
                ? "WORKOUT_SCHEDULE_OCCURRENCE_VERSION_CONFLICT"
                : "WORKOUT_EXECUTION_VERSION_CONFLICT");
        String finalStatus = jdbc.queryForObject("SELECT status::text FROM fitness.planned_workouts WHERE id=?",
                String.class, old);
        assertThat(finalStatus).isEqualTo(startWins ? "SCHEDULED" : cancel ? "CANCELLED" : "RESCHEDULED");
        UUID successor = jdbc.query("SELECT id FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                (rs, row) -> rs.getObject(1, UUID.class), old).stream().findFirst().orElse(null);
        if (startWins || cancel) assertThat(successor).isNull();
        else assertThat(successor).isEqualTo(first.replacement());
        assertThat(jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?",
                UUID.class, linked.appointmentId)).isEqualTo(startWins ? old : cancel ? null : successor);
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.appointments WHERE id=?",
                String.class, linked.appointmentId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs WHERE planned_workout_id=?",
                Integer.class, old)).isEqualTo(startWins ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_execution_command_receipts "
                + "WHERE actor_id=? AND command_key=?", Integer.class, linked.fixture.student(), startKey))
                .isEqualTo(startWins ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_change_receipts "
                + "WHERE actor_id=? AND command_key=?", Integer.class, linked.fixture.student(), changeKey))
                .isEqualTo(startWins ? 0 : 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointment_events_v2 WHERE appointment_id=? "
                + "AND event_type=?", Integer.class, linked.appointmentId,
                cancel ? "WORKOUT_LINK_RELEASED" : "WORKOUT_LINK_TRANSFERRED"))
                .isEqualTo(startWins ? 0 : 1);
        UUID auditTarget = startWins ? jdbc.queryForObject("SELECT id FROM fitness.workout_session_logs "
                + "WHERE planned_workout_id=?", UUID.class, old) : old;
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id=? "
                + "AND target_id=? AND action=?", Integer.class, linked.fixture.student(), auditTarget,
                startWins ? "WORKOUT_EXECUTION_STARTED" : cancel ? "PLANNED_WORKOUT_CANCELLED"
                        : "PLANNED_WORKOUT_RESCHEDULED")).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE related_entity_id=? "
                + "AND event_type='LINKED_WORKOUT_CANCELLED'", Integer.class, old))
                .isEqualTo(!startWins && cancel ? 1 : 0);
    }

    private HttpOutcome inHttpTransaction(boolean start, java.util.function.Supplier<HttpOutcome> command) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(start
                ? org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ
                : org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
        return transaction.execute(status -> {
            HttpOutcome outcome = command.get();
            // MockMvc catches the domain exception to serialize its HTTP 409,
            // while the joined command transaction is rollback-only. Complete
            // the losing outer transaction as an explicit rollback.
            if (outcome.status() >= 400) status.setRollbackOnly();
            return outcome;
        });
    }

    private HttpOutcome workoutDirect(UUID student, UUID occurrence, String action, String key) {
        return switch (action) {
            case "START" -> {
                execution.start(new WorkoutExecutionCommandUseCase.StartCommand(occurrence, student, 0, key));
                yield new HttpOutcome(201, null, null);
            }
            case "CANCEL" -> {
                changes.cancel(new WorkoutScheduleChangeUseCase.Cancel(occurrence, student, 0,
                        "Cancel linked workout", key));
                yield new HttpOutcome(200, null, null);
            }
            case "RESCHEDULE" -> {
                var result = changes.reschedule(new WorkoutScheduleChangeUseCase.Reschedule(occurrence,
                        student, 0, new WorkoutScheduleChangeUseCase.Destination(at(11), at(12),
                        "Etc/UTC", "+00:00", "+00:00"), null, key));
                yield new HttpOutcome(201, null, result.replacementOccurrenceId());
            }
            default -> throw new IllegalArgumentException(action);
        };
    }

    private HttpOutcome workoutHttp(UUID student, UUID occurrence, String action, String key) {
        String url, body;
        switch (action) {
            case "START" -> {
                url = "/api/v1/planned-workouts/{id}/executions";
                body = "{\"expectedOccurrenceVersion\":0,\"commandKey\":\"" + key + "\"}";
            }
            case "CANCEL" -> {
                url = "/api/v1/planned-workouts/{id}/cancel";
                body = "{\"expectedOccurrenceVersion\":0,\"reason\":\"Cancel linked workout\","
                        + "\"commandKey\":\"" + key + "\"}";
            }
            case "RESCHEDULE" -> {
                url = "/api/v1/planned-workouts/{id}/reschedule";
                body = """
                        {"expectedOccurrenceVersion":0,"commandKey":"%s",
                         "destination":{"localStart":"%s","localEnd":"%s","timezone":"Etc/UTC",
                         "startUtcOffset":"+00:00","endUtcOffset":"+00:00"}}
                        """.formatted(key, at(11), at(12));
            }
            default -> throw new IllegalArgumentException(action);
        }
        try {
            var response = mvc.perform(post(url, occurrence).contentType(MediaType.APPLICATION_JSON)
                    .content(body).with(jwt().jwt(token -> token.subject(student.toString()))))
                    .andReturn().getResponse();
            var tree = json.readTree(response.getContentAsString());
            return new HttpOutcome(response.getStatus(), tree.has("errorCode")
                    ? tree.get("errorCode").asText() : null,
                    tree.has("replacementOccurrenceId")
                            ? UUID.fromString(tree.get("replacementOccurrenceId").asText()) : null);
        } catch (Exception failure) { throw new IllegalStateException(action + " HTTP command failed", failure); }
    }

    private void addStartablePrescription(UUID session) {
        UUID exercise = UUID.randomUUID(), variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) "
                + "SELECT ?,?,'Race exercise','ACTIVE',student_id FROM fitness.workout_plans WHERE id="
                + "(SELECT workout_plan_id FROM fitness.workout_plan_versions WHERE id="
                + "(SELECT workout_plan_version_id FROM fitness.workout_plan_sessions WHERE id=?))",
                exercise, "linked-race-exercise-" + exercise, session);
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) "
                + "VALUES (?,?,?,'Race variation',true)", variation, exercise, "linked-race-variation-" + variation);
        jdbc.update("INSERT INTO fitness.workout_plan_session_exercises "
                + "(id,workout_plan_session_id,exercise_variation_id,sequence_number,target_sets,target_reps_min,target_reps_max) "
                + "VALUES (?,?,?,1,1,8,12)", UUID.randomUUID(), session, variation);
    }

    private void awaitBlockedBy(int waiter, int blocker, Future<HttpOutcome> waiting) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (waiting.isDone()) throw new AssertionError("Command finished before lock wait: "
                    + waiting.get(1, TimeUnit.SECONDS));
            Boolean blocked = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity a WHERE a.pid=?
                    AND a.wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(a.pid)))
                    """, Boolean.class, waiter, blocker);
            if (Boolean.TRUE.equals(blocked)) return;
            Thread.onSpinWait();
        }
        String observed = jdbc.queryForObject("SELECT state || '/' || coalesce(wait_event_type,'none') "
                + "|| '/' || coalesce(wait_event,'none') FROM pg_stat_activity WHERE pid=?", String.class, waiter);
        throw new AssertionError("Expected PostgreSQL waiter " + waiter + " blocked by " + blocker
                + "; observed " + observed);
    }

    private record HttpOutcome(int status, String code, UUID replacement) {}

    @Test void guardedConfirmedAppointmentCannotDoubleLinkSameOccurrenceEvenWithoutTimeOverlap() {
        LinkedFixture linked = linkedPendingProposal("unique-guarded-link");
        UUID occurrence = proposals.decide(new Decide(linked.fixture.student(), linked.proposalId, 0,
                "ACCEPT", null, "unique-guarded-link-accept"))
                .confirmedBatch().items().getFirst().occurrenceId();
        // A second interval does not overlap the first one, so V45's time exclusion
        // alone cannot protect the one-active-link identity.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO fitness.appointments
                (id,student_id,trainer_id,coaching_period_id,planned_workout_id,start_at,end_at,
                 display_timezone,local_start,local_end,start_utc_offset,end_utc_offset,
                 guarded_v2,status,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,true,'CONFIRMED',?)
                """, UUID.randomUUID(), linked.fixture.student(), linked.fixture.trainer(),
                linked.fixture.period(), occurrence,
                Timestamp.from(at(13).toInstant(ZoneOffset.UTC)),
                Timestamp.from(at(14).toInstant(ZoneOffset.UTC)),
                "Etc/UTC", at(13), at(14), "+00:00", "+00:00", linked.fixture.student()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments WHERE planned_workout_id=? "
                + "AND status='CONFIRMED'", Integer.class, occurrence)).isOne();
    }

    @Test void acceptedProposalCommitsBeforeAppointmentCancelAndPreventsDoubleSuccess() throws Exception {
        LinkedFixture linked = linkedPendingProposal("accept-first");
        CountDownLatch acceptedInsideTransaction = new CountDownLatch(1), releaseCommit = new CountDownLatch(1);
        CountDownLatch writerInvoked = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> accept = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                proposals.decide(new Decide(linked.fixture.student(), linked.proposalId, 0, "ACCEPT", null,
                        "accept-first-decision"));
                acceptedInsideTransaction.countDown();
                await(releaseCommit);
                return null;
            }));
            assertThat(acceptedInsideTransaction.await(15, TimeUnit.SECONDS)).isTrue();
            Future<String> writer = pool.submit(() -> {
                writerInvoked.countDown();
                try {
                    appointments.cancel(new AppointmentService.CancelCommand(linked.fixture.student(),
                            linked.appointmentId, linked.appointmentVersion, "Cancel", UUID.randomUUID()));
                    return "COMMITTED";
                } catch (com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentFailure failure) {
                    return failure.code();
                }
            });
            assertThat(writerInvoked.await(15, TimeUnit.SECONDS)).isTrue();
            releaseCommit.countDown();
            accept.get(20, TimeUnit.SECONDS);
            assertThat(writer.get(20, TimeUnit.SECONDS)).isIn("APPOINTMENT_VERSION_STALE", "APPOINTMENT_COACH_REQUIRED_DEPENDENCY");
        }
        assertThat(proposals.detail(linked.fixture.student(), linked.proposalId).status()).isEqualTo("ACCEPTED");
        assertThat(countOccurrences(linked.fixture)).isOne();
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.appointments WHERE id=?", String.class,
                linked.appointmentId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments a JOIN fitness.planned_workouts p "
                + "ON p.id=a.planned_workout_id WHERE a.id=? AND p.status='SCHEDULED'", Integer.class,
                linked.appointmentId)).isOne();
    }

    @Test void cancelledAppointmentCommitsBeforeProposalAcceptAndLeavesNoPartialMaterialization() throws Exception {
        LinkedFixture linked = linkedPendingProposal("writer-first");
        CountDownLatch cancelledInsideTransaction = new CountDownLatch(1), releaseCommit = new CountDownLatch(1);
        CountDownLatch acceptInvoked = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> writer = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                appointments.cancel(new AppointmentService.CancelCommand(linked.fixture.student(),
                        linked.appointmentId, linked.appointmentVersion, "Cancel", UUID.randomUUID()));
                cancelledInsideTransaction.countDown();
                await(releaseCommit);
                return null;
            }));
            assertThat(cancelledInsideTransaction.await(15, TimeUnit.SECONDS)).isTrue();
            Future<String> accept = pool.submit(() -> {
                acceptInvoked.countDown();
                try {
                    proposals.decide(new Decide(linked.fixture.student(), linked.proposalId, 0,
                            "ACCEPT", null, "writer-first-decision"));
                    return "COMMITTED";
                } catch (WorkoutScheduleFailure failure) {
                    return failure.code();
                }
            });
            assertThat(acceptInvoked.await(15, TimeUnit.SECONDS)).isTrue();
            releaseCommit.countDown();
            writer.get(20, TimeUnit.SECONDS);
            assertThat(accept.get(20, TimeUnit.SECONDS)).isEqualTo("WORKOUT_SCHEDULE_APPOINTMENT_NOT_QUALIFYING");
        }
        assertThat(proposals.detail(linked.fixture.student(), linked.proposalId).status()).isEqualTo("PENDING");
        assertThat(countOccurrences(linked.fixture)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_command_receipts "
                + "WHERE actor_id=? AND command_key='writer-first-decision'", Integer.class,
                linked.fixture.student())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_batches "
                + "WHERE source_proposal_id=?", Integer.class, linked.proposalId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE related_entity_id=? "
                + "AND event_type='WORKOUT_SCHEDULE_PROPOSAL_ACCEPTED'", Integer.class,
                linked.proposalId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.appointments WHERE id=?", String.class,
                linked.appointmentId)).isEqualTo("CANCELLED");
    }

    @Test void appointmentRescheduleAndLinkedWorkoutRescheduleSerializeInBothCommitOrders() throws Exception {
        for (boolean workoutWins : List.of(true, false)) {
            LinkedFixture linked = linkedPendingProposal("linked-reschedule-race-" + workoutWins);
            UUID old = proposals.decide(new Decide(linked.fixture.student(), linked.proposalId, 0,
                    "ACCEPT", null, "linked-reschedule-race-batch-" + workoutWins))
                    .confirmedBatch().items().getFirst().occurrenceId();
            long appointmentVersion = linked.appointmentVersion + 1;
            var request = appointments.requestReschedule(new AppointmentService.RescheduleCommand(
                    linked.fixture.student(), linked.appointmentId, appointmentVersion,
                    new AppointmentService.LocalInterval(at(9), at(11), "Etc/UTC", "+00:00", "+00:00"),
                    "Shorter appointment", UUID.randomUUID()));
            var destination = new WorkoutScheduleChangeUseCase.Destination(at(11), at(12),
                    "Etc/UTC", "+00:00", "+00:00");
            CountDownLatch firstChanged = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
            CountDownLatch secondInvoked = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Future<?> first = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    if (workoutWins) changes.reschedule(new WorkoutScheduleChangeUseCase.Reschedule(old,
                            linked.fixture.student(), 0, destination, null,
                            "linked-reschedule-race-workout-" + workoutWins));
                    else appointments.decideReschedule(new AppointmentService.RescheduleDecisionCommand(
                            linked.fixture.trainer(), request.request().requestId(), "ACCEPT", 0,
                            appointmentVersion, UUID.randomUUID()));
                    firstChanged.countDown();
                    await(releaseFirst);
                    return null;
                }));
                assertThat(firstChanged.await(15, TimeUnit.SECONDS)).isTrue();
                Future<String> second = pool.submit(() -> {
                    secondInvoked.countDown();
                    try {
                        if (workoutWins) appointments.decideReschedule(
                                new AppointmentService.RescheduleDecisionCommand(linked.fixture.trainer(),
                                        request.request().requestId(), "ACCEPT", 0, appointmentVersion,
                                        UUID.randomUUID()));
                        else changes.reschedule(new WorkoutScheduleChangeUseCase.Reschedule(old,
                                linked.fixture.student(), 0, destination, null,
                                "linked-reschedule-race-workout-" + workoutWins));
                        return "COMMITTED";
                    } catch (com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentFailure failure) {
                        return failure.code();
                    } catch (WorkoutScheduleFailure failure) {
                        return failure.code();
                    }
                });
                assertThat(secondInvoked.await(15, TimeUnit.SECONDS)).isTrue();
                releaseFirst.countDown();
                first.get(20, TimeUnit.SECONDS);
                String loser = second.get(20, TimeUnit.SECONDS);
                assertThat(loser).isNotEqualTo("COMMITTED");
                if (workoutWins) assertThat(loser).isIn("APPOINTMENT_VERSION_STALE",
                        "APPOINTMENT_COACH_REQUIRED_DEPENDENCY");
                else assertThat(loser).isEqualTo("WORKOUT_SCHEDULE_APPOINTMENT_LINKED");
            }
            UUID active = jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?",
                    UUID.class, linked.appointmentId);
            assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.appointments WHERE id=?",
                    String.class, linked.appointmentId)).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                    Integer.class, old)).isEqualTo(workoutWins ? 1 : 0);
            assertThat(active).isEqualTo(workoutWins ? jdbc.queryForObject(
                    "SELECT id FROM fitness.planned_workouts WHERE rescheduled_from_id=?", UUID.class, old) : old);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_change_receipts "
                    + "WHERE actor_id=? AND command_key=?", Integer.class, linked.fixture.student(),
                    "linked-reschedule-race-workout-" + workoutWins)).isEqualTo(workoutWins ? 1 : 0);
        }
    }

    private LinkedFixture linkedPendingProposal(String key) {
        return linkedPendingProposal(key, false);
    }

    private LinkedFixture linkedPendingProposal(String key, boolean startable) {
        Fixture f = fixture("COACH_REQUIRED", startable);
        var request = appointments.request(new AppointmentService.RequestCommand(f.student(), f.student(), null,
                new AppointmentService.LocalInterval(at(9), at(12), "Etc/UTC", "+00:00", "+00:00"),
                UUID.randomUUID()));
        var app = appointments.decide(new AppointmentService.DecisionCommand(f.trainer(),
                request.request().requestId(), "ACCEPT", 0, UUID.randomUUID())).appointment();
        Item item = new Item(UUID.randomUUID(), f.session(), 1, 1, 1, at(10), at(11),
                "+00:00", "+00:00", app.appointmentId());
        UUID proposal = proposals.create(new Create(f.trainer(), f.student(), f.plan(), f.version(), 1,
                anchor(), "Etc/UTC", List.of(item), key + "-proposal")).proposal().proposalId();
        return new LinkedFixture(f, app.appointmentId(), app.version(), proposal);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("transaction gate timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private record LinkedFixture(Fixture fixture, UUID appointmentId, long appointmentVersion, UUID proposalId) {}

    @Test void coachRequiredProposalPinsConfirmedAppointmentAndAcceptsAtomically() throws Exception {
        Fixture f = fixture("COACH_REQUIRED");
        var requested = appointments.request(new AppointmentService.RequestCommand(f.student(), f.student(), null,
                new AppointmentService.LocalInterval(at(9), at(12), "Etc/UTC", "+00:00", "+00:00"),
                UUID.randomUUID()));
        var appointment = appointments.decide(new AppointmentService.DecisionCommand(f.trainer(),
                requested.request().requestId(), "ACCEPT", 0, UUID.randomUUID())).appointment();
        Item item = new Item(UUID.randomUUID(), f.session(), 1, 1, 1, at(10), at(11),
                "+00:00", "+00:00", appointment.appointmentId());
        Create command = new Create(f.trainer(), f.student(), f.plan(), f.version(), 1, anchor(),
                "Etc/UTC", List.of(item), "coached-propose");
        var proposal = proposals.create(command).proposal();
        assertThat(proposal.items().getFirst().appointmentId()).isEqualTo(appointment.appointmentId());
        assertThat(proposal.items().getFirst().observedAppointmentVersion()).isEqualTo(appointment.version());
        assertThat(countOccurrences(f)).isZero();
        mvc.perform(get("/api/v1/planned-workout-schedule-proposals/{id}", proposal.proposalId())
                .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].appointmentId").value(appointment.appointmentId().toString()))
                .andExpect(jsonPath("$.items[0].observedAppointmentVersion").value((int) appointment.version()));
        var accepted = proposals.decide(new Decide(f.student(), proposal.proposalId(), 0,
                "ACCEPT", null, "coached-accept"));
        UUID occurrence = accepted.confirmedBatch().items().getFirst().occurrenceId();
        assertThat(jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?",
                UUID.class, appointment.appointmentId())).isEqualTo(occurrence);
        var scheduled = calendar.detail(f.student(), occurrence);
        assertThat(scheduled.linkedAppointmentId()).isEqualTo(appointment.appointmentId());
        assertThat(scheduled.linkedAppointmentVersion()).isEqualTo(appointment.version() + 1);
        assertThat(scheduled.availableActions()).contains("RESCHEDULE", "CANCEL");
        assertThat(proposals.decide(new Decide(f.student(), proposal.proposalId(), 0,
                "ACCEPT", null, "coached-accept")).replayed()).isTrue();
        assertThat(countOccurrences(f)).isOne();
    }

    @Test void changedAppointmentKeepsCoachRequiredProposalPending() {
        Fixture f = fixture("COACH_REQUIRED");
        var request = appointments.request(new AppointmentService.RequestCommand(f.student(), f.student(), null,
                new AppointmentService.LocalInterval(at(9), at(12), "Etc/UTC", "+00:00", "+00:00"),
                UUID.randomUUID()));
        var appointment = appointments.decide(new AppointmentService.DecisionCommand(f.trainer(),
                request.request().requestId(), "ACCEPT", 0, UUID.randomUUID())).appointment();
        Item item = new Item(UUID.randomUUID(), f.session(), 1, 1, 1, at(10), at(11),
                "+00:00", "+00:00", appointment.appointmentId());
        UUID proposalId = proposals.create(new Create(f.trainer(), f.student(), f.plan(), f.version(), 1,
                anchor(), "Etc/UTC", List.of(item), "appointment-stale-propose")).proposal().proposalId();
        var reschedule = appointments.requestReschedule(new AppointmentService.RescheduleCommand(f.student(),
                appointment.appointmentId(), appointment.version(),
                new AppointmentService.LocalInterval(at(8), at(12), "Etc/UTC", "+00:00", "+00:00"),
                "Move earlier", UUID.randomUUID()));
        appointments.decideReschedule(new AppointmentService.RescheduleDecisionCommand(f.trainer(),
                reschedule.request().requestId(), "ACCEPT", 0, appointment.version(), UUID.randomUUID()));
        assertFailure("WORKOUT_SCHEDULE_APPOINTMENT_NOT_QUALIFYING", () ->
                proposals.decide(new Decide(f.student(), proposalId, 0, "ACCEPT", null, "stale-app-accept")));
        assertThat(proposals.detail(f.student(), proposalId).status()).isEqualTo("PENDING");
        assertThat(countOccurrences(f)).isZero();
        assertThat(proposals.decide(new Decide(f.student(), proposalId, 0,
                "REJECT", null, "stale-app-reject")).proposal().status()).isEqualTo("REJECTED");
    }

    @Test void linkedRescheduleTransfersActiveLinkAndCancelKeepsAppointment() {
        Fixture f = fixture("COACH_REQUIRED");
        var request = appointments.request(new AppointmentService.RequestCommand(f.student(), f.student(), null,
                new AppointmentService.LocalInterval(at(9), at(13), "Etc/UTC", "+00:00", "+00:00"),
                UUID.randomUUID()));
        var appointment = appointments.decide(new AppointmentService.DecisionCommand(f.trainer(),
                request.request().requestId(), "ACCEPT", 0, UUID.randomUUID())).appointment();
        Item item = new Item(UUID.randomUUID(), f.session(), 1, 1, 1, at(10), at(11),
                "+00:00", "+00:00", appointment.appointmentId());
        UUID proposal = proposals.create(new Create(f.trainer(), f.student(), f.plan(), f.version(), 1,
                anchor(), "Etc/UTC", List.of(item), "linked-change-source")).proposal().proposalId();
        UUID old = proposals.decide(new Decide(f.student(), proposal, 0, "ACCEPT", null,
                "linked-change-accept")).confirmedBatch().items().getFirst().occurrenceId();
        assertFailure("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", () -> changes.reschedule(
                new WorkoutScheduleChangeUseCase.Reschedule(old, f.student(), 0,
                        new WorkoutScheduleChangeUseCase.Destination(at(12), at(14), "Etc/UTC", "+00:00", "+00:00"),
                        null, "outside-linked-appointment")));
        var moved = changes.reschedule(new WorkoutScheduleChangeUseCase.Reschedule(old, f.student(), 0,
                new WorkoutScheduleChangeUseCase.Destination(at(11), at(12), "Etc/UTC", "+00:00", "+00:00"),
                null, "inside-linked-appointment"));
        UUID successor = moved.replacementOccurrenceId();
        assertThat(jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?",
                UUID.class, appointment.appointmentId())).isEqualTo(successor);
        assertThat(calendar.detail(f.student(), old).linkedAppointmentId()).isNull();
        assertThat(calendar.detail(f.student(), successor).linkedAppointmentId()).isEqualTo(appointment.appointmentId());
        assertThat(qualifying.existsForOccurrence(old)).isFalse();
        // Execution qualification additionally requires DB time inside the future appointment.
        assertThat(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.appointments a
                  JOIN fitness.planned_workouts p ON p.id=a.planned_workout_id
                  WHERE a.id=? AND p.id=? AND a.status='CONFIRMED'
                    AND a.start_at<=p.planned_start_at AND a.end_at>=p.planned_end_at)
                """, Boolean.class, appointment.appointmentId(), successor)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointment_events_v2 WHERE appointment_id=? AND event_type='WORKOUT_LINK_TRANSFERRED'",
                Integer.class, appointment.appointmentId())).isOne();
        var cancelled = changes.cancel(new WorkoutScheduleChangeUseCase.Cancel(successor, f.student(), 0,
                "Không tập", "linked-successor-cancel"));
        assertThat(cancelled.previousStatus()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?",
                UUID.class, appointment.appointmentId())).isNull();
        assertThat(calendar.detail(f.student(), successor).linkedAppointmentId()).isNull();
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.appointments WHERE id=?",
                String.class, appointment.appointmentId())).isEqualTo("CONFIRMED");
        assertThat(notifications.list(f.trainer(), 0, 20).items())
                .anySatisfy(notice -> assertThat(notice.eventType()).isEqualTo("LINKED_WORKOUT_CANCELLED"));
        long version = jdbc.queryForObject("SELECT version FROM fitness.appointments WHERE id=?",
                Long.class, appointment.appointmentId());
        assertThat(appointments.cancel(new AppointmentService.CancelCommand(f.student(),
                appointment.appointmentId(), version, "Không còn buổi tập", UUID.randomUUID()))
                .appointment().status()).isEqualTo("CANCELLED");
    }

    @Test void trainerProposalListRequiresCurrentViewAndScopesOwnStudentAndStatus() throws Exception {
        Fixture f = fixture(), other = fixture();
        UUID first = proposals.create(command(f, "read-own-first", at(10))).proposal().proposalId();
        UUID second = proposals.create(command(f, "read-own-second", at(12))).proposal().proposalId();
        UUID otherProposal = proposals.create(command(other, "read-other", at(10))).proposal().proposalId();
        proposals.decide(new Decide(f.student(), first, 0, "REJECT", null, "read-reject"));
        assertThat(notifications.list(f.student(), 0, 20).items())
                .anySatisfy(item -> assertThat(item.eventType()).isEqualTo("WORKOUT_SCHEDULE_PROPOSAL_PENDING"));
        assertThat(notifications.list(f.trainer(), 0, 20).items())
                .anySatisfy(item -> assertThat(item.eventType()).isEqualTo("WORKOUT_SCHEDULE_PROPOSAL_REJECTED"));
        sharing.grantOrReplace(f.student(), f.relationship(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, 0L, UUID.randomUUID());

        mvc.perform(trainerProposals(f.trainer(), f.student()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].proposalId").value(second.toString()))
                .andExpect(jsonPath("$.items[0].trainerDisplayName").value("Person"))
                .andExpect(jsonPath("$.items[0].sourcePlanName").value("Trainer plan"))
                .andExpect(jsonPath("$.items[0].items[0].sourceSessionName").value("Session"))
                .andExpect(jsonPath("$.items[0].items[0].sourceSessionFocus").value(nullValue()));
        mvc.perform(trainerProposals(f.trainer(), f.student()).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].proposalId").value(first.toString()));
        mvc.perform(trainerProposals(f.trainer(), f.student()).param("status", "PENDING"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].proposalId").value(second.toString()));
        mvc.perform(trainerProposals(f.trainer(), f.student()).param("status", "REJECTED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].proposalId").value(first.toString()));
        mvc.perform(trainerProposals(f.trainer(), other.student()))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString(otherProposal.toString()))));
        mvc.perform(get("/api/v1/planned-workout-schedule-proposals/{id}", second)
                .with(jwt().jwt(token -> token.subject(f.trainer().toString()))))
                .andExpect(status().isOk());
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () ->
                proposals.withdraw(new Withdraw(f.trainer(), second, 0, "view-cannot-withdraw")));
    }

    @Test void trainerListConcealsRevokedPausedEndedAndRejectsInvalidPaging() throws Exception {
        Fixture revoked = fixture(), paused = fixture(), ended = fixture();
        UUID secret = proposals.create(command(revoked, "secret-proposal", at(10))).proposal().proposalId();
        proposals.create(command(paused, "paused-proposal", at(10)));
        proposals.create(command(ended, "ended-proposal", at(10)));
        mvc.perform(trainerProposals(revoked.trainer(), revoked.student()).param("status", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("WORKOUT_SCHEDULE_VALIDATION_FAILED"));
        mvc.perform(trainerProposals(revoked.trainer(), revoked.student()).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(trainerProposals(revoked.trainer(), revoked.student()).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(trainerProposals(revoked.trainer(), revoked.student()).param("page", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("WORKOUT_SCHEDULE_VALIDATION_FAILED"));

        sharing.revoke(revoked.student(), revoked.relationship(), revoked.permission(), 0,
                "Revoked schedule access", UUID.randomUUID());
        assertThat(notifications.list(revoked.trainer(), 0, 20).items())
                .noneMatch(item -> item.eventType().startsWith("WORKOUT_SCHEDULE_PROPOSAL_"));
        lifecycle.relationshipAction(paused.student(), paused.relationship(), "PAUSE", 1,
                null, UUID.randomUUID());
        lifecycle.relationshipAction(ended.student(), ended.relationship(), "END", 1,
                "Coaching ended", UUID.randomUUID());
        for (Fixture f : List.of(revoked, paused, ended)) {
            mvc.perform(trainerProposals(f.trainer(), f.student()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .string(not(containsString(secret.toString()))));
        }
        mvc.perform(trainerProposals(ended.student(), ended.student()))
                .andExpect(status().isNotFound());
    }

    @Test void studentKeepsProposalAndSeesCurrentTrainerNameWithExactSourceAfterEnd() throws Exception {
        Fixture f = fixture();
        UUID id = proposals.create(command(f, "old-version-proposal", at(10))).proposal().proposalId();
        jdbc.update("UPDATE fitness.users SET display_name='Updated Trainer' WHERE id=?", f.trainer());
        Timestamp boundary = jdbc.queryForObject("SELECT clock_timestamp() + interval '1 second'", Timestamp.class);
        jdbc.update("UPDATE fitness.workout_plan_versions SET effective_until=? WHERE id=?", boundary, f.version());
        UUID nextVersion = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions
                (id,workout_plan_id,version_number,effective_from,effective_until,change_level,created_by)
                VALUES (?,?,2,?,?,'MAJOR',?)
                """, nextVersion, f.plan(), boundary,
                Timestamp.from(boundary.toInstant().plusSeconds(1)), f.trainer());
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id,workout_plan_version_id,week_number,day_number,sequence_number,name,session_focus,supervision_requirement)
                VALUES (?,?,1,1,1,'New version session','New focus','SELF_ALLOWED')
                """, UUID.randomUUID(), nextVersion);
        jdbc.update("""
                UPDATE fitness.workout_plan_versions
                SET effective_until=NULL,locked_at=?,locked_by=?,lock_reason='ACTIVATED'
                WHERE id=?
                """, boundary, f.trainer(), nextVersion);
        lifecycle.relationshipAction(f.student(), f.relationship(), "END", 1,
                "Coaching ended", UUID.randomUUID());
        mvc.perform(get("/api/v1/planned-workout-schedule-proposals/me")
                .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].proposalId").value(id.toString()))
                .andExpect(jsonPath("$.items[0].trainerDisplayName").value("Updated Trainer"))
                .andExpect(jsonPath("$.items[0].sourcePlanName").value("Trainer plan"))
                .andExpect(jsonPath("$.items[0].sourcePlanVersionId").value(f.version().toString()))
                .andExpect(jsonPath("$.items[0].items[0].sourceSessionName").value("Session"));
        mvc.perform(get("/api/v1/planned-workout-schedule-proposals/{id}", id)
                .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainerDisplayName").value("Updated Trainer"));
        mvc.perform(trainerProposals(f.trainer(), f.student())).andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder trainerProposals(
            UUID trainer, UUID student) {
        return get("/api/v1/students/{studentId}/planned-workout-schedule-proposals", student)
                .with(jwt().jwt(token -> token.subject(trainer.toString())));
    }

    @Test void newTrainerCannotListFormerTrainersProposalForSameStudent() throws Exception {
        Fixture former = fixture();
        proposals.create(command(former, "former-proposal", at(10)));
        lifecycle.relationshipAction(former.student(), former.relationship(), "END", 1,
                "Coaching ended", UUID.randomUUID());
        UUID nextTrainer = person("TRAINER");
        Outcome pending = lifecycle.initiate(former.student(), former.student(), nextTrainer, UUID.randomUUID());
        Outcome active = lifecycle.relationshipAction(nextTrainer, pending.relationship().id(), "ACCEPT",
                0, null, UUID.randomUUID());
        sharing.grantOrReplace(former.student(), active.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, null, UUID.randomUUID());

        mvc.perform(trainerProposals(nextTrainer, former.student()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(trainerProposals(former.trainer(), former.student()))
                .andExpect(status().isNotFound());
    }

    @Test void createListWithdrawAndReplayExposeOneTerminalProposal() throws Exception {
        Fixture f = fixture();
        UUID id = proposals.create(command(f, "list-withdraw-create", at(10))).proposal().proposalId();
        mvc.perform(trainerProposals(f.trainer(), f.student()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].proposalId").value(id.toString()))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));
        String body = "{\"expectedProposalVersion\":0,\"commandKey\":\"list-withdraw-key\"}";
        for (boolean replayed : List.of(false, true)) {
            mvc.perform(post("/api/v1/planned-workout-schedule-proposals/{id}/withdraw", id)
                    .with(jwt().jwt(token -> token.subject(f.trainer().toString())))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.proposal.status").value("WITHDRAWN"))
                    .andExpect(jsonPath("$.proposal.proposalVersion").value(1))
                    .andExpect(jsonPath("$.proposal.trainerDisplayName").value("Person"))
                    .andExpect(jsonPath("$.replayed").value(replayed));
        }
        mvc.perform(trainerProposals(f.trainer(), f.student()).param("status", "WITHDRAWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].proposalId").value(id.toString()));
    }

    @Test void trainerCalendarPresentsConfirmedBatchIntentButNeverStudentActions() throws Exception {
        Fixture f = fixture();
        var pending = proposals.create(command(f, "trainer-calendar-source", at(10))).proposal();
        var accepted = proposals.decide(new Decide(f.student(), pending.proposalId(), 0,
                "ACCEPT", null, "trainer-calendar-accept"));
        UUID occurrence = accepted.confirmedBatch().items().getFirst().occurrenceId();
        sharing.grantOrReplace(f.student(), f.relationship(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, 0L, UUID.randomUUID());
        Instant at = at(10).toInstant(ZoneOffset.UTC);
        mvc.perform(trainerCalendar(f.trainer(), f.student(), at.minusSeconds(1).toString(),
                        at.plusSeconds(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].occurrenceId").value(occurrence.toString()))
                .andExpect(jsonPath("$.items[0].occurrenceVersion").value(0))
                .andExpect(jsonPath("$.items[0].timezone").value("Etc/UTC"))
                .andExpect(jsonPath("$.items[0].localStart").value(at(10).toString()))
                .andExpect(jsonPath("$.items[0].sessionSummary.planSessionId").value(f.session().toString()))
                .andExpect(jsonPath("$.items[0].availableActions").isEmpty());
    }

    @Test void trainerCalendarUsesCurrentViewGrantAndStudentProjectionWithoutExecutionActions() throws Exception {
        Fixture f = fixture();
        sharing.grantOrReplace(f.student(), f.relationship(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, 0L, UUID.randomUUID());
        Instant at = at(10).toInstant(ZoneOffset.UTC);
        UUID first = legacyCalendarRow(f.student(), f.session(), at);
        UUID second = legacyCalendarRow(f.student(), f.session(), at);
        UUID before = first.toString().compareTo(second.toString()) < 0 ? first : second;
        UUID after = before.equals(first) ? second : first;
        UUID orphan = legacyCalendarRow(f.student(), null, at.plusSeconds(3600));
        String from = at.minusSeconds(1).atOffset(ZoneOffset.ofHours(7)).toString();
        String until = at.plusSeconds(3601).atOffset(ZoneOffset.ofHours(7)).toString();

        mvc.perform(trainerCalendar(f.trainer(), f.student(), from, until).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items[0].occurrenceId").value(before.toString()))
                .andExpect(jsonPath("$.items[0].occurrenceVersion").value(0))
                .andExpect(jsonPath("$.items[0].planSummary.planId").value(f.plan().toString()))
                .andExpect(jsonPath("$.items[0].sessionSummary.planSessionId").value(f.session().toString()))
                .andExpect(jsonPath("$.items[0].availableActions").isEmpty())
                .andExpect(jsonPath("$.items[0].plannedEndAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].timezone").value(nullValue()))
                .andExpect(jsonPath("$.items[0].localStart").value(nullValue()))
                .andExpect(jsonPath("$.items[0].localEnd").value(nullValue()))
                .andExpect(jsonPath("$.items[0].startUtcOffset").value(nullValue()))
                .andExpect(jsonPath("$.items[0].endUtcOffset").value(nullValue()));
        mvc.perform(trainerCalendar(f.trainer(), f.student(), from, until)
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].occurrenceId").value(after.toString()));
        mvc.perform(trainerCalendar(f.trainer(), f.student(), from, until)
                        .param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].occurrenceId").value(orphan.toString()))
                .andExpect(jsonPath("$.items[0].planSummary").value(nullValue()))
                .andExpect(jsonPath("$.items[0].sessionSummary").value(nullValue()))
                .andExpect(jsonPath("$.items[0].availableActions").isEmpty());
        mvc.perform(trainerCalendar(f.trainer(), f.student(), at.toString(),
                        at.plus(93, java.time.temporal.ChronoUnit.DAYS).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(trainerCalendar(f.trainer(), f.student(), at.toString(),
                        at.plus(93, java.time.temporal.ChronoUnit.DAYS).plusNanos(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("WORKOUT_SCHEDULE_WINDOW_INVALID"));
    }

    @Test void trainerCalendarConcealsUnrelatedRevokedAndFormerTrainer() throws Exception {
        Fixture f = fixture(), other = fixture(), revoked = fixture(), former = fixture(), expired = fixture();
        Instant at = at(10).toInstant(ZoneOffset.UTC);
        UUID secret = legacyCalendarRow(f.student(), f.session(), at);
        String from = at.minusSeconds(1).toString(), until = at.plusSeconds(1).toString();
        mvc.perform(trainerCalendar(other.trainer(), f.student(), from, until))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PLANNED_WORKOUT_NOT_FOUND"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString(secret.toString()))));
        mvc.perform(trainerCalendar(f.trainer(), other.student(), from, until))
                .andExpect(status().isNotFound());
        sharing.revoke(revoked.student(), revoked.relationship(), revoked.permission(), 0,
                "Student revoked calendar access", UUID.randomUUID());
        mvc.perform(trainerCalendar(revoked.trainer(), revoked.student(), from, until))
                .andExpect(status().isNotFound());
        lifecycle.relationshipAction(former.student(), former.relationship(), "PAUSE", 1,
                null, UUID.randomUUID());
        mvc.perform(trainerCalendar(former.trainer(), former.student(), from, until))
                .andExpect(status().isNotFound());
        lifecycle.relationshipAction(expired.student(), expired.relationship(), "END", 1,
                "Coaching ended", UUID.randomUUID());
        mvc.perform(trainerCalendar(expired.trainer(), expired.student(), from, until))
                .andExpect(status().isNotFound());
        mvc.perform(trainerCalendar(f.student(), f.student(), from, until))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder trainerCalendar(
            UUID trainer, UUID student, String from, String until) {
        return get("/api/v1/students/{studentId}/planned-workouts", student)
                .param("from", from).param("until", until)
                .with(jwt().jwt(token -> token.subject(trainer.toString())));
    }

    private UUID legacyCalendarRow(UUID student, UUID session, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,planned_start_at,original_planned_start_at,created_by)
                VALUES (?,?,?,?,?,?)
                """, id, student, session, Timestamp.from(at), Timestamp.from(at), student);
        return id;
    }

    @Test void httpCreateListDetailAndDecisionUseContractShapes() throws Exception {
        Fixture f = fixture();
        LocalDateTime start = at(10);
        String body = """
                {"planId":"%s","sourcePlanVersionId":"%s","expectedPlanAggregateVersion":1,
                 "weekAnchorDate":"%s","timezone":"Etc/UTC","commandKey":"http-create",
                 "items":[{"clientItemId":"%s","planSessionId":"%s","weekNumber":1,
                  "dayNumber":1,"sequenceNumber":1,"localStart":"%s","localEnd":"%s",
                  "startUtcOffset":"+00:00","endUtcOffset":"+00:00"}]}
                """.formatted(f.plan(), f.version(), anchor(), UUID.randomUUID(), f.session(),
                start, start.plusHours(1));
        String created = mvc.perform(post("/api/v1/students/{student}/planned-workout-schedule-proposals", f.student())
                .with(jwt().jwt(jwt -> jwt.subject(f.trainer().toString())))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposal.status").value("PENDING"))
                .andExpect(jsonPath("$.proposal.proposalVersion").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(countOccurrences(f)).isZero();
        String proposalId = new tools.jackson.databind.ObjectMapper().readTree(created)
                .get("proposal").get("proposalId").asText();
        mvc.perform(get("/api/v1/planned-workout-schedule-proposals/me")
                .with(jwt().jwt(jwt -> jwt.subject(f.student().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].proposalId").value(proposalId));
        mvc.perform(get("/api/v1/planned-workout-schedule-proposals/{id}", proposalId)
                .with(jwt().jwt(jwt -> jwt.subject(f.trainer().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourcePlanVersionId").value(f.version().toString()));
        mvc.perform(post("/api/v1/planned-workout-schedule-proposals/{id}/decisions", proposalId)
                .with(jwt().jwt(jwt -> jwt.subject(f.student().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedProposalVersion\":0,\"decision\":\"ACCEPT\",\"commandKey\":\"http-accept\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.confirmedBatch.items[0].occurrenceVersion").value(0));
        assertThat(countOccurrences(f)).isOne();
    }

    @Test void trainerProposalCreatesNoOccurrenceAndStudentAcceptsWholeBatchWithReplay() {
        Fixture f = fixture();
        Create command = command(f, "proposal-create", at(10));
        CommandResult created = proposals.create(command);
        assertThat(created.proposal().status()).isEqualTo("PENDING");
        assertThat(created.proposal().proposalVersion()).isZero();
        assertThat(created.proposal().coachingPeriodId()).isEqualTo(f.period());
        assertThat(proposals.create(command)).isEqualTo(new CommandResult(created.proposal(), true));
        assertThat(proposals.listMine(f.student(), "PENDING", 0, 20).items()).hasSize(1);
        assertThat(proposals.detail(f.trainer(), created.proposal().proposalId())).isEqualTo(created.proposal());
        assertThat(countOccurrences(f)).isZero();

        Decide decide = new Decide(f.student(), created.proposal().proposalId(), 0, "ACCEPT", null, "accept-key");
        DecisionResult accepted = proposals.decide(decide);
        assertThat(accepted.proposal().status()).isEqualTo("ACCEPTED");
        assertThat(accepted.proposal().proposalVersion()).isOne();
        assertThat(accepted.confirmedBatch().sourceProposalId()).isEqualTo(created.proposal().proposalId());
        assertThat(accepted.confirmedBatch().items().getFirst().occurrenceVersion()).isZero();
        assertThat(countOccurrences(f)).isOne();
        assertThat(proposals.decide(decide).replayed()).isTrue();
        assertThat(countOccurrences(f)).isOne();
        assertThatThrownBy(() -> proposals.decide(new Decide(f.student(), created.proposal().proposalId(),
                0, "REJECT", null, "accept-key"))).isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code").isEqualTo("WORKOUT_SCHEDULE_IDEMPOTENCY_CONFLICT");
    }

    @Test void rejectDoesNotRunUnknownEndGuardAndWithdrawNeedsCurrentAuthority() {
        Fixture f = fixture();
        UUID proposal = proposals.create(command(f, "reject-propose", at(10))).proposal().proposalId();
        insertUnknownEnd(f);
        DecisionResult rejected = proposals.decide(new Decide(f.student(), proposal, 0, "REJECT", "Không tập", "reject"));
        assertThat(rejected.confirmedBatch()).isNull();
        assertThat(rejected.proposal().status()).isEqualTo("REJECTED");
        assertThat(countOccurrences(f)).isOne(); // the legacy fixture only
        UUID another = proposals.create(command(f, "withdraw-propose", at(12))).proposal().proposalId();
        assertThat(proposals.withdraw(new Withdraw(f.trainer(), another, 0, "withdraw")).proposal().status())
                .isEqualTo("WITHDRAWN");
        assertThat(proposals.withdraw(new Withdraw(f.trainer(), another, 0, "withdraw")).replayed()).isTrue();
    }

    @Test void acceptUnknownEndDuplicateAndOverlapRollBackProposalTransition() {
        Fixture unknown = fixture();
        UUID proposal = proposals.create(command(unknown, "unknown-propose", at(10))).proposal().proposalId();
        insertUnknownEnd(unknown);
        assertFailure("WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN", () ->
                proposals.decide(new Decide(unknown.student(), proposal, 0, "ACCEPT", null, "unknown-accept")));
        assertThat(proposals.detail(unknown.student(), proposal).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_batches WHERE student_id=?",
                Integer.class, unknown.student())).isZero();

        Fixture f = fixture();
        UUID first = proposals.create(command(f, "first-propose", at(10))).proposal().proposalId();
        proposals.decide(new Decide(f.student(), first, 0, "ACCEPT", null, "first-accept"));
        UUID duplicate = proposals.create(command(f, "duplicate-propose", at(10))).proposal().proposalId();
        assertFailure("WORKOUT_SCHEDULE_DUPLICATE", () ->
                proposals.decide(new Decide(f.student(), duplicate, 0, "ACCEPT", null, "duplicate-accept")));
        UUID overlap = proposals.create(command(f, "overlap-propose", at(10).plusMinutes(30))).proposal().proposalId();
        assertFailure("WORKOUT_SCHEDULE_OVERLAP", () ->
                proposals.decide(new Decide(f.student(), overlap, 0, "ACCEPT", null, "overlap-accept")));
        assertThat(countOccurrences(f)).isOne();
    }

    @Test void revokedGrantInvalidatesAcceptAndFormerTrainerCannotReadOrWithdraw() throws Exception {
        Fixture f = fixture();
        UUID proposal = proposals.create(command(f, "revoke-propose", at(10))).proposal().proposalId();
        sharing.revoke(f.student(), f.relationship(), f.permission(), 0,
                "No schedule sharing", UUID.randomUUID());
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_INVALIDATED", () ->
                proposals.decide(new Decide(f.student(), proposal, 0, "ACCEPT", null, "revoke-accept")));
        assertThat(countOccurrences(f)).isZero();
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () -> proposals.detail(f.trainer(), proposal));
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () ->
                proposals.withdraw(new Withdraw(f.trainer(), proposal, 0, "revoke-withdraw")));
        mvc.perform(get("/api/v1/planned-workout-schedule-proposals/{id}", proposal)
                .with(jwt().jwt(jwt -> jwt.subject(f.trainer().toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND"));
    }

    @Test void stalePlanAndProposalVersionCannotAcceptOrRemap() {
        Fixture f = fixture();
        UUID proposal = proposals.create(command(f, "stale-propose", at(10))).proposal().proposalId();
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_VERSION_CONFLICT", () ->
                proposals.decide(new Decide(f.student(), proposal, 3, "ACCEPT", null, "stale-proposal")));
        jdbc.update("UPDATE fitness.workout_plans SET version=version+1 WHERE id=?", f.plan());
        assertFailure("WORKOUT_SCHEDULE_PLAN_VERSION_STALE", () ->
                proposals.decide(new Decide(f.student(), proposal, 0, "ACCEPT", null, "stale-plan")));
        assertThat(countOccurrences(f)).isZero();
        assertThat(proposals.detail(f.student(), proposal).status()).isEqualTo("PENDING");
    }

    @Test void changedEffectiveVersionIsNotRemappedAndOldReceiptStillReplaysForStudent() {
        Fixture f = fixture();
        UUID accepted = proposals.create(command(f, "accepted-before-change", at(10))).proposal().proposalId();
        Decide acceptedCommand = new Decide(f.student(), accepted, 0, "ACCEPT", null, "accepted-replay");
        proposals.decide(acceptedCommand);
        UUID stale = proposals.create(command(f, "version-before-change", at(13))).proposal().proposalId();
        jdbc.update("UPDATE fitness.workout_plan_versions SET effective_until=clock_timestamp() WHERE id=?", f.version());
        UUID next = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions
                (id,workout_plan_id,version_number,change_level,created_by,effective_from,locked_at,locked_by,lock_reason)
                VALUES (?,?,2,'MAJOR',?,clock_timestamp(),clock_timestamp(),?,'ACTIVATED')
                """, next, f.plan(), f.trainer(), f.trainer());
        assertFailure("WORKOUT_SCHEDULE_PLAN_VERSION_STALE", () ->
                proposals.decide(new Decide(f.student(), stale, 0, "ACCEPT", null, "stale-source")));
        assertThat(proposals.decide(acceptedCommand).replayed()).isTrue();
        assertThat(countOccurrences(f)).isOne();
    }

    @Test void proposalValidationRejectsDstGapAndAcceptRechecksNinetyDayBoundary() {
        Fixture f = fixture();
        LocalDate week = LocalDate.of(2027, 3, 8);
        LocalDateTime gap = LocalDateTime.of(2027, 3, 14, 2, 30);
        Create invalid = new Create(f.trainer(), f.student(), f.plan(), f.version(), 1, week,
                "America/New_York", List.of(new Item(UUID.randomUUID(), f.session(), 1, 1, 1,
                gap, gap.plusHours(1), "-05:00", "-04:00")), "gap-key");
        assertFailure("VALIDATION_FAILED", () -> proposals.create(invalid));
        LocalDate distant = LocalDate.now(ZoneOffset.UTC).plusWeeks(15)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        LocalDateTime start = distant.atTime(10, 0);
        Create future = new Create(f.trainer(), f.student(), f.plan(), f.version(), 1, distant,
                "Etc/UTC", List.of(new Item(UUID.randomUUID(), f.session(), 1, 1, 1,
                start, start.plusHours(1), "+00:00", "+00:00")), "future-propose");
        UUID proposal = proposals.create(future).proposal().proposalId();
        assertFailure("VALIDATION_FAILED", () ->
                proposals.decide(new Decide(f.student(), proposal, 0, "ACCEPT", null, "future-accept")));
        assertThat(countOccurrences(f)).isZero();
    }

    @Test void acceptingMultiItemProposalRollsBackEveryNewItemOnOverlap() {
        Fixture f = fixture();
        UUID first = proposals.create(command(f, "occupy-first", at(10))).proposal().proposalId();
        proposals.decide(new Decide(f.student(), first, 0, "ACCEPT", null, "occupy-accept"));
        Create multi = new Create(f.trainer(), f.student(), f.plan(), f.version(), 1, anchor(), "Etc/UTC",
                List.of(new Item(UUID.randomUUID(), f.session(), 1, 1, 1, at(12), at(13), "+00:00", "+00:00"),
                        new Item(UUID.randomUUID(), f.secondSession(), 1, 2, 1, at(10).plusMinutes(30),
                                at(11).plusMinutes(30), "+00:00", "+00:00")), "multi-propose");
        UUID proposal = proposals.create(multi).proposal().proposalId();
        assertFailure("WORKOUT_SCHEDULE_OVERLAP", () ->
                proposals.decide(new Decide(f.student(), proposal, 0, "ACCEPT", null, "multi-accept")));
        assertThat(countOccurrences(f)).isOne();
        assertThat(proposals.detail(f.student(), proposal).status()).isEqualTo("PENDING");
    }

    @Test void viewGrantAllowsDetailButNotMutationAndUnrelatedStudentsAreConcealed() {
        Fixture f = fixture();
        UUID proposal = proposals.create(command(f, "view-propose", at(10))).proposal().proposalId();
        sharing.grantOrReplace(f.student(), f.relationship(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, 0L, UUID.randomUUID());
        assertThat(proposals.detail(f.trainer(), proposal).proposalId()).isEqualTo(proposal);
        assertFailure("WORKOUT_SCHEDULE_ACCESS_DENIED", () ->
                proposals.create(command(f, "view-create", at(12))));
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () ->
                proposals.withdraw(new Withdraw(f.trainer(), proposal, 0, "view-withdraw")));
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_INVALIDATED", () ->
                proposals.decide(new Decide(f.student(), proposal, 0, "ACCEPT", null, "view-accept")));
        UUID unrelated = person("STUDENT");
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () -> proposals.detail(unrelated, proposal));
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () ->
                proposals.decide(new Decide(unrelated, proposal, 0, "REJECT", null, "unrelated")));
        assertThat(proposals.listMine(unrelated, null, 0, 20).items()).isEmpty();
    }

    @Test void crossStudentPlanAndUnknownProposalAreConcealed() {
        Fixture owner = fixture(), other = fixture();
        Create c = command(owner, "cross-plan", at(10));
        assertFailure("WORKOUT_PLAN_NOT_FOUND", () -> proposals.create(new Create(other.trainer(),
                other.student(), c.planId(), c.sourcePlanVersionId(), c.expectedPlanAggregateVersion(),
                c.weekAnchorDate(), c.timezone(), c.items(), c.commandKey())));
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () ->
                proposals.detail(owner.trainer(), UUID.randomUUID()));
    }

    @Test void endedRelationshipConcealsFormerTrainerButStudentMayRejectPendingProposal() {
        Fixture f = fixture();
        UUID proposal = proposals.create(command(f, "end-propose", at(10))).proposal().proposalId();
        lifecycle.relationshipAction(f.student(), f.relationship(), "END", 1,
                "Coaching ended", UUID.randomUUID());
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () -> proposals.detail(f.trainer(), proposal));
        assertThat(proposals.decide(new Decide(f.student(), proposal, 0, "REJECT", null, "end-reject"))
                .proposal().status()).isEqualTo("REJECTED");
        assertThat(countOccurrences(f)).isZero();
    }

    @Test void differentKeysConcurrentAcceptanceCreatesExactlyOneOccurrence() throws Exception {
        Fixture f = fixture();
        UUID first = proposals.create(command(f, "concurrent-a", at(10))).proposal().proposalId();
        UUID second = proposals.create(command(f, "concurrent-b", at(10))).proposal().proposalId();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> decideOutcome(f.student(), first, "concurrent-accept-a"));
            var b = pool.submit(() -> decideOutcome(f.student(), second, "concurrent-accept-b"));
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder("ACCEPTED", "WORKOUT_SCHEDULE_DUPLICATE");
        }
        assertThat(countOccurrences(f)).isOne();
    }

    private String decideOutcome(UUID student, UUID proposal, String key) {
        try { proposals.decide(new Decide(student, proposal, 0, "ACCEPT", null, key)); return "ACCEPTED"; }
        catch (WorkoutScheduleFailure failure) { return failure.code(); }
    }
    private void assertFailure(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code").isEqualTo(code);
    }
    private int countOccurrences(Fixture f) {
        return jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE student_id=?",
                Integer.class, f.student());
    }
    private void insertUnknownEnd(Fixture f) {
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,planned_start_at,original_planned_start_at,created_by)
                VALUES (?,?,?,clock_timestamp()+interval '2 days',clock_timestamp()+interval '2 days',?)
                """, UUID.randomUUID(), f.student(), f.session(), f.student());
    }
    private Create command(Fixture f, String key, LocalDateTime start) {
        Item item = new Item(UUID.randomUUID(), f.session(), 1, 1, 1,
                start, start.plusHours(1), "+00:00", "+00:00");
        return new Create(f.trainer(), f.student(), f.plan(), f.version(), 1, anchor(), "Etc/UTC",
                List.of(item), key);
    }
    private static LocalDate anchor() {
        return LocalDate.now(ZoneOffset.UTC).plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
    private static LocalDateTime at(int hour) { return anchor().atTime(hour, 0); }

    private Fixture fixture() {
        return fixture("SELF_ALLOWED");
    }
    private Fixture fixture(String supervision) {
        return fixture(supervision, false);
    }

    private Fixture fixture(String supervision, boolean startable) {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        Outcome active = lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT",
                0, null, UUID.randomUUID());
        var permission = sharing.grantOrReplace(student, active.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.CONTRIBUTE, null, null, null, null, UUID.randomUUID());
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID();
        UUID session = UUID.randomUUID(), secondSession = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,name,source,status,created_by,decision_owner_type,decision_owner_id,coaching_period_id)
                VALUES (?,?,'Trainer plan','TRAINER','DRAFT',?,'TRAINER',?,?)
                """, plan, student, trainer, trainer, active.currentPeriod().id());
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions
                (id,workout_plan_id,version_number,change_level,created_by)
                VALUES (?,?,1,'INITIAL',?)
                """, version, plan, trainer);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id,workout_plan_version_id,week_number,day_number,sequence_number,name,supervision_requirement)
                 VALUES (?,?,1,1,1,'Session',?::fitness.supervision_requirement)
                 """, session, version, supervision);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id,workout_plan_version_id,week_number,day_number,sequence_number,name,supervision_requirement)
                VALUES (?,?,1,2,1,'Second','SELF_ALLOWED')
                """, secondSession, version);
        if (startable) addStartablePrescription(session);
        jdbc.update("""
                UPDATE fitness.workout_plan_versions
                SET effective_from=clock_timestamp(),locked_at=clock_timestamp(),locked_by=?,lock_reason='ACTIVATED'
                WHERE id=?
                """, trainer, version);
        jdbc.update("""
                UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1,
                    assigned_by=?,assigned_at=clock_timestamp() WHERE id=?
                """, trainer, plan);
        return new Fixture(student, trainer, active.relationship().id(), active.currentPeriod().id(),
                permission.id(), plan, version, session, secondSession);
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
    private record Fixture(UUID student, UUID trainer, UUID relationship, UUID period, UUID permission,
                           UUID plan, UUID version, UUID session, UUID secondSession) {}
}
