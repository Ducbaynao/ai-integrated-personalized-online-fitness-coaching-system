package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.common.exception.DataSharingAccessLevelInsufficientException;
import com.fitnesscoaching.platform.common.exception.DataSharingPermissionRequiredException;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.*;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.workout.domain.ExercisePrescription;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionAdjustment;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class WorkoutPlanPersistenceIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("workout_plan_b04").withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired WorkoutPlanCommandUseCase commands;
    @Autowired WorkoutPlanQueryUseCase queries;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuditService audit;
    @MockitoSpyBean WorkoutPlanPersistencePort store;
    @Autowired ExerciseReferenceQuery exerciseReferences;

    @BeforeEach void resetSpies() { reset(audit, store); }

    @Test
    void draftAuthoringPersistsReplacementSnapshotAndReadProjection() {
        Fixture f = fixture();
        CommandResult created = commands.createDraft(new CreateDraftCommand(f.studentId(), f.studentId(),
                "Kế hoạch API", "Bản nháp", blueprint(f.variationId()), "create-draft-api"));

        var initial = queries.version(f.studentId(), created.planId(), created.planVersionId());
        assertThat(initial.sessions()).hasSize(1);
        assertThat(initial.sessions().getFirst().prescriptions()).hasSize(1);
        assertThat(initial.sessions().getFirst().prescriptions().getFirst().exercise().state())
                .isEqualTo("ACTIVE");

        CommandResult updated = commands.updateDraft(new UpdateDraftCommand(created.planId(), f.studentId(), 0,
                "Kế hoạch API đã sửa", null, List.of(), "update-draft-api"));
        var detail = queries.detail(f.studentId(), created.planId());
        var replacement = queries.version(f.studentId(), created.planId(), created.planVersionId());

        assertThat(updated.aggregateVersion()).isOne();
        assertThat(detail.plan().name()).isEqualTo("Kế hoạch API đã sửa");
        assertThat(replacement.sessions()).isEmpty();
    }

    @Test
    void activationIsIdempotentAndPublicationUsesOneBoundaryWithoutRemappingOccurrence() {
        Fixture f = fixture();
        CommandResult activated = commands.activate(new ActivateCommand(f.planId(), f.studentId(), 0, "activate-1"));
        CommandResult replay = commands.activate(new ActivateCommand(f.planId(), f.studentId(), 0, "activate-1"));
        assertThat(activated.status()).isEqualTo(WorkoutPlanStatus.ACTIVE);
        assertThat(replay.replayed()).isTrue();

        UUID occurrence = materialize(f, f.sessionId());
        UUID originalSession = jdbc.queryForObject(
                "SELECT workout_plan_session_id FROM fitness.planned_workouts WHERE id=?", UUID.class, occurrence);
        CommandResult published = commands.publishVersion(new PublishVersionCommand(
                f.planId(), f.studentId(), 1, "publish-1", "PROGRESSION", "Next phase", blueprint(f.variationId())));

        Timestamp oldUntil = jdbc.queryForObject(
                "SELECT effective_until FROM fitness.workout_plan_versions WHERE id=?", Timestamp.class, f.versionId());
        Timestamp newFrom = jdbc.queryForObject(
                "SELECT effective_from FROM fitness.workout_plan_versions WHERE id=?", Timestamp.class, published.planVersionId());
        assertThat(oldUntil).isEqualTo(newFrom);
        assertThat(jdbc.queryForObject(
                "SELECT workout_plan_session_id FROM fitness.planned_workouts WHERE id=?", UUID.class, occurrence))
                .isEqualTo(originalSession);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM fitness.workout_plan_command_receipts WHERE actor_id=?", Integer.class, f.studentId()))
                .isEqualTo(2);
    }

    @Test
    void occurrenceAdjustmentIsAppendOnlyOverlayAndNeverMutatesPublishedPrescription() {
        Fixture f = fixture();
        commands.activate(new ActivateCommand(f.planId(), f.studentId(), 0, "activate-adjust"));
        UUID occurrence = materialize(f, f.sessionId());
        String originalInstructions = jdbc.queryForObject(
                "SELECT instructions FROM fitness.workout_plan_session_exercises WHERE id=?", String.class, f.prescriptionId());

        OccurrenceResult result = commands.adjustOccurrence(new AdjustOccurrenceCommand(
                occurrence, f.studentId(), 0, "adjust-1", WorkoutSessionAdjustment.Type.NOTE,
                f.prescriptionId(), null, null, "{\"note\":\"Giảm nhịp\"}", "Theo cảm nhận buổi tập"));

        assertThat(result.occurrenceVersion()).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id=?", Integer.class, occurrence))
                .isOne();
        assertThat(jdbc.queryForObject(
                "SELECT instructions FROM fitness.workout_plan_session_exercises WHERE id=?", String.class, f.prescriptionId()))
                .isEqualTo(originalInstructions);
    }

    @Test
    void archivedExerciseHistoricalResolutionPreservesOriginalVariationIdentity() {
        Fixture f = fixture();
        UUID exerciseId = jdbc.queryForObject(
                "SELECT exercise_id FROM fitness.exercise_variations WHERE id=?", UUID.class, f.variationId());
        jdbc.update("UPDATE fitness.exercises SET admin_status='ARCHIVED',version=version+1,updated_at=clock_timestamp() WHERE id=?",
                exerciseId);

        var resolved = exerciseReferences.resolveHistorical(f.variationId()).orElseThrow();
        assertThat(resolved.variationId()).isEqualTo(f.variationId());
        assertThat(resolved.exerciseId()).isEqualTo(exerciseId);
        assertThat(resolved.state()).isEqualTo(ExerciseReferenceQuery.PresentationState.ARCHIVED);
    }

    @Test
    void archivedExerciseCannotBeUsedForActivationAuthoring() {
        Fixture f = fixture();
        UUID exerciseId = jdbc.queryForObject(
                "SELECT exercise_id FROM fitness.exercise_variations WHERE id=?", UUID.class, f.variationId());
        jdbc.update("UPDATE fitness.exercises SET admin_status='ARCHIVED',version=version+1,updated_at=clock_timestamp() WHERE id=?",
                exerciseId);

        assertThatThrownBy(() -> commands.activate(new ActivateCommand(
                f.planId(), f.studentId(), 0, "archived-exercise")))
                .isInstanceOf(WorkoutPlanFailure.class)
                .extracting(ex -> ((WorkoutPlanFailure) ex).error())
                .isEqualTo(WorkoutPlanError.WORKOUT_PLAN_EXERCISE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.workout_plans WHERE id=?",
                String.class, f.planId())).isEqualTo("DRAFT");
    }

    @Test
    void trainerMutationRequiresManageAndReceiptReplayRechecksCurrentPermission() {
        TrainerFixture viewOnly = trainerFixture("VIEW");
        assertThatThrownBy(() -> commands.activate(new ActivateCommand(
                viewOnly.plan().planId(), viewOnly.trainerId(), 0, "trainer-view")))
                .isInstanceOf(DataSharingAccessLevelInsufficientException.class);
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.workout_plans WHERE id=?",
                String.class, viewOnly.plan().planId())).isEqualTo("DRAFT");

        TrainerFixture managed = trainerFixture("MANAGE");
        commands.activate(new ActivateCommand(managed.plan().planId(), managed.trainerId(), 0, "trainer-manage"));
        jdbc.update("""
                UPDATE fitness.data_sharing_permissions
                SET revoked_at=clock_timestamp(),revoke_reason='TEST_REVOKE',version=version+1
                WHERE id=? AND version=0
                """, managed.permissionId());

        assertThatThrownBy(() -> commands.activate(new ActivateCommand(
                managed.plan().planId(), managed.trainerId(), 0, "trainer-manage")))
                .isInstanceOf(DataSharingPermissionRequiredException.class);
    }

    @Test
    void studentSuccessorDeepCopiesLockedSourceAndRetainsExplicitLineage() {
        FormerTrainerFixture delivered = formerTrainerDeliveredPlan();
        Fixture f = delivered.plan();

        CommandResult successor = commands.createStudentSuccessor(new CreateStudentSuccessorCommand(
                f.planId(), f.versionId(), f.studentId(), "Kế hoạch tự tập tiếp nối", "successor-1"));

        assertThat(successor.status()).isEqualTo(WorkoutPlanStatus.DRAFT);
        assertThat(jdbc.queryForObject("SELECT based_on_plan_id FROM fitness.workout_plans WHERE id=?",
                UUID.class, successor.planId())).isEqualTo(f.planId());
        assertThat(jdbc.queryForObject("SELECT based_on_plan_version_id FROM fitness.workout_plans WHERE id=?",
                UUID.class, successor.planId())).isEqualTo(f.versionId());
        assertThat(jdbc.queryForObject("SELECT decision_owner_type::text FROM fitness.workout_plans WHERE id=?",
                String.class, successor.planId())).isEqualTo("STUDENT");
        assertThat(jdbc.queryForObject("SELECT decision_owner_type::text FROM fitness.workout_plans WHERE id=?",
                String.class, f.planId())).isEqualTo("TRAINER");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.workout_plan_session_exercises e
                JOIN fitness.workout_plan_sessions s ON s.id=e.workout_plan_session_id
                WHERE s.workout_plan_version_id=?
                """, Integer.class, successor.planVersionId())).isOne();
        assertThat(jdbc.queryForObject("SELECT ownership_transferred_to_student_at FROM fitness.workout_plans WHERE id=?",
                Timestamp.class, f.planId())).isNull();

        assertThatThrownBy(() -> commands.activate(new ActivateCommand(
                successor.planId(), f.studentId(), 0, "activate-successor-too-early")))
                .isInstanceOf(WorkoutPlanFailure.class)
                .extracting(ex -> ((WorkoutPlanFailure) ex).error())
                .isEqualTo(WorkoutPlanError.ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS);
        commands.transition(new TransitionCommand(f.planId(), f.studentId(), 1,
                WorkoutPlanStatus.COMPLETED, "Chuyển sang tự tập", "complete-delivered"));
        assertThat(commands.activate(new ActivateCommand(
                successor.planId(), f.studentId(), 0, "activate-successor")).status())
                .isEqualTo(WorkoutPlanStatus.ACTIVE);
    }

    @Test
    void concurrentActivationAllowsExactlyOneActivePlanForStudent() throws Exception {
        Fixture first = fixture();
        Fixture second = additionalPlan(first);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(); AtomicInteger conflicts = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> activateAfter(start, first, "race-a", successes, conflicts));
            var b = pool.submit(() -> activateAfter(start, second, "race-b", successes, conflicts));
            start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        assertThat(successes).hasValue(1);
        assertThat(conflicts).hasValue(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM fitness.workout_plans WHERE student_id=? AND status='ACTIVE'", Integer.class,
                first.studentId())).isOne();
    }

    @Test
    void concurrentPublicationHasOneWinnerAndNoPartialVersion() throws Exception {
        Fixture f = fixture();
        commands.activate(new ActivateCommand(f.planId(), f.studentId(), 0, "activate-publish-race"));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(); AtomicInteger conflicts = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> publishAfter(start, f, "publish-race-a", successes, conflicts));
            var b = pool.submit(() -> publishAfter(start, f, "publish-race-b", successes, conflicts));
            start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        assertThat(successes).hasValue(1);
        assertThat(conflicts).hasValue(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM fitness.workout_plan_versions WHERE workout_plan_id=?", Integer.class, f.planId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM fitness.workout_plan_versions WHERE workout_plan_id=? AND effective_until IS NULL",
                Integer.class, f.planId())).isOne();
        assertThat(jdbc.queryForObject("SELECT version FROM fitness.workout_plans WHERE id=?", Long.class, f.planId()))
                .isEqualTo(2);
    }

    @Test
    void auditFailureRollsBackActivationHistoryVersionAndReceipt() {
        Fixture f = fixture();
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordAudit(any());

        assertThatThrownBy(() -> commands.activate(new ActivateCommand(
                f.planId(), f.studentId(), 0, "audit-fail"))).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.workout_plans WHERE id=?",
                String.class, f.planId())).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT locked_at FROM fitness.workout_plan_versions WHERE id=?",
                Timestamp.class, f.versionId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_plan_status_history WHERE workout_plan_id=?",
                Integer.class, f.planId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_plan_command_receipts WHERE command_key='audit-fail'",
                Integer.class)).isZero();
    }

    @Test
    void receiptFailureRollsBackActivationAuditHistoryAndVersion() {
        Fixture f = fixture();
        doThrow(new IllegalStateException("receipt unavailable")).when(store).saveReceipt(any());

        assertThatThrownBy(() -> commands.activate(new ActivateCommand(
                f.planId(), f.studentId(), 0, "receipt-fail"))).isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.workout_plans WHERE id=?",
                String.class, f.planId())).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT locked_at FROM fitness.workout_plan_versions WHERE id=?",
                Timestamp.class, f.versionId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE target_id=?",
                Integer.class, f.planId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_plan_status_history WHERE workout_plan_id=?",
                Integer.class, f.planId())).isZero();
    }

    private void activateAfter(CountDownLatch start, Fixture fixture, String key,
                               AtomicInteger successes, AtomicInteger conflicts) {
        try {
            start.await(); commands.activate(new ActivateCommand(fixture.planId(), fixture.studentId(), 0, key));
            successes.incrementAndGet();
        } catch (WorkoutPlanFailure expected) { conflicts.incrementAndGet(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void publishAfter(CountDownLatch start, Fixture fixture, String key,
                              AtomicInteger successes, AtomicInteger conflicts) {
        try {
            start.await();
            commands.publishVersion(new PublishVersionCommand(fixture.planId(), fixture.studentId(), 1,
                    key, "PROGRESSION", key, blueprint(fixture.variationId())));
            successes.incrementAndGet();
        } catch (WorkoutPlanFailure expected) { conflicts.incrementAndGet(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private Fixture fixture() {
        UUID student = person("STUDENT"); UUID period = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,created_by) VALUES (?,?,'SELF_DIRECTED',clock_timestamp()-interval '1 day',?)",
                period, student, student);
        UUID exercise = UUID.randomUUID(), variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                exercise, "exercise-" + exercise, "Exercise", student);
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                variation, exercise, "variation-" + variation, "Variation");
        return createPlan(student, period, variation, "Plan " + UUID.randomUUID());
    }

    private TrainerFixture trainerFixture(String level) {
        UUID student = person("STUDENT"), trainer = person("TRAINER"), relationship = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                (id,student_id,trainer_id,status,requested_by,requested_at,accepted_at,started_at)
                VALUES (?,?,?,'ACTIVE',?,clock_timestamp()-interval '2 days',
                        clock_timestamp()-interval '2 days',clock_timestamp()-interval '2 days')
                """, relationship, student, trainer, student);
        UUID period = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_periods
                (id,student_id,mode,coaching_relationship_id,trainer_id,started_at,created_by)
                VALUES (?,?,'HUMAN_COACH',?,?,clock_timestamp()-interval '2 days',?)
                """, period, student, relationship, trainer, trainer);
        UUID permission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id,relationship_id,student_id,trainer_id,data_scope,decision,access_level,valid_from,granted_by)
                VALUES (?,?,?,?, 'WORKOUT_PLAN','ALLOW',?::fitness.data_access_level,
                        clock_timestamp()-interval '1 day',?)
                """, permission, relationship, student, trainer, level, student);
        UUID exercise = UUID.randomUUID(), variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                exercise, "exercise-" + exercise, "Exercise", trainer);
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                variation, exercise, "variation-" + variation, "Variation");
        Fixture plan = createTrainerPlan(student, trainer, period, variation);
        return new TrainerFixture(plan, trainer, permission);
    }

    private FormerTrainerFixture formerTrainerDeliveredPlan() {
        UUID student = person("STUDENT"), trainer = person("TRAINER"), relationship = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                (id,student_id,trainer_id,status,requested_by,requested_at,accepted_at,started_at,ended_at,termination_reason)
                VALUES (?,?,?,'ENDED',?,clock_timestamp()-interval '10 days',
                        clock_timestamp()-interval '10 days',clock_timestamp()-interval '10 days',
                        clock_timestamp()-interval '2 days','HANDOFF')
                """, relationship, student, trainer, student);
        UUID humanPeriod = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_periods
                (id,student_id,mode,coaching_relationship_id,trainer_id,started_at,ended_at,reason,created_by)
                VALUES (?,?,'HUMAN_COACH',?,?,clock_timestamp()-interval '10 days',
                        clock_timestamp()-interval '2 days','HANDOFF',?)
                """, humanPeriod, student, relationship, trainer, trainer);
        UUID selfPeriod = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,reason,created_by)
                VALUES (?,?,'SELF_DIRECTED',clock_timestamp()-interval '2 days','HANDOFF',?)
                """, selfPeriod, student, student);
        UUID exercise = UUID.randomUUID(), variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                exercise, "exercise-" + exercise, "Exercise", trainer);
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                variation, exercise, "variation-" + variation, "Variation");
        Fixture plan = createTrainerPlan(student, trainer, humanPeriod, variation);
        jdbc.update("""
                UPDATE fitness.workout_plan_versions
                SET effective_from=clock_timestamp()-interval '9 days',locked_at=clock_timestamp()-interval '9 days',
                    locked_by=?,lock_reason='ACTIVATED' WHERE id=?
                """, trainer, plan.versionId());
        jdbc.update("""
                UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1,assigned_by=?,
                    assigned_at=clock_timestamp()-interval '9 days' WHERE id=?
                """, trainer, plan.planId());
        return new FormerTrainerFixture(plan, trainer, selfPeriod);
    }

    private Fixture createTrainerPlan(UUID student, UUID trainer, UUID period, UUID variation) {
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID(), session = UUID.randomUUID(), prescription = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,coaching_period_id,name,source,status,created_by,decision_owner_type,decision_owner_id)
                VALUES (?, ?,?,'Trainer plan','TRAINER','DRAFT',?,'TRAINER',?)
                """, plan, student, period, trainer, trainer);
        jdbc.update("INSERT INTO fitness.workout_plan_versions(id,workout_plan_id,version_number,change_level,created_by) VALUES (?,?,1,'INITIAL',?)",
                version, plan, trainer);
        jdbc.update("INSERT INTO fitness.workout_plan_sessions(id,workout_plan_version_id,week_number,day_number,sequence_number,name) VALUES (?,?,1,1,1,'Ngày 1')",
                session, version);
        jdbc.update("INSERT INTO fitness.workout_plan_session_exercises(id,workout_plan_session_id,exercise_variation_id,sequence_number) VALUES (?,?,?,1)",
                prescription, session, variation);
        return new Fixture(student, period, plan, version, session, prescription, variation);
    }

    private Fixture additionalPlan(Fixture base) {
        return createPlan(base.studentId(), base.periodId(), base.variationId(), "Second plan " + UUID.randomUUID());
    }

    private Fixture createPlan(UUID student, UUID period, UUID variation, String name) {
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID(), session = UUID.randomUUID(), prescription = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,coaching_period_id,name,source,status,created_by,decision_owner_type,decision_owner_id)
                VALUES (?,?,?,?,'STUDENT','DRAFT',?,'STUDENT',?)
                """, plan, student, period, name, student, student);
        jdbc.update("INSERT INTO fitness.workout_plan_versions(id,workout_plan_id,version_number,change_level,created_by) VALUES (?,?,1,'INITIAL',?)",
                version, plan, student);
        jdbc.update("INSERT INTO fitness.workout_plan_sessions(id,workout_plan_version_id,week_number,day_number,sequence_number,name) VALUES (?,?,1,1,1,'Ngày 1')",
                session, version);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_session_exercises
                (id,workout_plan_session_id,exercise_variation_id,sequence_number,target_sets,target_reps_min,target_reps_max,instructions)
                VALUES (?,?,?,1,3,8,12,'Giữ kỹ thuật ổn định')
                """, prescription, session, variation);
        return new Fixture(student, period, plan, version, session, prescription, variation);
    }

    private UUID materialize(Fixture f, UUID session) {
        UUID occurrence = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,coaching_period_id,planned_start_at,
                 original_planned_start_at,created_by)
                VALUES (?,?,?, ?,clock_timestamp()+interval '1 day',clock_timestamp()+interval '1 day',?)
                """, occurrence, f.studentId(), session, f.periodId(), f.studentId());
        return occurrence;
    }

    private List<WorkoutSessionTemplate> blueprint(UUID variation) {
        return List.of(new WorkoutSessionTemplate(null, 1, 1, 1, "Ngày 1 nâng cấp", "Sức mạnh", 45, null,
                List.of(new ExercisePrescription(null, variation, 1, 4, 8, 10,
                        null, 90, null, "Tăng dần tải"))));
    }

    private UUID person(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?,'hash','Person','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')",
                id, id + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?",
                id, id, role);
        if (role.equals("STUDENT")) jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        if (role.equals("TRAINER")) jdbc.update("""
                INSERT INTO fitness.trainer_profiles
                (user_id,verification_status,verified_at,is_accepting_students,is_active,activity_status)
                VALUES (?,'VERIFIED',clock_timestamp(),true,true,'ACTIVE')
                """, id);
        return id;
    }

    private record Fixture(UUID studentId, UUID periodId, UUID planId, UUID versionId,
                           UUID sessionId, UUID prescriptionId, UUID variationId) {}
    private record TrainerFixture(Fixture plan, UUID trainerId, UUID permissionId) {}
    private record FormerTrainerFixture(Fixture plan, UUID trainerId, UUID selfPeriodId) {}
}
