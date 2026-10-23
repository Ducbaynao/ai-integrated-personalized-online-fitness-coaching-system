package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.*;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutScheduleBatchIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("workout_schedule_b06").withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired WorkoutScheduleBatchUseCase batches;
    @Autowired WorkoutScheduleCalendarQuery calendar;
    @Autowired WorkoutExecutionCommandUseCase executionCommands;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired AppointmentService appointments;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @MockitoSpyBean AuditService audit;
    @BeforeEach void resetAudit() { reset(audit); }

    @Test void auditFailureRollsBackEveryOccurrenceAndReceipt() {
        Fixture f = fixture();
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordAudit(any());
        assertThatThrownBy(() -> batches.confirmDirect(command(f, "audit-rollback", item(f, startAtNextWeek()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_batches WHERE student_id=?",
                Integer.class, f.student())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE student_id=?",
                Integer.class, f.student())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_command_receipts WHERE actor_id=?",
                Integer.class, f.student())).isZero();
    }

    @Test void confirmsRealOccurrenceAndReplaysWithoutAnotherEffect() {
        Fixture f = fixture();
        ConfirmBatch c = command(f, "replay", item(f, startAtNextWeek()));
        ConfirmedBatch first = batches.confirmDirect(c);
        ConfirmedBatch replay = batches.confirmDirect(c);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.batchId()).isEqualTo(first.batchId());
        assertThat(first.items().getFirst().occurrenceVersion()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE schedule_batch_id=?",
                Integer.class, first.batchId())).isOne();
        assertThat(jdbc.queryForObject("SELECT schedule_local_start FROM fitness.planned_workouts WHERE id=?",
                String.class, first.items().getFirst().occurrenceId())).isEqualTo(c.items().getFirst().localStart().toString());
        assertThatThrownBy(() -> batches.confirmDirect(command(f, "replay", item(f, startAtNextWeek().plusHours(2)))))
                .isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code").isEqualTo("WORKOUT_SCHEDULE_IDEMPOTENCY_CONFLICT");
    }

    @Test void coachRequiredDirectBatchAtomicallyLinksCurrentConfirmedAppointment() {
        UUID trainer = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status) VALUES (?,?,'hash','Trainer','ACTIVE')",
                trainer, trainer + "@example.com");
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='TRAINER'
                """, trainer, trainer);
        jdbc.update("""
                INSERT INTO fitness.trainer_profiles
                (user_id,verification_status,verified_at,is_accepting_students,is_active)
                VALUES (?,'VERIFIED',clock_timestamp(),true,true)
                """, trainer);
        final UUID[] period = new UUID[1];
        Fixture f = fixture("COACH_REQUIRED", false, student -> {
            jdbc.update("""
                    INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                    SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='STUDENT'
                    """, student, student);
            var request = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
            var active = lifecycle.relationshipAction(trainer, request.relationship().id(),
                    "ACCEPT", 0, null, UUID.randomUUID());
            period[0] = active.currentPeriod().id();
            return period[0];
        });
        LocalDateTime start = startAtNextWeek();
        var appointmentRequest = appointments.request(new AppointmentService.RequestCommand(
                f.student(), f.student(), null,
                new AppointmentService.LocalInterval(start.minusHours(1), start.plusHours(2),
                        "Etc/UTC", "+00:00", "+00:00"), UUID.randomUUID()));
        var appointment = appointments.decide(new AppointmentService.DecisionCommand(trainer,
                appointmentRequest.request().requestId(), "ACCEPT", 0, UUID.randomUUID())).appointment();
        Item ordinary = item(f, start);
        Item linked = new Item(ordinary.clientItemId(), ordinary.planSessionId(), ordinary.weekNumber(),
                ordinary.dayNumber(), ordinary.sequenceNumber(), ordinary.localStart(), ordinary.localEnd(),
                ordinary.startUtcOffset(), ordinary.endUtcOffset(), appointment.appointmentId());
        Item unknown = new Item(ordinary.clientItemId(), ordinary.planSessionId(), ordinary.weekNumber(),
                ordinary.dayNumber(), ordinary.sequenceNumber(), ordinary.localStart(), ordinary.localEnd(),
                ordinary.startUtcOffset(), ordinary.endUtcOffset(), UUID.randomUUID());
        assertThatThrownBy(() -> batches.confirmDirect(command(f, "coach-unknown", unknown)))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code")
                .isEqualTo("WORKOUT_SCHEDULE_APPOINTMENT_NOT_QUALIFYING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE student_id=?",
                Integer.class, f.student())).isZero();
        ConfirmBatch command = command(f, "coach-linked", linked);
        ConfirmedBatch confirmed = batches.confirmDirect(command);
        UUID occurrence = confirmed.items().getFirst().occurrenceId();
        assertThat(jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?",
                UUID.class, appointment.appointmentId())).isEqualTo(occurrence);
        assertThat(jdbc.queryForObject("SELECT coaching_period_id FROM fitness.planned_workouts WHERE id=?",
                UUID.class, occurrence)).isEqualTo(period[0]);
        assertThat(batches.confirmDirect(command).items().getFirst().occurrenceId()).isEqualTo(occurrence);
        assertThatThrownBy(() -> batches.confirmDirect(command(f, "coach-reuse", linked)))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code")
                .isEqualTo("WORKOUT_SCHEDULE_DUPLICATE");
    }

    @Test void coachRequiredBatchWinsAgainstAppointmentCancelWithoutOrphanLink() throws Exception {
        raceCoachRequiredBatchAndCancel(true);
    }

    @Test void appointmentCancelWinsAgainstCoachRequiredBatchWithoutPartialWrite() throws Exception {
        raceCoachRequiredBatchAndCancel(false);
    }

    private void raceCoachRequiredBatchAndCancel(boolean batchFirst) throws Exception {
        UUID trainer = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status) VALUES (?,?,'hash','Trainer','ACTIVE')",
                trainer, trainer + "@example.com");
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='TRAINER'
                """, trainer, trainer);
        jdbc.update("""
                INSERT INTO fitness.trainer_profiles
                (user_id,verification_status,verified_at,is_accepting_students,is_active)
                VALUES (?,'VERIFIED',clock_timestamp(),true,true)
                """, trainer);
        Fixture f = fixture("COACH_REQUIRED", false, student -> {
            jdbc.update("""
                    INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                    SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='STUDENT'
                    """, student, student);
            var request = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
            return lifecycle.relationshipAction(trainer, request.relationship().id(),
                    "ACCEPT", 0, null, UUID.randomUUID()).currentPeriod().id();
        });
        LocalDate week = anchor().plusWeeks(4);
        LocalDateTime start = week.atTime(10, 0);
        var request = appointments.request(new AppointmentService.RequestCommand(f.student(), f.student(), null,
                new AppointmentService.LocalInterval(start.minusHours(1), start.plusHours(2),
                        "Etc/UTC", "+00:00", "+00:00"), UUID.randomUUID()));
        var appointment = appointments.decide(new AppointmentService.DecisionCommand(trainer,
                request.request().requestId(), "ACCEPT", 0, UUID.randomUUID())).appointment();
        UUID appointmentId = appointment.appointmentId();
        UUID cancelKey = UUID.randomUUID();
        String batchKey = "coach-race-" + UUID.randomUUID();
        String batchBody = """
                {"planId":"%s","sourcePlanVersionId":"%s","expectedPlanAggregateVersion":1,
                 "weekAnchorDate":"%s","timezone":"Etc/UTC","commandKey":"%s",
                 "items":[{"clientItemId":"%s","planSessionId":"%s","weekNumber":1,
                           "dayNumber":1,"sequenceNumber":1,"localStart":"%s","localEnd":"%s",
                           "startUtcOffset":"+00:00","endUtcOffset":"+00:00","appointmentId":"%s"}]}
                """.formatted(f.plan(), f.version(), week, batchKey, UUID.randomUUID(), f.session(),
                start, start.plusHours(1), appointmentId);
        String cancelBody = """
                {"expectedAppointmentVersion":%d,"reason":"Không thể tham dự","commandKey":"%s"}
                """.formatted(appointment.version(), cancelKey);
        int initialEvents = count("SELECT count(*) FROM fitness.appointment_events_v2 WHERE appointment_id=?",
                appointmentId);

        try (Connection gate = dataSource.getConnection(); ExecutorService pool = Executors.newFixedThreadPool(2)) {
            gate.setAutoCommit(false);
            try (var lock = gate.prepareStatement("SELECT id FROM fitness.appointments WHERE id=? FOR UPDATE")) {
                lock.setObject(1, appointmentId);
                assertThat(lock.executeQuery().next()).isTrue();
            }
            CountDownLatch firstStarted = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
            Callable<HttpResult> batch = () -> {
                (batchFirst ? firstStarted : secondStarted).countDown();
                var response = mvc.perform(post("/api/v1/planned-workout-batches")
                                .contentType("application/json").content(batchBody)
                                .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                        .andReturn().getResponse();
                return new HttpResult(response.getStatus(), response.getContentAsString());
            };
            Callable<HttpResult> cancel = () -> {
                (batchFirst ? secondStarted : firstStarted).countDown();
                var response = mvc.perform(post("/api/v1/appointments/{appointmentId}/cancel", appointmentId)
                                .contentType("application/json").content(cancelBody)
                                .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                        .andReturn().getResponse();
                return new HttpResult(response.getStatus(), response.getContentAsString());
            };
            Future<HttpResult> first = pool.submit(batchFirst ? batch : cancel);
            try {
                assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
                awaitAppointmentLockWaiters(1);
                Future<HttpResult> second = pool.submit(batchFirst ? cancel : batch);
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                awaitAppointmentLockWaiters(2);
                assertThat(first.isDone()).isFalse();
                assertThat(second.isDone()).isFalse();
                gate.commit();
                HttpResult batchResult = (batchFirst ? first : second).get(30, TimeUnit.SECONDS);
                HttpResult cancelResult = (batchFirst ? second : first).get(30, TimeUnit.SECONDS);
                assertThat(batchResult.status()).isEqualTo(batchFirst ? 201 : 409);
                assertThat(cancelResult.status()).isEqualTo(batchFirst ? 409 : 200);
                assertThat(batchResult.body()).doesNotContain("SQLException", "deadlock detected");
                assertThat(cancelResult.body()).doesNotContain("SQLException", "deadlock detected");
                if (batchFirst) {
                    assertThat(cancelResult.body()).contains("APPOINTMENT_VERSION_STALE");
                } else {
                    assertThat(batchResult.body()).contains("WORKOUT_SCHEDULE_APPOINTMENT_NOT_QUALIFYING");
                }
            } finally {
                gate.rollback(); // Also releases the test-only row lock if an assertion fails.
            }
        }

        UUID linked = jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?",
                UUID.class, appointmentId);
        String status = jdbc.queryForObject("SELECT status::text FROM fitness.appointments WHERE id=?",
                String.class, appointmentId);
        assertThat(status).isEqualTo(batchFirst ? "CONFIRMED" : "CANCELLED");
        assertThat(count("SELECT count(*) FROM fitness.planned_workouts WHERE student_id=?", f.student()))
                .isEqualTo(batchFirst ? 1 : 0);
        assertThat(count("SELECT count(*) FROM fitness.workout_schedule_batches WHERE student_id=?", f.student()))
                .isEqualTo(batchFirst ? 1 : 0);
        assertThat(count("SELECT count(*) FROM fitness.workout_schedule_command_receipts WHERE actor_id=? AND command_key=?",
                f.student(), batchKey)).isEqualTo(batchFirst ? 1 : 0);
        assertThat(count("SELECT count(*) FROM fitness.appointment_command_receipts_v2 WHERE actor_id=? AND command_key=?",
                f.student(), cancelKey)).isEqualTo(batchFirst ? 0 : 1);
        assertThat(count("SELECT count(*) FROM fitness.appointment_events_v2 WHERE appointment_id=?", appointmentId))
                .isEqualTo(initialEvents + (batchFirst ? 0 : 1));
        assertThat(count("SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id=? AND action='WORKOUT_SCHEDULE_BATCH_CONFIRMED'",
                f.student())).isEqualTo(batchFirst ? 1 : 0);
        if (batchFirst) {
            assertThat(linked).isNotNull();
            assertThat(count("SELECT count(*) FROM fitness.planned_workouts WHERE id=? AND student_id=? AND status='SCHEDULED'",
                    linked, f.student())).isOne();
        } else {
            assertThat(linked).isNull();
            assertThat(count("SELECT count(*) FROM fitness.notifications WHERE event_type='APPOINTMENT_CANCELLED' AND related_entity_id=?",
                    appointmentId)).isOne();
        }
    }

    private void awaitAppointmentLockWaiters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (count("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname=current_database() AND wait_event_type='Lock'
                      AND query ILIKE '%fitness.appointments%'
                    """) >= expected) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("Expected " + expected + " Appointment row-lock waiters");
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private record HttpResult(int status, String body) {}

    @Test void preservesNanosecondLocalIntentWhileInstantUsesDatabaseMicroseconds() {
        Fixture f = fixture();
        LocalDateTime local = startAtNextWeek().plusNanos(123_456_789);
        ConfirmedBatch batch = batches.confirmDirect(command(f, "local-nanos", item(f, local)));
        UUID occurrence = batch.items().getFirst().occurrenceId();
        assertThat(jdbc.queryForObject("SELECT schedule_local_start FROM fitness.planned_workouts WHERE id=?",
                String.class, occurrence)).isEqualTo(local.toString());
        assertThat(batch.items().getFirst().localStart()).isEqualTo(local);
        assertThat(batch.items().getFirst().plannedStartAt().getNano() % 1_000).isZero();
    }

    @Test void rejectsUnknownLegacyEndWholeBatchButTerminalLegacyDoesNotBlock() {
        Fixture f = fixture();
        UUID legacy = legacy(f, null, "SCHEDULED");
        ConfirmBatch c = command(f, "unknown", item(f, startAtNextWeek()));
        assertThatThrownBy(() -> batches.confirmDirect(c)).isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code").isEqualTo("WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_batches WHERE student_id=?",
                Integer.class, f.student())).isZero();
        jdbc.update("UPDATE fitness.planned_workouts SET status='SKIPPED',version=version+1 WHERE id=?", legacy);
        assertThat(batches.confirmDirect(c).items()).hasSize(1);
    }

    @Test void startedScheduledUnknownEndStillBlocksAndOtherStudentsAreUnaffected() {
        Fixture f = fixture(), other = fixture();
        UUID legacy = legacy(f, null, "SCHEDULED");
        jdbc.update("UPDATE fitness.planned_workouts SET execution_started_at=clock_timestamp(),version=version+1 WHERE id=?",
                legacy);
        assertThatThrownBy(() -> batches.confirmDirect(command(f, "started-null", item(f, startAtNextWeek()))))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code")
                .isEqualTo("WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN");
        assertThat(batches.confirmDirect(command(other, "other-student", item(other, startAtNextWeek()))).items())
                .hasSize(1);
    }

    @Test void knownLegacyRangeBlocksOnlyActualOverlap() {
        Fixture f = fixture();
        LocalDateTime start = startAtNextWeek();
        Instant legacyEnd = start.plusMinutes(45).toInstant(ZoneOffset.UTC);
        UUID legacy = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,planned_start_at,planned_end_at,original_planned_start_at,created_by)
                VALUES (?,?,?,?,?,?,?)
                """, legacy, f.student(), f.session(), Timestamp.from(start.minusMinutes(15).toInstant(ZoneOffset.UTC)),
                Timestamp.from(legacyEnd), Timestamp.from(start.minusMinutes(15).toInstant(ZoneOffset.UTC)), f.student());
        assertThatThrownBy(() -> batches.confirmDirect(command(f, "legacy-overlap", item(f, start))))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code")
                .isEqualTo("WORKOUT_SCHEDULE_OVERLAP");
        assertThat(batches.confirmDirect(command(f, "after-legacy", item(f, start.plusHours(2)))).items())
                .hasSize(1);
    }

    @Test void databaseExclusionRejectsOverlappingNewRowsEvenOutsideService() {
        Fixture f = fixture();
        LocalDateTime start = startAtNextWeek();
        batches.confirmDirect(command(f, "db-guard", item(f, start)));
        UUID secondBatch = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_batches
                (id,student_id,plan_id,plan_version_id,plan_version_number,week_anchor_date,timezone,
                 confirmation_source,confirmed_by,confirmed_at)
                VALUES (?,?,?,?,1,?,'Etc/UTC','STUDENT_DIRECT',?,clock_timestamp())
                """, secondBatch, f.student(), f.plan(), f.version(), anchor(), f.student());
        LocalDateTime overlap = start.plusMinutes(30);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,planned_start_at,planned_end_at,original_planned_start_at,
                 created_by,schedule_batch_id,schedule_local_start,schedule_local_end,schedule_timezone,
                 schedule_start_utc_offset,schedule_end_utc_offset)
                VALUES (?,?,?,?,?,?,?,?,?,?,'Etc/UTC','+00:00','+00:00')
                """, UUID.randomUUID(), f.student(), f.session(), Timestamp.from(overlap.toInstant(ZoneOffset.UTC)),
                Timestamp.from(overlap.plusHours(1).toInstant(ZoneOffset.UTC)),
                Timestamp.from(overlap.toInstant(ZoneOffset.UTC)), f.student(), secondBatch, overlap,
                overlap.plusHours(1))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void rejectsCoachRequiredSessionAndWrongSourceVersion() {
        Fixture f = fixture("COACH_REQUIRED");
        assertThatThrownBy(() -> batches.confirmDirect(command(f, "coach-required", item(f, startAtNextWeek()))))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code")
                .isEqualTo("WORKOUT_SCHEDULE_COACH_REQUIRED_UNSUPPORTED");
        ConfirmBatch c = command(f, "wrong-version", item(f, startAtNextWeek()));
        ConfirmBatch wrong = new ConfirmBatch(c.actorId(), c.planId(), UUID.randomUUID(),
                c.expectedPlanAggregateVersion(), c.weekAnchorDate(), c.timezone(), c.items(), c.commandKey());
        assertThatThrownBy(() -> batches.confirmDirect(wrong)).isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code").isEqualTo("WORKOUT_SCHEDULE_PLAN_VERSION_STALE");
    }

    @Test void rejectsOverlapDuplicateAndInvalidItemWithoutPartialBatch() {
        Fixture f = fixture();
        LocalDateTime start = startAtNextWeek();
        batches.confirmDirect(command(f, "first", item(f, start)));
        assertThatThrownBy(() -> batches.confirmDirect(command(f, "duplicate", item(f, start))))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code")
                .isEqualTo("WORKOUT_SCHEDULE_DUPLICATE");
        assertThatThrownBy(() -> batches.confirmDirect(command(f, "overlap", item(f, start.plusMinutes(30)))))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code")
                .isEqualTo("WORKOUT_SCHEDULE_OVERLAP");
        Item invalid = new Item(UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1,
                start.plusHours(4), start.plusHours(5), "+00:00", "+00:00");
        ConfirmBatch partial = new ConfirmBatch(f.student(), f.plan(), f.version(), 1, anchor(), "Etc/UTC",
                List.of(item(f, start.plusHours(2)), invalid), "partial");
        assertThatThrownBy(() -> batches.confirmDirect(partial)).isInstanceOf(WorkoutScheduleFailure.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_batches WHERE student_id=?",
                Integer.class, f.student())).isOne();
    }

    @Test void rejectsStalePlanAndOtherStudent() {
        Fixture f = fixture();
        ConfirmBatch c = command(f, "stale", item(f, startAtNextWeek()));
        ConfirmBatch stale = new ConfirmBatch(c.actorId(), c.planId(), c.sourcePlanVersionId(), 99,
                c.weekAnchorDate(), c.timezone(), c.items(), c.commandKey());
        assertThatThrownBy(() -> batches.confirmDirect(stale)).isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code").isEqualTo("WORKOUT_SCHEDULE_PLAN_VERSION_STALE");
        ConfirmBatch other = new ConfirmBatch(UUID.randomUUID(), c.planId(), c.sourcePlanVersionId(), 1,
                c.weekAnchorDate(), c.timezone(), c.items(), "other");
        assertThatThrownBy(() -> batches.confirmDirect(other)).isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code").isEqualTo("WORKOUT_PLAN_NOT_FOUND");
    }

    @Test void missingAndOtherStudentsPlanReturnConcealedHttp404() throws Exception {
        Fixture actor = fixture();
        Fixture other = fixture();
        UUID missingPlan = UUID.randomUUID();
        for (UUID planId : List.of(missingPlan, other.plan())) {
            String request = """
                    {"planId":"%s","sourcePlanVersionId":"%s","expectedPlanAggregateVersion":1,
                     "weekAnchorDate":"%s","timezone":"Etc/UTC","commandKey":"missing-plan-%s",
                     "items":[{"clientItemId":"%s","planSessionId":"%s","weekNumber":1,
                               "dayNumber":1,"sequenceNumber":1,"localStart":"%s","localEnd":"%s",
                               "startUtcOffset":"+00:00","endUtcOffset":"+00:00"}]}
                    """.formatted(planId, other.version(), anchor(), planId, UUID.randomUUID(),
                    other.session(), startAtNextWeek(), startAtNextWeek().plusHours(1));
            String response = mvc.perform(post("/api/v1/planned-workout-batches")
                            .contentType("application/json").content(request)
                            .with(jwt().jwt(token -> token.subject(actor.student().toString()))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("WORKOUT_PLAN_NOT_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(planId.toString(), actor.student().toString(),
                    other.student().toString(), "SELECT", "SQLException", "fitness.workout_plans");
        }
    }

    @Test void twoDifferentKeysCannotCreateSameSourceInstant() throws Exception {
        Fixture f = fixture();
        LocalDateTime start = startAtNextWeek();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
            Callable<String> a = () -> attempt(f, "race-a", start, ready, go);
            Callable<String> b = () -> attempt(f, "race-b", start, ready, go);
            Future<String> first = pool.submit(a), second = pool.submit(b);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", "WORKOUT_SCHEDULE_DUPLICATE");
        }
    }

    @Test void studentCalendarReturnsB06IntentAndConcealsOtherStudents() throws Exception {
        Fixture owner = fixture("SELF_ALLOWED", true), other = fixture();
        var created = batches.confirmDirect(command(owner, "calendar-created", item(owner, startAtNextWeek())));
        batches.confirmDirect(command(other, "calendar-other", item(other, startAtNextWeek())));
        UUID occurrence = created.items().getFirst().occurrenceId();
        Instant from = startAtNextWeek().minusHours(1).toInstant(ZoneOffset.UTC);
        Instant until = from.plusSeconds(7200);
        mvc.perform(get("/api/v1/planned-workouts").param("from", from.toString())
                        .param("until", until.toString())
                        .with(jwt().jwt(token -> token.subject(owner.student().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].occurrenceId").value(occurrence.toString()))
                .andExpect(jsonPath("$.items[0].occurrenceVersion").value(0))
                .andExpect(jsonPath("$.items[0].timezone").value("Etc/UTC"))
                .andExpect(jsonPath("$.items[0].localStart").value(startAtNextWeek().toString()))
                .andExpect(jsonPath("$.items[0].planSummary.planId").value(owner.plan().toString()))
                .andExpect(jsonPath("$.items[0].sessionSummary.planSessionId").value(owner.session().toString()))
                .andExpect(jsonPath("$.items[0].sessionSummary.supervisionRequirement").value("SELF_PERFORMABLE"))
                .andExpect(jsonPath("$.items[0].availableActions[0]").value("START"))
                .andExpect(jsonPath("$.items[0].availableActions[1]").value("SKIP"));
        mvc.perform(get("/api/v1/planned-workouts").param("from", from.toString())
                        .param("until", until.toString())
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("WORKOUT_SCHEDULE_ACCESS_DENIED"));
        mvc.perform(get("/api/v1/planned-workouts").param("from", from.toString())
                        .param("until", until.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test void calendarHttpBindsOffsetInstantsAndReturnsOrderedRealVersionsAndLegacyNulls() throws Exception {
        Fixture f = fixture("SELF_ALLOWED", true);
        Instant start = startAtNextWeek().toInstant(ZoneOffset.UTC);
        var confirmed = batches.confirmDirect(command(f, "calendar-http-offset", item(f, startAtNextWeek())));
        UUID first = confirmed.items().getFirst().occurrenceId();
        UUID legacyA = legacyAt(f, start.plusSeconds(3600), "SELF_ALLOWED");
        UUID legacyB = legacyAt(f, start.plusSeconds(3600), "SELF_ALLOWED");
        jdbc.update("UPDATE fitness.planned_workouts SET version=version+1 WHERE id=?", legacyA);
        UUID earlierLegacy = legacyA.toString().compareTo(legacyB.toString()) < 0 ? legacyA : legacyB;
        UUID laterLegacy = earlierLegacy.equals(legacyA) ? legacyB : legacyA;
        int earlierVersion = earlierLegacy.equals(legacyA) ? 1 : 0;
        int laterVersion = laterLegacy.equals(legacyA) ? 1 : 0;
        String from = start.minusSeconds(1).atOffset(ZoneOffset.ofHours(7)).toString();
        String until = start.plusSeconds(7200).atOffset(ZoneOffset.ofHours(7)).toString();

        mvc.perform(get("/api/v1/planned-workouts").param("from", from).param("until", until)
                        .param("page", "0").param("size", "2")
                        .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].occurrenceId").value(first.toString()))
                .andExpect(jsonPath("$.items[0].occurrenceVersion").value(confirmed.items().getFirst().occurrenceVersion()))
                .andExpect(jsonPath("$.items[1].occurrenceId").value(earlierLegacy.toString()))
                .andExpect(jsonPath("$.items[1].occurrenceVersion").value(earlierVersion))
                .andExpect(jsonPath("$.items[1].plannedEndAt").value(nullValue()))
                .andExpect(jsonPath("$.items[1].timezone").value(nullValue()))
                .andExpect(jsonPath("$.items[1].localStart").value(nullValue()))
                .andExpect(jsonPath("$.items[1].localEnd").value(nullValue()))
                .andExpect(jsonPath("$.items[1].startUtcOffset").value(nullValue()))
                .andExpect(jsonPath("$.items[1].endUtcOffset").value(nullValue()));
        mvc.perform(get("/api/v1/planned-workouts").param("from", from).param("until", until)
                        .param("page", "1").param("size", "2")
                        .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].occurrenceId").value(laterLegacy.toString()))
                .andExpect(jsonPath("$.items[0].occurrenceVersion").value(laterVersion));

        Fixture orphanOwner = fixture();
        UUID orphan = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,planned_start_at,original_planned_start_at,created_by)
                VALUES (?,?,?,?,?)
                """, orphan, orphanOwner.student(), Timestamp.from(start), Timestamp.from(start),
                orphanOwner.student());
        mvc.perform(get("/api/v1/planned-workouts").param("from", from).param("until", until)
                        .with(jwt().jwt(token -> token.subject(orphanOwner.student().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].occurrenceId").value(orphan.toString()))
                .andExpect(jsonPath("$.items[0].planSummary").value(nullValue()))
                .andExpect(jsonPath("$.items[0].sessionSummary").value(nullValue()))
                .andExpect(jsonPath("$.items[0].availableActions[0]").value("CANCEL"));
    }

    @Test void calendarHttpRejectsInvalidQueryWithContractErrorEnvelope() throws Exception {
        Fixture f = fixture();
        Instant start = startAtNextWeek().toInstant(ZoneOffset.UTC);
        String from = start.toString(), until = start.plusSeconds(3600).toString();
        String[][] invalid = {
                {null, until, null, null}, {from, null, null, null},
                {"not-a-time", until, null, null}, {from, "not-a-time", null, null},
                {from, start.plus(93, java.time.temporal.ChronoUnit.DAYS).plusNanos(1).toString(), null, null},
                {from, until, "-1", null}, {from, until, "wrong", null},
                {from, until, null, "0"}, {from, until, null, "101"}, {from, until, null, "wrong"}
        };
        for (String[] query : invalid) {
            var request = get("/api/v1/planned-workouts")
                    .with(jwt().jwt(token -> token.subject(f.student().toString())));
            if (query[0] != null) request.param("from", query[0]);
            if (query[1] != null) request.param("until", query[1]);
            if (query[2] != null) request.param("page", query[2]);
            if (query[3] != null) request.param("size", query[3]);
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("WORKOUT_SCHEDULE_WINDOW_INVALID"))
                    .andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.timestamp").isString())
                    .andExpect(jsonPath("$.requestId").isString())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath("$.itemErrors").isEmpty());
        }
    }

    @Test void calendarHttpDeniesNonStudentCallerWithoutExposingAnotherStudentsOccurrence() throws Exception {
        Fixture owner = fixture();
        Fixture other = fixture();
        UUID occurrence = legacyAt(owner, startAtNextWeek().toInstant(ZoneOffset.UTC), "SELF_ALLOWED");
        UUID nonStudent = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status) VALUES (?,?,'hash','Trainer','ACTIVE')",
                nonStudent, nonStudent + "@example.com");
        jdbc.update("INSERT INTO fitness.trainer_profiles(user_id) VALUES (?)", nonStudent);
        Instant start = startAtNextWeek().toInstant(ZoneOffset.UTC);
        mvc.perform(get("/api/v1/planned-workouts").param("from", start.minusSeconds(1).toString())
                        .param("until", start.plusSeconds(1).toString())
                        .with(jwt().jwt(token -> token.subject(nonStudent.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("WORKOUT_SCHEDULE_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Workout schedule is not available"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString(occurrence.toString()))));
        mvc.perform(get("/api/v1/planned-workouts").param("from", start.minusSeconds(1).toString())
                        .param("until", start.plusSeconds(1).toString())
                        .with(jwt().jwt(token -> token.subject(other.student().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test void sourcePlanTransitionsDoNotRemoveActionsButExecutionSealDoes() {
        Instant start = startAtNextWeek().toInstant(ZoneOffset.UTC);
        for (String status : List.of("PAUSED", "COMPLETED", "ARCHIVED")) {
            Fixture f = fixture("SELF_ALLOWED", true);
            UUID occurrence = legacyAt(f, start, "SELF_ALLOWED");
            jdbc.update("UPDATE fitness.workout_plans SET status=?::fitness.plan_status,version=version+1 WHERE id=?",
                    status, f.plan());
            var open = calendar.listMine(f.student(), start.minusSeconds(1), start.plusSeconds(1), 0, 20);
            // B06 cancel remains available for an unsealed occurrence even after source-plan transition.
            assertThat(open.items().getFirst().availableActions()).containsExactly("START", "SKIP", "CANCEL");
            jdbc.update("UPDATE fitness.planned_workouts SET execution_started_at=clock_timestamp(),version=version+1 WHERE id=?",
                    occurrence);
            var sealed = calendar.listMine(f.student(), start.minusSeconds(1), start.plusSeconds(1), 0, 20);
            assertThat(sealed.items().getFirst().occurrenceVersion()).isEqualTo(1);
            assertThat(sealed.items().getFirst().availableActions()).isEmpty();
        }
    }

    @Test void legacyCoachRequiredWithoutCurrentQualifyingAppointmentCannotStartButCanSkip() {
        Fixture f = fixture("COACH_REQUIRED", true);
        Instant start = startAtNextWeek().toInstant(ZoneOffset.UTC);
        UUID occurrence = legacyAt(f, start, "COACH_REQUIRED");
        var without = calendar.listMine(f.student(), start.minusSeconds(1), start.plusSeconds(1), 0, 20);
        assertThat(without.items().getFirst().availableActions()).containsExactly("SKIP", "CANCEL");
        UUID trainer = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status) VALUES (?,?,'hash','Trainer','ACTIVE')",
                trainer, trainer + "@example.com");
        jdbc.update("INSERT INTO fitness.trainer_profiles(user_id) VALUES (?)", trainer);
        UUID appointment = UUID.randomUUID();
        LegacyAppointmentFixture.insert(jdbc, """
                INSERT INTO fitness.appointments
                (id,trainer_id,student_id,planned_workout_id,start_at,end_at,display_timezone,status,created_by)
                VALUES (?,?,?,?,?,?,'Etc/UTC','SCHEDULED',?)
                """, appointment, trainer, f.student(), occurrence, Timestamp.from(start),
                Timestamp.from(start.plusSeconds(3600)), f.student());
        assertThat(calendar.listMine(f.student(), start.minusSeconds(1), start.plusSeconds(1), 0, 20)
                .items().getFirst().availableActions()).containsExactly("SKIP", "CANCEL");
        jdbc.update("UPDATE fitness.appointments SET status='CONFIRMED' WHERE id=?", appointment);
        assertThat(calendar.listMine(f.student(), start.minusSeconds(1), start.plusSeconds(1), 0, 20)
                .items().getFirst().availableActions()).containsExactly("SKIP", "CANCEL");
        jdbc.update("UPDATE fitness.appointments SET status='COMPLETED' WHERE id=?", appointment);
        assertThat(calendar.listMine(f.student(), start.minusSeconds(1), start.plusSeconds(1), 0, 20)
                .items().getFirst().availableActions()).containsExactly("SKIP", "CANCEL");
        jdbc.update("UPDATE fitness.appointments SET status='CANCELLED' WHERE id=?", appointment);
        jdbc.update("UPDATE fitness.planned_workouts SET supervision_override_by=?,supervision_override_reason='Approved override',version=version+1 WHERE id=?",
                f.student(), occurrence);
        assertThat(calendar.listMine(f.student(), start.minusSeconds(1), start.plusSeconds(1), 0, 20)
                .items().getFirst().availableActions()).containsExactly("SKIP", "CANCEL");
    }

    @Test void anotherInProgressExecutionSuppressesStartButKeepsSkip() {
        Fixture f = fixture("SELF_ALLOWED", true);
        Instant start = startAtNextWeek().toInstant(ZoneOffset.UTC);
        UUID first = legacyAt(f, start, "SELF_ALLOWED");
        UUID second = legacyAt(f, start.plusSeconds(7200), "SELF_ALLOWED");
        executionCommands.start(new WorkoutExecutionCommandUseCase.StartCommand(first, f.student(), 0,
                "calendar-first-execution"));
        var page = calendar.listMine(f.student(), start, start.plusSeconds(10800), 0, 20);
        assertThat(page.items().stream().filter(item -> item.occurrenceId().equals(first)).findFirst()
                .orElseThrow().availableActions()).isEmpty();
        assertThat(page.items().stream().filter(item -> item.occurrenceId().equals(second)).findFirst()
                .orElseThrow().availableActions()).containsExactly("SKIP", "CANCEL");
    }

    @Test void legacyNullIntentStableSortPaginationAndNinetyThreeDayBoundary() {
        Fixture f = fixture();
        Instant start = startAtNextWeek().toInstant(ZoneOffset.UTC);
        UUID a = legacyAt(f, start, "SELF_ALLOWED");
        UUID b = legacyAt(f, start, "SELF_ALLOWED");
        legacyAt(f, start.plusSeconds(3600), "SELF_ALLOWED");
        var first = calendar.listMine(f.student(), start, start.plusSeconds(3600), 0, 1);
        var second = calendar.listMine(f.student(), start, start.plusSeconds(3600), 1, 1);
        assertThat(first.totalItems()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items().getFirst().occurrenceId()).isEqualTo(a.toString().compareTo(b.toString()) < 0 ? a : b);
        assertThat(second.items().getFirst().occurrenceId()).isEqualTo(a.toString().compareTo(b.toString()) < 0 ? b : a);
        assertThat(first.items().getFirst().timezone()).isNull();
        assertThat(first.items().getFirst().localStart()).isNull();
        assertThat(first.items().getFirst().plannedEndAt()).isNull();
        assertThat(calendar.listMine(f.student(), start, start.plus(93, java.time.temporal.ChronoUnit.DAYS), 0, 20)
                .totalItems()).isEqualTo(3);
        assertThatThrownBy(() -> calendar.listMine(f.student(), start,
                start.plus(93, java.time.temporal.ChronoUnit.DAYS).plusNanos(1), 0, 20))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code")
                .isEqualTo("WORKOUT_SCHEDULE_WINDOW_INVALID");

        Fixture orphanOwner = fixture();
        UUID orphan = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,planned_start_at,original_planned_start_at,created_by)
                VALUES (?,?,?,?,?)
                """, orphan, orphanOwner.student(), Timestamp.from(start), Timestamp.from(start),
                orphanOwner.student());
        var legacy = calendar.listMine(orphanOwner.student(), start, start.plusSeconds(1), 0, 20).items().getFirst();
        assertThat(legacy.planSummary()).isNull();
        assertThat(legacy.sessionSummary()).isNull();
        // Missing source/local intent blocks reschedule, but Student may cancel an unsealed legacy row.
        assertThat(legacy.availableActions()).containsExactly("CANCEL");

        Fixture optional = fixture("COACH_PREFERRED");
        legacyAt(optional, start, "COACH_PREFERRED");
        assertThat(calendar.listMine(optional.student(), start, start.plusSeconds(1), 0, 20)
                .items().getFirst().sessionSummary().supervisionRequirement()).isEqualTo("COACH_OPTIONAL");
    }

    @Test void validatesDstOffsetAndNinetyDayWindow() {
        Fixture f = fixture();
        LocalDateTime gap = LocalDateTime.of(2027, 3, 14, 2, 30);
        Item gapItem = new Item(UUID.randomUUID(), f.session(), 1, 1, 1, gap, gap.plusHours(1), "-05:00", "-04:00");
        assertThatThrownBy(() -> batches.confirmDirect(new ConfirmBatch(f.student(), f.plan(), f.version(), 1,
                LocalDate.of(2027, 3, 8), "America/New_York", List.of(gapItem), "gap")))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code").isEqualTo("VALIDATION_FAILED");
        LocalDateTime far = anchor().plusWeeks(15).atTime(10, 0);
        assertThatThrownBy(() -> batches.confirmDirect(new ConfirmBatch(f.student(), f.plan(), f.version(), 1,
                anchor().plusWeeks(15), "Etc/UTC", List.of(item(f, far)), "far")))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code").isEqualTo("VALIDATION_FAILED");
    }

    private String attempt(Fixture f, String key, LocalDateTime start, CountDownLatch ready, CountDownLatch go)
            throws InterruptedException {
        ready.countDown(); go.await();
        try { batches.confirmDirect(command(f, key, item(f, start))); return "CREATED"; }
        catch (WorkoutScheduleFailure failure) { return failure.code(); }
    }

    private Fixture fixture() { return fixture("SELF_ALLOWED", false); }
    private Fixture fixture(String supervision) { return fixture(supervision, false); }
    private Fixture fixture(String supervision, boolean withPrescription) {
        return fixture(supervision, withPrescription, student -> null);
    }
    private Fixture fixture(String supervision, boolean withPrescription, Function<UUID, UUID> periodFactory) {
        UUID student = UUID.randomUUID(), plan = UUID.randomUUID(), version = UUID.randomUUID(), session = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?, 'hash','Student','ACTIVE','vi-VN','Etc/UTC')",
                student, student + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
        UUID period = periodFactory.apply(student);
        jdbc.update("""
                INSERT INTO fitness.workout_plans(id,student_id,name,source,status,created_by,decision_owner_type,decision_owner_id,coaching_period_id)
                VALUES (?,?,'Plan','STUDENT','DRAFT',?,'STUDENT',?,?)
                """, plan, student, student, student, period);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions
                (id,workout_plan_id,version_number,change_level,created_by)
                VALUES (?,?,1,'INITIAL',?)
                """, version, plan, student);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id,workout_plan_version_id,week_number,day_number,sequence_number,name,supervision_requirement)
                VALUES (?,?,1,1,1,'Session',?::fitness.supervision_requirement)
                """, session, version, supervision);
        if (withPrescription) prescription(new Fixture(student, plan, version, session));
        jdbc.update("""
                UPDATE fitness.workout_plan_versions
                SET effective_from=clock_timestamp(),locked_at=clock_timestamp(),locked_by=?,lock_reason='ACTIVATED'
                WHERE id=?
                """, student, version);
        jdbc.update("""
                UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1,
                    assigned_by=?,assigned_at=clock_timestamp() WHERE id=?
                """, student, plan);
        return new Fixture(student, plan, version, session);
    }
    private UUID legacy(Fixture f, Instant end, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,planned_start_at,planned_end_at,original_planned_start_at,status,created_by)
                VALUES (?,?,?,clock_timestamp()+interval '2 days',?,clock_timestamp()+interval '2 days',?::fitness.planned_workout_status,?)
                """, id, f.student(), f.session(), end == null ? null : Timestamp.from(end), status, f.student());
        return id;
    }
    private UUID legacyAt(Fixture f, Instant at, String supervision) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,planned_start_at,original_planned_start_at,
                 supervision_requirement,created_by)
                VALUES (?,?,?,?,?,?::fitness.supervision_requirement,?)
                """, id, f.student(), f.session(), Timestamp.from(at), Timestamp.from(at),
                supervision, f.student());
        return id;
    }
    private void prescription(Fixture f) {
        UUID exercise = UUID.randomUUID(), variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                exercise, "calendar-exercise-" + exercise, "Exercise", f.student());
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                variation, exercise, "calendar-variation-" + variation, "Variation");
        jdbc.update("""
                INSERT INTO fitness.workout_plan_session_exercises
                (id,workout_plan_session_id,exercise_variation_id,sequence_number,target_sets)
                VALUES (?,?,?,1,1)
                """, UUID.randomUUID(), f.session(), variation);
    }
    private static ConfirmBatch command(Fixture f, String key, Item item) {
        return new ConfirmBatch(f.student(), f.plan(), f.version(), 1, anchor(), "Etc/UTC", List.of(item), key);
    }
    private static Item item(Fixture f, LocalDateTime start) {
        return new Item(UUID.randomUUID(), f.session(), 1, 1, 1, start, start.plusHours(1), "+00:00", "+00:00");
    }
    private static LocalDate anchor() {
        return LocalDate.now(ZoneOffset.UTC).plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
    private static LocalDateTime startAtNextWeek() { return anchor().atTime(10, 0); }
    private record Fixture(UUID student, UUID plan, UUID version, UUID session) {}
}
