package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.*;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import com.fitnesscoaching.platform.modules.audit.AuditService;
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

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
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

    private Fixture fixture() { return fixture("SELF_ALLOWED"); }
    private Fixture fixture(String supervision) {
        UUID student = UUID.randomUUID(), plan = UUID.randomUUID(), version = UUID.randomUUID(), session = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?, 'hash','Student','ACTIVE','vi-VN','Etc/UTC')",
                student, student + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
        jdbc.update("""
                INSERT INTO fitness.workout_plans(id,student_id,name,source,status,created_by,decision_owner_type,decision_owner_id)
                VALUES (?,?,'Plan','STUDENT','DRAFT',?,'STUDENT',?)
                """, plan, student, student, student);
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
