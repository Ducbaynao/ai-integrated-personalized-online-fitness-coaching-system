package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutRecurrenceUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutRecurrenceUseCase.Create;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutRecurrenceUseCase.Item;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutRecurrenceUseCase.VersionCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
class WorkoutRecurrenceHttpIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("workout_recurrence_b06_disposable")
            .withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired WorkoutRecurrenceUseCase recurrence;
    @Autowired WorkoutScheduleBatchUseCase batches;
    @Autowired WorkoutScheduleChangeUseCase changes;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired CoachingSharingUseCase sharing;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void studentHttpCreatesVersionBoundSeriesAndJobIsIdempotentAcrossConcurrentRuns() throws Exception {
        Fixture f = fixture();
        String key = "series-" + UUID.randomUUID();
        String body = """
                {"planId":"%s","sourcePlanVersionId":"%s","expectedPlanAggregateVersion":1,
                 "mondayAnchorDate":"%s","timezone":"Etc/UTC","commandKey":"%s",
                 "items":[{"planSessionId":"%s","weekNumber":1,"dayNumber":1,"sequenceNumber":1,
                           "firstLocalStart":"%s","firstLocalEnd":"%s"}]}
                """.formatted(f.plan(), f.version(), anchor(), key, f.session(),
                anchor().atTime(10, 0), anchor().atTime(11, 0));
        var response = mvc.perform(post("/api/v1/planned-workout-series")
                        .contentType("application/json").content(body)
                        .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.planWeeks").value(3))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(response.split("\\\"seriesId\\\":\\\"")[1].split("\\\"")[0]);
        mvc.perform(post("/api/v1/planned-workout-series")
                        .contentType("application/json").content(body)
                        .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seriesId").value(id.toString()))
                .andExpect(jsonPath("$.replayed").value(true));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> recurrence.materialize(id));
            var two = pool.submit(() -> recurrence.materialize(id));
            one.get(20, TimeUnit.SECONDS);
            two.get(20, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods "
                + "WHERE series_id=? AND outcome='CREATED'", Integer.class, id)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts "
                + "WHERE series_period_id IN (SELECT id FROM fitness.workout_recurrence_periods WHERE series_id=?)",
                Integer.class, id)).isOne();
        mvc.perform(get("/api/v1/planned-workout-series/{id}/periods", id)
                        .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outcome").value("CREATED"));
        var stopped = recurrence.stop(new VersionCommand(f.student(), id, 0, "stop-" + UUID.randomUUID()));
        assertThat(stopped.status()).isEqualTo("STOPPED");
        recurrence.materialize(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods WHERE series_id=?",
                Integer.class, id)).isOne();
    }

    @Test void databaseRejectsSelectedSessionFromAnotherSourceVersion() {
        Fixture owner = fixture(), other = fixture();
        var series = recurrence.confirmStudent(new Create(owner.student(), owner.student(), owner.plan(),
                owner.version(), 1, anchor(), "Etc/UTC", List.of(new Item(owner.session(), 1, 1, 1,
                anchor().atTime(10, 0), anchor().atTime(11, 0))), "fk-" + UUID.randomUUID()));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO fitness.workout_recurrence_series_items
                (series_id,source_plan_version_id,plan_session_id,week_number,day_number,sequence_number,
                 first_local_start,first_local_end) VALUES (?,?,?,1,1,2,?,?)
                """, series.seriesId(), owner.version(), other.session(),
                anchor().atTime(12, 0).toString(), anchor().atTime(13, 0).toString()))
                .hasMessageContaining("fk_workout_recurrence_item_session_version_v46");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO fitness.workout_recurrence_series_items
                (series_id,source_plan_version_id,plan_session_id,week_number,day_number,sequence_number,
                 first_local_start,first_local_end) VALUES (?,?,?,1,1,2,?,?)
                """, series.seriesId(), other.version(), other.session(),
                anchor().atTime(12, 0).toString(), anchor().atTime(13, 0).toString()))
                .hasMessageContaining("fk_workout_recurrence_item_series_version_v46");
    }

    @Test void jobAndStudentDirectBatchSerializeInBothOrders() throws Exception {
        for (boolean jobFirst : List.of(true, false)) {
            Fixture f = fixture();
            UUID seriesId = createSeries(f);
            String key = "race-batch-" + UUID.randomUUID();
            Supplier<String> job = () -> { recurrence.materialize(seriesId); return "JOB_DONE"; };
            Supplier<String> batch = () -> {
                batches.confirmDirect(batch(f, key, anchor().atTime(10, 0)));
                return "BATCH_CREATED";
            };
            Race result = jobFirst ? race(job, batch) : race(batch, job);
            assertThat(result.first()).isEqualTo(jobFirst ? "JOB_DONE" : "BATCH_CREATED");
            assertThat(result.second()).isEqualTo(jobFirst ? "WORKOUT_SCHEDULE_DUPLICATE" : "JOB_DONE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE student_id=? "
                    + "AND workout_plan_session_id=? AND planned_start_at=?", Integer.class,
                    f.student(), f.session(), java.sql.Timestamp.from(
                            anchor().atTime(10, 0).toInstant(ZoneOffset.UTC)))).isOne();
            assertThat(jdbc.queryForObject("SELECT outcome FROM fitness.workout_recurrence_periods "
                    + "WHERE series_id=?", String.class, seriesId))
                    .isEqualTo(jobFirst ? "CREATED" : "SKIPPED_DUPLICATE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_command_receipts "
                    + "WHERE actor_id=? AND command_key=?", Integer.class, f.student(), key))
                    .isEqualTo(jobFirst ? 0 : 1);
            if (jobFirst) {
                mvc.perform(post("/api/v1/planned-workout-batches")
                                .contentType("application/json")
                                .content("""
                                        {"planId":"%s","sourcePlanVersionId":"%s",
                                         "expectedPlanAggregateVersion":1,"weekAnchorDate":"%s",
                                         "timezone":"Etc/UTC","commandKey":"%s",
                                         "items":[{"clientItemId":"%s","planSessionId":"%s",
                                         "weekNumber":1,"dayNumber":1,"sequenceNumber":1,
                                         "localStart":"%s","localEnd":"%s",
                                         "startUtcOffset":"+00:00","endUtcOffset":"+00:00"}]}
                                        """.formatted(f.plan(), f.version(), anchor(), key, UUID.randomUUID(),
                                        f.session(), anchor().atTime(10, 0), anchor().atTime(11, 0)))
                                .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.errorCode").value("WORKOUT_SCHEDULE_DUPLICATE"));
            }
            recurrence.materialize(seriesId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods "
                    + "WHERE series_id=?", Integer.class, seriesId)).isOne();
            if (jobFirst) {
                UUID generated = jdbc.queryForObject("SELECT occurrence_id FROM fitness.workout_recurrence_periods "
                        + "WHERE series_id=?", UUID.class, seriesId);
                changes.cancel(new WorkoutScheduleChangeUseCase.Cancel(generated, f.student(), 0,
                        "Student cancelled", "cancel-series-" + UUID.randomUUID()));
                recurrence.materialize(seriesId);
                assertThat(jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?",
                        String.class, generated)).isEqualTo("CANCELLED");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods "
                        + "WHERE series_id=?", Integer.class, seriesId)).isOne();
            }
        }
    }

    @Test void jobAndStudentDirectRescheduleSerializeInBothOrders() throws Exception {
        for (boolean jobFirst : List.of(true, false)) {
            Fixture f = fixture();
            UUID old = batches.confirmDirect(batch(f, "old-" + UUID.randomUUID(),
                    anchor().atTime(9, 0))).items().getFirst().occurrenceId();
            UUID seriesId = createSeries(f);
            String key = "race-change-" + UUID.randomUUID();
            Supplier<String> job = () -> { recurrence.materialize(seriesId); return "JOB_DONE"; };
            Supplier<String> change = () -> {
                changes.reschedule(new WorkoutScheduleChangeUseCase.Reschedule(old, f.student(), 0,
                        new WorkoutScheduleChangeUseCase.Destination(anchor().atTime(10, 0),
                                anchor().atTime(11, 0), "Etc/UTC", "+00:00", "+00:00"), null, key));
                return "CHANGED";
            };
            Race result = jobFirst ? race(job, change) : race(change, job);
            assertThat(result.first()).isEqualTo(jobFirst ? "JOB_DONE" : "CHANGED");
            assertThat(result.second()).isEqualTo(jobFirst ? "WORKOUT_SCHEDULE_DUPLICATE" : "JOB_DONE");
            assertThat(jdbc.queryForObject("SELECT outcome FROM fitness.workout_recurrence_periods "
                    + "WHERE series_id=?", String.class, seriesId))
                    .isEqualTo(jobFirst ? "CREATED" : "SKIPPED_DUPLICATE");
            assertThat(jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?",
                    String.class, old)).isEqualTo(jobFirst ? "SCHEDULED" : "RESCHEDULED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_change_receipts "
                    + "WHERE actor_id=? AND command_key=?", Integer.class, f.student(), key))
                    .isEqualTo(jobFirst ? 0 : 1);
            if (jobFirst) {
                mvc.perform(post("/api/v1/planned-workouts/{id}/reschedule", old)
                                .contentType("application/json")
                                .content("""
                                        {"expectedOccurrenceVersion":0,"commandKey":"%s",
                                         "destination":{"localStart":"%s","localEnd":"%s",
                                         "timezone":"Etc/UTC","startUtcOffset":"+00:00",
                                         "endUtcOffset":"+00:00"}}
                                        """.formatted(key, anchor().atTime(10, 0), anchor().atTime(11, 0)))
                                .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.errorCode").value("WORKOUT_SCHEDULE_DUPLICATE"));
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE student_id=? "
                    + "AND workout_plan_session_id=?", Integer.class, f.student(), f.session())).isEqualTo(2);
            recurrence.materialize(seriesId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods "
                    + "WHERE series_id=?", Integer.class, seriesId)).isOne();
        }
    }

    private UUID createSeries(Fixture f) {
        return recurrence.confirmStudent(new Create(f.student(), f.student(), f.plan(), f.version(), 1,
                anchor(), "Etc/UTC", List.of(new Item(f.session(), 1, 1, 1,
                anchor().atTime(10, 0), anchor().atTime(11, 0))), "race-series-" + UUID.randomUUID()))
                .seriesId();
    }

    private WorkoutScheduleBatchUseCase.ConfirmBatch batch(Fixture f, String key, LocalDateTime start) {
        return new WorkoutScheduleBatchUseCase.ConfirmBatch(f.student(), f.plan(), f.version(), 1,
                anchor(), "Etc/UTC", List.of(new WorkoutScheduleBatchUseCase.Item(UUID.randomUUID(),
                f.session(), 1, 1, 1, start, start.plusHours(1), "+00:00", "+00:00")), key);
    }

    private Race race(Supplier<String> first, Supplier<String> second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger(), secondPid = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstFuture = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                String result = first.get();
                firstDone.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted); }
                return result;
            }));
            assertThat(firstDone.await(15, TimeUnit.SECONDS)).isTrue();
            var secondFuture = pool.submit(() -> {
                try {
                    return new TransactionTemplate(transactionManager).execute(status -> {
                        secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        secondStarted.countDown();
                        return second.get();
                    });
                } catch (WorkoutScheduleFailure failure) { return failure.code(); }
            });
            try {
                assertThat(secondStarted.await(15, TimeUnit.SECONDS)).isTrue();
                awaitBlockedBy(secondPid.get(), firstPid.get());
            } finally { release.countDown(); }
            return new Race(firstFuture.get(20, TimeUnit.SECONDS), secondFuture.get(20, TimeUnit.SECONDS));
        }
    }

    private void awaitBlockedBy(int waitingPid, int blockerPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity a WHERE a.pid=?
                    AND a.wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(a.pid)))
                    """, Boolean.class, waitingPid, blockerPid);
            if (Boolean.TRUE.equals(blocked)) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("Expected PostgreSQL lock wait: " + waitingPid + " behind " + blockerPid);
    }

    private record Race(String first, String second) {}

    @Test void unknownLegacyEndSkipsPeriodWithoutInventingEndOrCreatingOccurrence() {
        Fixture f = fixture();
        UUID legacy = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,planned_start_at,original_planned_start_at,created_by)
                VALUES (?,?,clock_timestamp()+interval '2 days',clock_timestamp()+interval '2 days',?)
                """, legacy, f.student(), f.student());
        var series = recurrence.confirmStudent(new Create(f.student(), f.student(), f.plan(), f.version(), 1,
                anchor(), "Etc/UTC", List.of(new Item(f.session(), 1, 1, 1,
                anchor().atTime(10, 0), anchor().atTime(11, 0))), "unknown-" + UUID.randomUUID()));
        recurrence.materialize(series.seriesId());
        assertThat(jdbc.queryForObject("SELECT outcome FROM fitness.workout_recurrence_periods "
                + "WHERE series_id=?", String.class, series.seriesId())).isEqualTo("SKIPPED_UNKNOWN_END");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts "
                + "WHERE series_period_id IS NOT NULL AND student_id=?", Integer.class, f.student())).isZero();
        assertThat(jdbc.queryForObject("SELECT planned_end_at IS NULL FROM fitness.planned_workouts WHERE id=?",
                Boolean.class, legacy)).isTrue();
    }

    @Test void finiteLegacyOverlapSkipsPeriodOutsideV46PartialExclusion() {
        Fixture f = fixture();
        UUID legacy = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,planned_start_at,planned_end_at,original_planned_start_at,created_by)
                VALUES (?,?,?,?,?,?)
                """, legacy, f.student(),
                java.sql.Timestamp.from(anchor().atTime(10, 30).toInstant(ZoneOffset.UTC)),
                java.sql.Timestamp.from(anchor().atTime(11, 30).toInstant(ZoneOffset.UTC)),
                java.sql.Timestamp.from(anchor().atTime(10, 30).toInstant(ZoneOffset.UTC)), f.student());
        var series = recurrence.confirmStudent(new Create(f.student(), f.student(), f.plan(), f.version(), 1,
                anchor(), "Etc/UTC", List.of(new Item(f.session(), 1, 1, 1,
                anchor().atTime(10, 0), anchor().atTime(11, 0))), "legacy-overlap-" + UUID.randomUUID()));

        recurrence.materialize(series.seriesId());

        assertThat(jdbc.queryForObject("SELECT outcome FROM fitness.workout_recurrence_periods "
                + "WHERE series_id=?", String.class, series.seriesId())).isEqualTo("SKIPPED_OVERLAP");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts "
                + "WHERE series_period_id IS NOT NULL AND student_id=?", Integer.class, f.student())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?",
                String.class, legacy)).isEqualTo("SCHEDULED");
        recurrence.materialize(series.seriesId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods "
                + "WHERE series_id=?", Integer.class, series.seriesId())).isOne();
    }

    @Test void pausedPlanDoesNotGenerateAndTerminalOccurrenceIsNeverRecreated() {
        Fixture f = fixture();
        var series = recurrence.confirmStudent(new Create(f.student(), f.student(), f.plan(), f.version(), 1,
                anchor(), "Etc/UTC", List.of(new Item(f.session(), 1, 1, 1,
                anchor().atTime(10, 0), anchor().atTime(11, 0))), "pause-" + UUID.randomUUID()));
        jdbc.update("UPDATE fitness.workout_plans SET status='PAUSED',version=version+1 WHERE id=?", f.plan());
        recurrence.materialize(series.seriesId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods WHERE series_id=?",
                Integer.class, series.seriesId())).isZero();
        jdbc.update("UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1 WHERE id=?", f.plan());
        recurrence.materialize(series.seriesId());
        UUID occurrence = jdbc.queryForObject("SELECT occurrence_id FROM fitness.workout_recurrence_periods "
                + "WHERE series_id=?", UUID.class, series.seriesId());
        assertThat(occurrence).isNotNull();
        jdbc.update("UPDATE fitness.planned_workouts SET status='CANCELLED',version=version+1 WHERE id=?", occurrence);
        recurrence.materialize(series.seriesId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods WHERE series_id=?",
                Integer.class, series.seriesId())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE series_period_id IS NOT NULL "
                + "AND student_id=?", Integer.class, f.student())).isOne();
    }

    @Test void unrelatedStudentCannotReadSeriesOrPeriodCounts() throws Exception {
        Fixture f = fixture();
        UUID stranger = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) "
                + "VALUES (?,?,'hash','Other','ACTIVE','vi-VN','Etc/UTC')", stranger, stranger + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", stranger);
        var series = recurrence.confirmStudent(new Create(f.student(), f.student(), f.plan(), f.version(), 1,
                anchor(), "Etc/UTC", List.of(new Item(f.session(), 1, 1, 1,
                anchor().atTime(10, 0), anchor().atTime(11, 0))), "owner-" + UUID.randomUUID()));
        mvc.perform(get("/api/v1/planned-workout-series/{id}", series.seriesId())
                        .with(jwt().jwt(token -> token.subject(stranger.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/planned-workout-series/{id}/periods", series.seriesId())
                        .with(jwt().jwt(token -> token.subject(stranger.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/planned-workout-series")
                        .with(jwt().jwt(token -> token.subject(stranger.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test void publishedSourceChangePausesSeriesWithoutRemappingItsSession() {
        Fixture f = fixture();
        var series = recurrence.confirmStudent(new Create(f.student(), f.student(), f.plan(), f.version(), 1,
                anchor(), "Etc/UTC", List.of(new Item(f.session(), 1, 1, 1,
                anchor().atTime(10, 0), anchor().atTime(11, 0))), "stale-" + UUID.randomUUID()));
        jdbc.update("UPDATE fitness.workout_plan_versions SET effective_until=clock_timestamp() "
                + "WHERE id=?", f.version());
        UUID replacementVersion = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.workout_plan_versions "
                + "(id,workout_plan_id,version_number,change_level,created_by,effective_from,locked_at,locked_by,lock_reason) "
                + "VALUES (?,?,2,'MAJOR',?,clock_timestamp(),clock_timestamp(),?,'ACTIVATED')",
                replacementVersion, f.plan(), f.student(), f.student());
        jdbc.update("UPDATE fitness.workout_plans SET version=version+1 WHERE id=?", f.plan());
        recurrence.materialize(series.seriesId());
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.workout_recurrence_series WHERE id=?",
                String.class, series.seriesId())).isEqualTo("PAUSED_SOURCE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods WHERE series_id=?",
                Integer.class, series.seriesId())).isZero();
    }

    @Test void aFutureBlockOutsideThirtyDayWindowIsNotMaterialized() {
        Fixture f = fixture();
        LocalDate farMonday = anchor().plusWeeks(5);
        var series = recurrence.confirmStudent(new Create(f.student(), f.student(), f.plan(), f.version(), 1,
                farMonday, "Etc/UTC", List.of(new Item(f.session(), 1, 1, 1,
                farMonday.atTime(10, 0), farMonday.atTime(11, 0))), "horizon-" + UUID.randomUUID()));
        recurrence.materialize(series.seriesId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods WHERE series_id=?",
                Integer.class, series.seriesId())).isZero();
    }

    @Test void trainerProposalNeedsLiveContributeAndStudentCanRejectAfterRevocation() throws Exception {
        Fixture f = fixture();
        UUID trainer = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) "
                + "VALUES (?,?,'hash','Trainer','ACTIVE','vi-VN','Etc/UTC')", trainer, trainer + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) "
                + "SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='TRAINER'", trainer, trainer);
        jdbc.update("INSERT INTO fitness.trainer_profiles "
                + "(user_id,verification_status,verified_at,is_accepting_students,is_active) "
                + "VALUES (?,'VERIFIED',clock_timestamp(),true,true)", trainer);
        var pending = lifecycle.initiate(f.student(), f.student(), trainer, UUID.randomUUID());
        var active = lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT", 0,
                null, UUID.randomUUID());
        var grant = sharing.grantOrReplace(f.student(), active.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.CONTRIBUTE, null, null, null, null, UUID.randomUUID());
        var proposed = recurrence.proposeTrainer(new Create(trainer, f.student(), f.plan(), f.version(), 1,
                anchor(), "Etc/UTC", List.of(new Item(f.session(), 1, 1, 1,
                anchor().atTime(10, 0), anchor().atTime(11, 0))), "proposal-" + UUID.randomUUID()));
        assertThat(proposed.status()).isEqualTo("PENDING");
        sharing.revoke(f.student(), active.relationship().id(), grant.id(), grant.version(),
                "access removed", UUID.randomUUID());
        mvc.perform(get("/api/v1/students/{studentId}/planned-workout-series", f.student())
                        .with(jwt().jwt(token -> token.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/planned-workout-series/{id}", proposed.seriesId())
                        .with(jwt().jwt(token -> token.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        var rejected = recurrence.decide(new WorkoutRecurrenceUseCase.Decision(f.student(),
                proposed.seriesId(), 0, "REJECT", "reject-" + UUID.randomUUID()));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods WHERE series_id=?",
                Integer.class, proposed.seriesId())).isZero();
    }

    private Fixture fixture() {
        UUID student = UUID.randomUUID(), plan = UUID.randomUUID(), version = UUID.randomUUID();
        UUID session = UUID.randomUUID(), unselectedWeekThree = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) "
                + "VALUES (?,?,'hash','Student','ACTIVE','vi-VN','Etc/UTC')",
                student, student + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) "
                + "SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='STUDENT'", student, student);
        jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,name,source,status,created_by,decision_owner_type,decision_owner_id)
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
                VALUES (?,?,1,1,1,'Selected','SELF_ALLOWED')
                """, session, version);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id,workout_plan_version_id,week_number,day_number,sequence_number,name,supervision_requirement)
                VALUES (?,?,3,1,1,'Unselected','SELF_ALLOWED')
                """, unselectedWeekThree, version);
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

    private static LocalDate anchor() {
        // Keep the first period inside, and the next three-week cycle outside, the 30-day job window.
        return LocalDate.now(ZoneOffset.UTC).plusWeeks(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
    private record Fixture(UUID student, UUID plan, UUID version, UUID session) {}
}
