package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.ConfirmBatch;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.Item;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.*;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
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
class WorkoutScheduleChangeIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("workout_change_b06").withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired WorkoutScheduleBatchUseCase batches;
    @Autowired WorkoutScheduleChangeUseCase changes;
    @Autowired WorkoutScheduleCalendarQuery calendar;
    @Autowired WorkoutExecutionCommandUseCase execution;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void startWinningSealsOccurrenceAndRejectsRescheduleWithoutEffects() throws Exception {
        Fixture f = startableFixture();
        UUID source = confirmed(f, "start-wins-source");
        String moveKey = "start-wins-move-" + source;
        CountDownLatch effectReady = new CountDownLatch(1), releaseCommit = new CountDownLatch(1);
        CountDownLatch moveInvoked = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> start = pool.submit(() -> heldTransaction(() -> execution.start(
                    new WorkoutExecutionCommandUseCase.StartCommand(source, f.student(), 0,
                            "start-wins-start-" + source)), effectReady, releaseCommit,
                    TransactionDefinition.ISOLATION_REPEATABLE_READ));
            assertThat(effectReady.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> move = pool.submit(() -> {
                moveInvoked.countDown();
                try { changes.reschedule(reschedule(f, source, moveKey, localStart().plusDays(2)));
                    return "MOVED";
                } catch (WorkoutScheduleFailure failure) { return failure.code(); }
            });
            assertThat(moveInvoked.await(10, TimeUnit.SECONDS)).isTrue();
            releaseCommit.countDown();
            start.get(20, TimeUnit.SECONDS);
            assertThat(move.get(20, TimeUnit.SECONDS)).isEqualTo("WORKOUT_SCHEDULE_OCCURRENCE_VERSION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.planned_workouts WHERE id=?",
                String.class, source)).isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT execution_started_at IS NOT NULL AND version=1 FROM fitness.planned_workouts WHERE id=?",
                Boolean.class, source)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs WHERE planned_workout_id=?",
                Integer.class, source)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                Integer.class, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_occurrence_changes WHERE previous_occurrence_id=?",
                Integer.class, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_change_receipts WHERE actor_id=? AND command_key=?",
                Integer.class, f.student(), moveKey)).isZero();
    }

    @Test void rescheduleWinningRejectsStartWithoutExecution() throws Exception {
        Fixture f = startableFixture();
        UUID source = confirmed(f, "move-wins-source");
        CountDownLatch effectReady = new CountDownLatch(1), releaseCommit = new CountDownLatch(1);
        CountDownLatch startSnapshotReady = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<ChangeResult> move = pool.submit(() -> heldTransaction(() -> changes.reschedule(
                    reschedule(f, source, "move-wins-move-" + source, localStart().plusDays(2))),
                    effectReady, releaseCommit, TransactionDefinition.ISOLATION_READ_COMMITTED));
            assertThat(effectReady.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> start = pool.submit(() -> {
                try {
                    return transactionTemplate(TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status -> {
                        // Establish the older PostgreSQL snapshot before the reschedule commits.
                        assertThat(jdbc.queryForObject("SELECT version FROM fitness.planned_workouts WHERE id=?",
                                Long.class, source)).isZero();
                        startSnapshotReady.countDown();
                        execution.start(new WorkoutExecutionCommandUseCase.StartCommand(source,
                                f.student(), 0, "move-wins-start-" + source));
                        return "STARTED";
                    });
                } catch (WorkoutExecutionFailure failure) { return failure.error().name(); }
            });
            assertThat(startSnapshotReady.await(10, TimeUnit.SECONDS)).isTrue();
            releaseCommit.countDown();
            ChangeResult result = move.get(20, TimeUnit.SECONDS);
            assertThat(start.get(20, TimeUnit.SECONDS)).isIn(
                    "PLANNED_WORKOUT_NOT_STARTABLE", "WORKOUT_EXECUTION_VERSION_CONFLICT");
            assertThat(result.previousStatus()).isEqualTo("RESCHEDULED");
        }
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.planned_workouts WHERE id=?",
                String.class, source)).isEqualTo("RESCHEDULED");
        assertThat(jdbc.queryForObject("SELECT execution_started_at IS NULL FROM fitness.planned_workouts WHERE id=?",
                Boolean.class, source)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                Integer.class, source)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs WHERE planned_workout_id=?",
                Integer.class, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_execution_command_receipts WHERE actor_id=? AND command_key=?",
                Integer.class, f.student(), "move-wins-start-" + source)).isZero();
    }

    @Test void rescheduleRetainsSourceCreatesOneSuccessorAndReplays() {
        Fixture f = fixture();
        UUID original = confirmed(f, "source");
        Reschedule cmd = reschedule(f, original, "change-1", localStart().plusDays(1));
        ChangeResult first = changes.reschedule(cmd);
        assertThat(first.previousStatus()).isEqualTo("RESCHEDULED");
        assertThat(first.replacementOccurrenceId()).isNotEqualTo(original);
        assertThat(first.previousOccurrenceVersion()).isOne();
        assertThat(first.replacementOccurrenceVersion()).isZero();
        assertThat(changes.reschedule(cmd).asReplay()).isEqualTo(first.asReplay());
        var old = calendar.detail(f.student(), original);
        var next = calendar.detail(f.student(), first.replacementOccurrenceId());
        assertThat(old.replacementOccurrenceId()).isEqualTo(next.occurrenceId());
        assertThat(next.rescheduledFromOccurrenceId()).isEqualTo(original);
        assertThat(next.originalPlannedStartAt()).isEqualTo(old.originalPlannedStartAt());
        assertThat(next.sessionSummary().planSessionId()).isEqualTo(f.session());
        assertThat(old.status()).isEqualTo("RESCHEDULED");
        assertThat(next.availableActions()).contains("RESCHEDULE", "CANCEL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_batch_items WHERE occurrence_id=?",
                Integer.class, original)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_batch_items WHERE occurrence_id=?",
                Integer.class, next.occurrenceId())).isZero();
        assertCode(() -> changes.reschedule(reschedule(f, original, "other", localStart().plusDays(2))),
                "WORKOUT_SCHEDULE_OCCURRENCE_VERSION_CONFLICT");
        assertCode(() -> changes.reschedule(new Reschedule(original, f.student(), 0,
                destination(localStart().plusDays(2)), null, "change-1")),
                "WORKOUT_SCHEDULE_IDEMPOTENCY_CONFLICT");
    }

    @Test void studentHttpRescheduleAndDetailExposeRealLineage() throws Exception {
        Fixture f = fixture();
        UUID original = confirmed(f, "http-source");
        String body = """
                {"expectedOccurrenceVersion":0,"commandKey":"http-change",
                 "destination":{"localStart":"%s","localEnd":"%s","timezone":"Etc/UTC",
                 "startUtcOffset":"+00:00","endUtcOffset":"+00:00"}}
                """.formatted(localStart().plusDays(2), localStart().plusDays(2).plusHours(1));
        String response = mvc.perform(post("/api/v1/planned-workouts/{id}/reschedule", original)
                        .contentType("application/json").content(body)
                        .with(jwt().jwt(t -> t.subject(f.student().toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.previousOccurrenceId").value(original.toString()))
                .andExpect(jsonPath("$.previousStatus").value("RESCHEDULED"))
                .andReturn().getResponse().getContentAsString();
        UUID next = UUID.fromString(mapper.readTree(response)
                .get("replacementOccurrenceId").asText());
        mvc.perform(get("/api/v1/planned-workouts/{id}", original)
                        .with(jwt().jwt(t -> t.subject(f.student().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacementOccurrenceId").value(next.toString()));
    }

    @Test void sourcePlanTerminalTransitionsDoNotRevokeConfirmedOccurrenceChanges() {
        for (String status : List.of("PAUSED", "COMPLETED", "ARCHIVED")) {
            Fixture f = fixture();
            UUID source = confirmed(f, "plan-" + status);
            jdbc.update("UPDATE fitness.workout_plans SET status=?::fitness.plan_status,version=version+1 WHERE id=?",
                    status, f.plan());
            ChangeResult result = changes.reschedule(reschedule(f, source, "move-" + status,
                    localStart().plusDays(2)));
            assertThat(result.previousStatus()).isEqualTo("RESCHEDULED");
            assertThat(calendar.detail(f.student(), result.replacementOccurrenceId()).availableActions())
                    .contains("RESCHEDULE", "CANCEL");
        }
    }

    @Test void ambiguousDstOffsetsAreExplicitAndProduceDistinctInstants() {
        LocalDateTime ambiguous = LocalDateTime.of(2026, 11, 1, 1, 30);
        for (String offset : List.of("-04:00", "-05:00")) {
            Fixture f = fixture();
            UUID source = confirmed(f, "ambiguous-" + offset);
            Destination destination = new Destination(ambiguous, LocalDateTime.of(2026, 11, 1, 2, 30),
                    "America/New_York", offset, "-05:00");
            ChangeResult result = changes.reschedule(new Reschedule(source, f.student(), 0,
                    destination, null, "ambiguous-" + offset));
            assertThat(result.newPlannedStartAt()).isEqualTo(ambiguous.toInstant(ZoneOffset.of(offset)));
        }
    }

    @Test void appointmentCannotAttachAfterChange() {
        Fixture f = fixture();
        UUID source = confirmed(f, "appointment-race");
        changes.reschedule(reschedule(f, source, "appointment-change", localStart().plusDays(2)));
        assertThatThrownBy(() -> insertAppointment(f, source, UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments WHERE planned_workout_id=?",
                Integer.class, source)).isZero();
    }

    @Test void appointmentLinkAndRescheduleRaceHasOnlyOneWinner() throws Exception {
        Fixture f = fixture();
        UUID source = confirmed(f, "appointment-concurrent");
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
            Future<String> move = pool.submit(() -> {
                ready.countDown(); go.await();
                try { changes.reschedule(reschedule(f, source, "appointment-move", localStart().plusDays(2)));
                    return "MOVED";
                } catch (WorkoutScheduleFailure failure) { return failure.code(); }
            });
            Future<String> link = pool.submit(() -> {
                ready.countDown(); go.await();
                try { insertAppointment(f, source, UUID.randomUUID()); return "LINKED"; }
                catch (org.springframework.dao.DataAccessException rejected) { return "REJECTED"; }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            String moved = move.get(20, TimeUnit.SECONDS), linked = link.get(20, TimeUnit.SECONDS);
            assertThat(List.of(moved, linked)).isIn(
                    List.of("MOVED", "REJECTED"),
                    List.of("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", "LINKED"));
        }
    }

    @Test void cancelLegacyNullEndAndConcealOtherStudent() throws Exception {
        Fixture f = fixture(), other = fixture();
        UUID legacy = legacy(f, null, localStart().toInstant(ZoneOffset.UTC));
        ChangeResult cancelled = changes.cancel(new Cancel(legacy, f.student(), 0, null, "legacy-cancel"));
        assertThat(cancelled.previousStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.replacementOccurrenceId()).isNull();
        assertThat(calendar.detail(f.student(), legacy).availableActions()).isEmpty();
        assertThat(changes.cancel(new Cancel(legacy, f.student(), 0, null, "legacy-cancel")).replayed()).isTrue();
        assertCode(() -> changes.cancel(new Cancel(legacy, other.student(), 0, null, "intruder")),
                "PLANNED_WORKOUT_NOT_FOUND");
        mvc.perform(post("/api/v1/planned-workouts/{id}/cancel", legacy)
                        .contentType("application/json")
                        .content("{\"expectedOccurrenceVersion\":0,\"commandKey\":\"intruder-http\"}")
                        .with(jwt().jwt(t -> t.subject(other.student().toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PLANNED_WORKOUT_NOT_FOUND"));
    }

    @Test void validatesDstAndWindowAndLegacySource() {
        Fixture f = fixture();
        UUID source = confirmed(f, "dst-source");
        Destination gap = new Destination(LocalDateTime.of(2027, 3, 14, 2, 30),
                LocalDateTime.of(2027, 3, 14, 3, 30), "America/New_York", "-05:00", "-04:00");
        assertCode(() -> changes.reschedule(new Reschedule(source, f.student(), 0, gap, null, "gap")),
                "WORKOUT_SCHEDULE_DST_TIME_NONEXISTENT");
        Destination invalid = new Destination(LocalDateTime.of(2027, 11, 7, 1, 30),
                LocalDateTime.of(2027, 11, 7, 2, 30), "America/New_York", "-03:00", "-05:00");
        assertCode(() -> changes.reschedule(new Reschedule(source, f.student(), 0, invalid, null, "offset")),
                "WORKOUT_SCHEDULE_DST_OFFSET_INVALID");
        assertCode(() -> changes.reschedule(reschedule(f, source, "too-far", localStart().plusDays(100))),
                "WORKOUT_SCHEDULE_WINDOW_INVALID");
        UUID legacy = legacy(f, null, localStart().plusDays(3).toInstant(ZoneOffset.UTC));
        assertCode(() -> changes.reschedule(reschedule(f, legacy, "legacy", localStart().plusDays(4))),
                "WORKOUT_SCHEDULE_LEGACY_SOURCE_UNAVAILABLE");
    }

    @Test void unknownEndOverlapDuplicateAndAppointmentRejectWithoutPartialHistory() {
        Fixture f = fixture();
        UUID old = confirmed(f, "conflicts-source");
        UUID blocker = legacy(f, null, localStart().plusDays(6).toInstant(ZoneOffset.UTC));
        assertCode(() -> changes.reschedule(reschedule(f, old, "unknown", localStart().plusDays(1))),
                "WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN");
        jdbc.update("UPDATE fitness.planned_workouts SET status='CANCELLED',version=version+1 WHERE id=?", blocker);
        assertCode(() -> changes.reschedule(reschedule(f, old, "same-instant", localStart())),
                "WORKOUT_SCHEDULE_DUPLICATE");
        UUID overlap = legacy(f, Timestamp.from(localStart().plusDays(1).plusHours(2).toInstant(ZoneOffset.UTC)).toInstant(),
                localStart().plusDays(1).toInstant(ZoneOffset.UTC));
        assertCode(() -> changes.reschedule(reschedule(f, old, "overlap", localStart().plusDays(1).plusMinutes(30))),
                "WORKOUT_SCHEDULE_OVERLAP");
        jdbc.update("UPDATE fitness.planned_workouts SET status='CANCELLED',version=version+1 WHERE id=?", overlap);
        UUID appointment = UUID.randomUUID();
        LegacyAppointmentFixture.insert(jdbc, """
                INSERT INTO fitness.appointments(id,student_id,planned_workout_id,start_at,end_at,
                    display_timezone,status,created_by)
                VALUES (?,?,?,?,?,'Etc/UTC','CANCELLED',?)
                """, appointment, f.student(), old, Timestamp.from(localStart().toInstant(ZoneOffset.UTC)),
                Timestamp.from(localStart().plusHours(1).toInstant(ZoneOffset.UTC)), f.student());
        assertCode(() -> changes.cancel(new Cancel(old, f.student(), 0, null, "linked")),
                "WORKOUT_SCHEDULE_APPOINTMENT_LINKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_occurrence_changes WHERE previous_occurrence_id=?",
                Integer.class, old)).isZero();
    }

    @Test void lateCancelRequiresReasonAndSealedOccurrenceCannotChange() {
        Fixture f = fixture();
        UUID past = legacy(f, null, Instant.now().minusSeconds(3600));
        assertCode(() -> changes.cancel(new Cancel(past, f.student(), 0, null, "late")),
                "WORKOUT_SCHEDULE_CHANGE_REASON_REQUIRED");
        assertThat(changes.cancel(new Cancel(past, f.student(), 0, "Illness", "late-with-reason"))
                .previousStatus()).isEqualTo("CANCELLED");
        UUID sealed = confirmed(f, "seal-source");
        jdbc.update("UPDATE fitness.planned_workouts SET execution_started_at=clock_timestamp(),version=version+1 WHERE id=?",
                sealed);
        assertCode(() -> changes.cancel(new Cancel(sealed, f.student(), 1, null, "sealed")),
                "WORKOUT_SCHEDULE_OCCURRENCE_SEALED");
    }

    @Test void competingCommandsCreateOnlyOneReplacement() throws Exception {
        Fixture f = fixture();
        UUID source = confirmed(f, "race-source");
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
            Callable<String> first = () -> compete(f, source, "race-a", ready, go);
            Callable<String> second = () -> compete(f, source, "race-b", ready, go);
            Future<String> a = pool.submit(first), b = pool.submit(second);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", "WORKOUT_SCHEDULE_OCCURRENCE_VERSION_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                    Integer.class, source)).isOne();
        }
    }

    @Test void skipAndRescheduleRaceLeavesExactlyOneTerminalOutcome() throws Exception {
        for (int i = 0; i < 4; i++) {
            Fixture f = fixture();
            UUID source = confirmed(f, "skip-race-source-" + i);
            try (var pool = Executors.newFixedThreadPool(2)) {
                CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
                Future<String> move = pool.submit(() -> {
                    ready.countDown(); go.await();
                    try { changes.reschedule(reschedule(f, source, "skip-race-move-" + source,
                            localStart().plusDays(2))); return "MOVED"; }
                    catch (RuntimeException failure) { return failure.getClass().getSimpleName(); }
                });
                Future<String> skip = pool.submit(() -> {
                    ready.countDown(); go.await();
                    try { execution.skip(new WorkoutExecutionCommandUseCase.SkipCommand(source, f.student(), 0,
                            "Rest", "skip-race-skip-" + source)); return "SKIPPED"; }
                    catch (RuntimeException failure) { return failure.getClass().getSimpleName(); }
                });
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
                var results = List.of(move.get(20, TimeUnit.SECONDS), skip.get(20, TimeUnit.SECONDS));
                assertThat(results.stream().filter(value -> value.equals("MOVED") || value.equals("SKIPPED")).count())
                        .isOne();
                assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.planned_workouts WHERE id=?",
                        String.class, source)).isIn("RESCHEDULED", "SKIPPED");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                        Integer.class, source)).isBetween(0, 1);
            }
        }
    }

    private String compete(Fixture f, UUID source, String key, CountDownLatch ready, CountDownLatch go)
            throws InterruptedException {
        ready.countDown(); go.await();
        try { changes.reschedule(reschedule(f, source, key, localStart().plusDays(2))); return "CREATED"; }
        catch (WorkoutScheduleFailure failure) { return failure.code(); }
    }
    private void insertAppointment(Fixture f, UUID occurrence, UUID appointment) {
        LegacyAppointmentFixture.insert(jdbc, """
                INSERT INTO fitness.appointments(id,student_id,planned_workout_id,start_at,end_at,
                    display_timezone,status,created_by)
                VALUES (?,?,?,?,?,'Etc/UTC','CANCELLED',?)
                """, appointment, f.student(), occurrence, Timestamp.from(localStart().toInstant(ZoneOffset.UTC)),
                Timestamp.from(localStart().plusHours(1).toInstant(ZoneOffset.UTC)), f.student());
    }
    private static void assertCode(Runnable command, String code) {
        assertThatThrownBy(command::run).isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code").isEqualTo(code);
    }
    private <T> T heldTransaction(Callable<T> action, CountDownLatch effectReady,
                                  CountDownLatch releaseCommit, int isolation) {
        return transactionTemplate(isolation).execute(status -> {
            try {
                T value = action.call();
                effectReady.countDown();
                if (!releaseCommit.await(10, TimeUnit.SECONDS)) throw new AssertionError("Commit release timed out");
                return value;
            } catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
    }
    private TransactionTemplate transactionTemplate(int isolation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(isolation);
        template.setTimeout(15);
        return template;
    }
    private Fixture startableFixture() {
        return fixture(true);
    }
    private UUID confirmed(Fixture f, String key) {
        return batches.confirmDirect(new ConfirmBatch(f.student(), f.plan(), f.version(), 1, anchor(),
                "Etc/UTC", List.of(new Item(UUID.randomUUID(), f.session(), 1, 1, 1,
                localStart(), localStart().plusHours(1), "+00:00", "+00:00")), key))
                .items().getFirst().occurrenceId();
    }
    private Fixture fixture() { return fixture(false); }
    private Fixture fixture(boolean startable) {
        UUID student = UUID.randomUUID(), plan = UUID.randomUUID(), version = UUID.randomUUID(), session = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?, 'hash','Student','ACTIVE','vi-VN','Etc/UTC')",
                student, student + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
        jdbc.update("""
                INSERT INTO fitness.workout_plans(id,student_id,name,source,status,created_by,decision_owner_type,decision_owner_id)
                VALUES (?,?,'Plan','STUDENT','DRAFT',?,'STUDENT',?)
                """, plan, student, student, student);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions(id,workout_plan_id,version_number,change_level,created_by)
                VALUES (?,?,1,'INITIAL',?)
                """, version, plan, student);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id,workout_plan_version_id,week_number,day_number,sequence_number,name,supervision_requirement)
                VALUES (?,?,1,1,1,'Session','SELF_ALLOWED')
                """, session, version);
        if (startable) {
            UUID exercise = UUID.randomUUID(), variation = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                    exercise, "race-exercise-" + exercise, "Race exercise", student);
            jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                    variation, exercise, "race-variation-" + variation, "Race variation");
            jdbc.update("""
                    INSERT INTO fitness.workout_plan_session_exercises
                    (id,workout_plan_session_id,exercise_variation_id,sequence_number,target_sets,target_reps_min,target_reps_max)
                    VALUES (?,?,?,1,1,8,12)
                    """, UUID.randomUUID(), session, variation);
        }
        jdbc.update("""
                UPDATE fitness.workout_plan_versions SET effective_from=clock_timestamp(),
                    locked_at=clock_timestamp(),locked_by=?,lock_reason='ACTIVATED' WHERE id=?
                """, student, version);
        jdbc.update("""
                UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1,
                    assigned_by=?,assigned_at=clock_timestamp() WHERE id=?
                """, student, plan);
        return new Fixture(student, plan, version, session);
    }
    private UUID legacy(Fixture f, Instant end, Instant start) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,planned_start_at,planned_end_at,
                 original_planned_start_at,created_by)
                VALUES (?,?,?,?,?,?,?)
                """, id, f.student(), f.session(), Timestamp.from(start), end == null ? null : Timestamp.from(end),
                Timestamp.from(start), f.student());
        return id;
    }
    private static Reschedule reschedule(Fixture f, UUID old, String key, LocalDateTime at) {
        return new Reschedule(old, f.student(), 0, destination(at), null, key);
    }
    private static Destination destination(LocalDateTime at) {
        return new Destination(at, at.plusHours(1), "Etc/UTC", "+00:00", "+00:00");
    }
    private static LocalDate anchor() {
        return LocalDate.now(ZoneOffset.UTC).plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
    private static LocalDateTime localStart() { return anchor().atTime(10, 0); }
    private record Fixture(UUID student, UUID plan, UUID version, UUID session) {}
}
