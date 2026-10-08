package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase.StartCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase.TerminalCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase.UpsertSetCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.AdjustOccurrenceCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort;
import com.fitnesscoaching.platform.modules.workout.domain.SetExecution;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecution;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionAdjustment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WorkoutExecutionConcurrencyIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("workout_execution_concurrency").withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired WorkoutExecutionCommandUseCase executions;
    @Autowired WorkoutPlanCommandUseCase plans;
    @Autowired WorkoutPlanPersistencePort planStore;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void doubleStartSameOccurrenceCreatesExactlyOneExecutionAndSnapshot() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch firstEffectReady = new CountDownLatch(1);
        CountDownLatch releaseFirstCommit = new CountDownLatch(1);
        CountDownLatch secondInvoked = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<WorkoutExecution> first = pool.submit(() -> heldTransaction(
                    () -> executions.start(new StartCommand(fixture.occurrenceId(), fixture.studentId(), 0,
                            "concurrent-start-a-" + fixture.occurrenceId())), firstEffectReady, releaseFirstCommit));
            assertThat(firstEffectReady.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> second = pool.submit(() -> transactionOutcome(secondInvoked, () ->
                    executions.start(new StartCommand(fixture.occurrenceId(), fixture.studentId(), 0,
                            "concurrent-start-b-" + fixture.occurrenceId()))));
            assertThat(secondInvoked.await(10, TimeUnit.SECONDS)).isTrue();
            releaseFirstCommit.countDown();

            WorkoutExecution winner = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("ACTIVE_WORKOUT_EXECUTION_EXISTS");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs WHERE planned_workout_id=?",
                    Integer.class, fixture.occurrenceId())).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.exercise_logs WHERE workout_session_log_id=?",
                    Integer.class, winner.id())).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_execution_status_history WHERE workout_execution_id=?",
                    Integer.class, winner.id())).isOne();
            assertThat(jdbc.queryForObject("SELECT version FROM fitness.planned_workouts WHERE id=?",
                    Long.class, fixture.occurrenceId())).isOne();
        }
    }

    @Test
    void startWinningAgainstAdjustmentSealsBeforeAdjustmentCanCommit() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch startReady = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch adjustmentInvoked = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<WorkoutExecution> start = pool.submit(() -> heldTransaction(() -> executions.start(new StartCommand(
                    fixture.occurrenceId(), fixture.studentId(), 0, "start-adjustment-" + fixture.occurrenceId())),
                    startReady, releaseStart));
            assertThat(startReady.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> adjustment = pool.submit(() -> transactionOutcome(adjustmentInvoked, () ->
                    plans.adjustOccurrence(new AdjustOccurrenceCommand(fixture.occurrenceId(), fixture.studentId(), 0,
                            "late-adjustment-" + fixture.occurrenceId(), WorkoutSessionAdjustment.Type.NOTE,
                            fixture.prescriptionId(), null, null, "{\"note\":\"Late\"}", "Late change"))));
            assertThat(adjustmentInvoked.await(10, TimeUnit.SECONDS)).isTrue();
            releaseStart.countDown();

            WorkoutExecution winner = start.get(10, TimeUnit.SECONDS);
            assertThat(adjustment.get(10, TimeUnit.SECONDS)).isEqualTo("PLANNED_WORKOUT_ADJUSTMENTS_SEALED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id=?",
                    Integer.class, fixture.occurrenceId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_execution_applied_adjustments WHERE workout_execution_id=?",
                    Integer.class, winner.id())).isZero();
        }
    }

    @Test
    void startWinningAgainstSchedulingBoundaryRejectsLateScheduleMutation() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch startReady = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch scheduleInvoked = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<WorkoutExecution> start = pool.submit(() -> heldTransaction(() -> executions.start(new StartCommand(
                    fixture.occurrenceId(), fixture.studentId(), 0, "start-schedule-" + fixture.occurrenceId())),
                    startReady, releaseStart));
            assertThat(startReady.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> schedule = pool.submit(() -> transactionOutcome(scheduleInvoked,
                    () -> { rescheduleThroughSharedBoundary(fixture); return null; }));
            assertThat(scheduleInvoked.await(10, TimeUnit.SECONDS)).isTrue();
            releaseStart.countDown();

            start.get(10, TimeUnit.SECONDS);
            assertThat(schedule.get(10, TimeUnit.SECONDS)).isEqualTo("PLANNED_WORKOUT_SCHEDULE_LOCKED");
            assertThat(jdbc.queryForObject("SELECT planned_start_at=original_planned_start_at FROM fitness.planned_workouts WHERE id=?",
                    Boolean.class, fixture.occurrenceId())).isTrue();
        }
    }

    @Test
    void setCommitBeforeCompleteIsVisibleToTerminalDerivation() throws Exception {
        Fixture fixture = fixture();
        WorkoutExecution started = executions.start(new StartCommand(fixture.occurrenceId(), fixture.studentId(), 0,
                "start-set-complete-" + fixture.occurrenceId()));
        SetExecution set = completedSet(UUID.randomUUID());
        CountDownLatch setReady = new CountDownLatch(1);
        CountDownLatch releaseSet = new CountDownLatch(1);
        CountDownLatch completeInvoked = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<WorkoutExecution> setWrite = pool.submit(() -> heldTransaction(() -> executions.upsertSet(
                    new UpsertSetCommand(started.id(), started.exercises().getFirst().id(), fixture.studentId(), 0, set)),
                    setReady, releaseSet));
            assertThat(setReady.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> complete = pool.submit(() -> transactionOutcome(completeInvoked, () -> executions.complete(
                    new TerminalCommand(started.id(), fixture.studentId(), 1,
                            "complete-after-set-" + started.id(), null, "Done"))));
            assertThat(completeInvoked.await(10, TimeUnit.SECONDS)).isTrue();
            releaseSet.countDown();

            assertThat(setWrite.get(10, TimeUnit.SECONDS).version()).isOne();
            assertThat(complete.get(10, TimeUnit.SECONDS)).isEqualTo("SUCCESS");
            assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.workout_session_logs WHERE id=?",
                    String.class, started.id())).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.planned_workouts WHERE id=?",
                    String.class, fixture.occurrenceId())).isEqualTo("COMPLETED");
        }
    }

    @Test
    void completeAndAbortRaceProducesOneTerminalTransitionAndOneStableLoser() throws Exception {
        Fixture fixture = fixture();
        WorkoutExecution started = executions.start(new StartCommand(fixture.occurrenceId(), fixture.studentId(), 0,
                "start-terminal-race-" + fixture.occurrenceId()));
        WorkoutExecution withSet = executions.upsertSet(new UpsertSetCommand(started.id(),
                started.exercises().getFirst().id(), fixture.studentId(), 0, completedSet(UUID.randomUUID())));
        CountDownLatch completeReady = new CountDownLatch(1);
        CountDownLatch releaseComplete = new CountDownLatch(1);
        CountDownLatch abortInvoked = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<WorkoutExecution> complete = pool.submit(() -> heldTransaction(() -> executions.complete(
                    new TerminalCommand(started.id(), fixture.studentId(), withSet.version(),
                            "race-complete-" + started.id(), null, "Complete wins")), completeReady, releaseComplete));
            assertThat(completeReady.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> abort = pool.submit(() -> transactionOutcome(abortInvoked, () -> executions.abort(
                    new TerminalCommand(started.id(), fixture.studentId(), withSet.version(),
                            "race-abort-" + started.id(), null, "Abort loses"))));
            assertThat(abortInvoked.await(10, TimeUnit.SECONDS)).isTrue();
            releaseComplete.countDown();

            assertThat(complete.get(10, TimeUnit.SECONDS).status().name()).isEqualTo("COMPLETED");
            assertThat(abort.get(10, TimeUnit.SECONDS)).isIn(
                    "WORKOUT_EXECUTION_VERSION_CONFLICT", "WORKOUT_EXECUTION_TERMINAL");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_execution_status_history WHERE workout_execution_id=? AND from_status IS NOT NULL",
                    Integer.class, started.id())).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_execution_command_receipts WHERE workout_execution_id=? AND command_name IN ('COMPLETE','ABORT')",
                    Integer.class, started.id())).isOne();
            assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.planned_workouts WHERE id=?",
                    String.class, fixture.occurrenceId())).isEqualTo("COMPLETED");
        }
    }

    private <T> T heldTransaction(Callable<T> action, CountDownLatch effectReady,
                                  CountDownLatch releaseCommit) {
        return transactionTemplate().execute(status -> {
            try {
                T result = action.call();
                effectReady.countDown();
                if (!releaseCommit.await(10, TimeUnit.SECONDS)) throw new AssertionError("Commit release timed out");
                return result;
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private String transactionOutcome(CountDownLatch invoked, Callable<?> action) {
        invoked.countDown();
        try {
            transactionTemplate().execute(status -> {
                try {
                action.call();
                    return null;
                } catch (RuntimeException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            return "SUCCESS";
        } catch (WorkoutExecutionFailure failure) {
            return failure.error().name();
        } catch (WorkoutPlanFailure failure) {
            return failure.error().name();
        } catch (RuntimeException failure) {
            return "RAW:" + failure.getClass().getSimpleName();
        }
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.setTimeout(10);
        return template;
    }

    private void rescheduleThroughSharedBoundary(Fixture fixture) {
        planStore.lockStudentAndActor(fixture.studentId(), fixture.studentId());
        var occurrence = planStore.lockOccurrence(fixture.occurrenceId());
        if (occurrence.executionStartedAt() != null) {
            throw new WorkoutPlanFailure(WorkoutPlanError.PLANNED_WORKOUT_SCHEDULE_LOCKED,
                    "Started occurrence schedule is locked");
        }
        Instant moved = Instant.now().plusSeconds(3600);
        jdbc.update("""
                UPDATE fitness.planned_workouts SET planned_start_at=?,version=version+1,updated_at=clock_timestamp()
                WHERE id=? AND version=? AND execution_started_at IS NULL
                """, java.sql.Timestamp.from(moved), fixture.occurrenceId(), occurrence.version());
    }

    private SetExecution completedSet(UUID clientSetId) {
        return new SetExecution(null, clientSetId, 1, 1, "WORKING", "COMPLETED",
                10, null, null, null, null, null, null, null, null, null, "Completed");
    }

    private Fixture fixture() {
        UUID student = person();
        UUID period = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,created_by) VALUES (?,?,'SELF_DIRECTED',clock_timestamp()-interval '1 day',?)",
                period, student, student);
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID(), session = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,coaching_period_id,name,source,status,created_by,decision_owner_type,decision_owner_id)
                VALUES (?,?,?,'B05 concurrency','STUDENT','DRAFT',?,'STUDENT',?)
                """, plan, student, period, student, student);
        jdbc.update("INSERT INTO fitness.workout_plan_versions(id,workout_plan_id,version_number,change_level,created_by) VALUES (?,?,1,'INITIAL',?)",
                version, plan, student);
        jdbc.update("INSERT INTO fitness.workout_plan_sessions(id,workout_plan_version_id,week_number,day_number,sequence_number,name) VALUES (?,?,1,1,1,'Session')",
                session, version);
        UUID variation = variation(student);
        UUID prescription = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plan_session_exercises
                (id,workout_plan_session_id,exercise_variation_id,sequence_number,target_sets,target_reps_min,target_reps_max)
                VALUES (?,?,?,1,1,8,12)
                """, prescription, session, variation);
        jdbc.update("""
                UPDATE fitness.workout_plan_versions SET effective_from=clock_timestamp()-interval '1 day',
                    locked_at=clock_timestamp(),locked_by=?,lock_reason='ACTIVATED' WHERE id=?
                """, student, version);
        jdbc.update("UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1 WHERE id=?", plan);
        UUID occurrence = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,coaching_period_id,planned_start_at,
                 original_planned_start_at,created_by)
                VALUES (?,?,?,?,clock_timestamp(),clock_timestamp(),?)
                """, occurrence, student, session, period, student);
        return new Fixture(student, occurrence, prescription);
    }

    private UUID person() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?,'hash','Student','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')",
                id, id + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='STUDENT'",
                id, id);
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        return id;
    }

    private UUID variation(UUID creator) {
        UUID exercise = UUID.randomUUID(), variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                exercise, "exercise-" + exercise, "Exercise", creator);
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                variation, exercise, "variation-" + variation, "Variation");
        return variation;
    }

    private record Fixture(UUID studentId, UUID occurrenceId, UUID prescriptionId) {}
}
