package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutRescheduleUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.ConfirmBatch;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AiWorkoutRescheduleIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_f05_slice_test").withUsername("fitness_app").withPassword("testpass123");

    static {
        postgres.start();
    }

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
        registry.add("ai.service.mode", () -> "simulated");
    }

    @Autowired private AiWorkoutRescheduleUseCase rescheduleService;
    @Autowired private AiRecommendationRepository recommendationRepository;
    @Autowired private WorkoutScheduleBatchUseCase batches;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private record TestFixture(UUID studentId, UUID planId, UUID versionId, UUID sessionId) {}

    private TestFixture createFixture(boolean coachRequired) {
        UUID student = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        jdbc.update("INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone) VALUES (?, ?, 'hash', 'Student', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh')",
                student, student + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
        jdbc.update("""
                INSERT INTO fitness.workout_plans(id, student_id, name, source, status, created_by, decision_owner_type, decision_owner_id)
                VALUES (?, ?, 'Test Plan', 'STUDENT', 'DRAFT', ?, 'STUDENT', ?)
                """, plan, student, student, student);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions(id, workout_plan_id, version_number, change_level, created_by)
                VALUES (?, ?, 1, 'INITIAL', ?)
                """, version, plan, student);

        String supervision = coachRequired ? "COACH_REQUIRED" : "SELF_ALLOWED";
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id, workout_plan_version_id, week_number, day_number, sequence_number, name, supervision_requirement)
                VALUES (?, ?, 1, 1, 1, 'Chest & Triceps', ?::fitness.supervision_requirement)
                """, session, version, supervision);

        UUID exercise = UUID.randomUUID();
        UUID variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id, code, name, admin_status, created_by) VALUES (?, ?, 'Bench Press', 'ACTIVE', ?)",
                exercise, "ex-" + exercise, student);
        jdbc.update("INSERT INTO fitness.exercise_variations(id, exercise_id, code, name, is_active) VALUES (?, ?, ?, 'Barbell Bench Press', true)",
                variation, exercise, "barbell-bench-" + variation);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_session_exercises
                (id, workout_plan_session_id, exercise_variation_id, sequence_number, target_sets, target_reps_min, target_reps_max)
                VALUES (?, ?, ?, 1, 3, 8, 12)
                """, UUID.randomUUID(), session, variation);

        jdbc.update("""
                UPDATE fitness.workout_plan_versions SET effective_from=clock_timestamp(),
                    locked_at=clock_timestamp(), locked_by=?, lock_reason='ACTIVATED' WHERE id=?
                """, student, version);
        jdbc.update("""
                UPDATE fitness.workout_plans SET status='ACTIVE', version=version+1,
                    assigned_by=?, assigned_at=clock_timestamp() WHERE id=?
                """, student, plan);

        return new TestFixture(student, plan, version, session);
    }

    private UUID createOccurrence(TestFixture f, LocalDateTime start) {
        LocalDate anchor = start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return batches.confirmDirect(new ConfirmBatch(
                f.studentId(), f.planId(), f.versionId(), 1, anchor, "Asia/Ho_Chi_Minh",
                List.of(new Item(UUID.randomUUID(), f.sessionId(), 1, 1, 1,
                        start, start.plusHours(1), "+07:00", "+07:00")),
                "confirm-" + UUID.randomUUID()
        )).items().getFirst().occurrenceId();
    }

    private void setupCoachingPeriod(UUID studentId, UUID trainerId, String mode) {
        jdbc.update("INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone) VALUES (?, ?, 'hash', 'Trainer', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh') ON CONFLICT DO NOTHING",
                trainerId, trainerId + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id, role_id, assigned_by, assigned_at) SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'TRAINER' ON CONFLICT DO NOTHING", trainerId, trainerId);
        jdbc.update("INSERT INTO fitness.trainer_profiles(user_id, bio, verification_status, activity_status, is_active) VALUES (?, 'Certified trainer', 'VERIFIED', 'ACTIVE', true) ON CONFLICT DO NOTHING", trainerId);

        UUID relId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships (id, trainer_id, student_id, status, requested_by, requested_at, accepted_at, started_at, created_at)
                VALUES (?, ?, ?, 'ACTIVE'::fitness.coaching_relationship_status, ?, clock_timestamp(), clock_timestamp(), clock_timestamp(), clock_timestamp())
                """, relId, trainerId, studentId, studentId);

        jdbc.update("""
                INSERT INTO fitness.coaching_periods(id, student_id, mode, coaching_relationship_id, trainer_id, started_at, created_by, created_at)
                VALUES (gen_random_uuid(), ?, ?::fitness.coaching_mode, ?, ?, clock_timestamp(), ?, clock_timestamp())
                """, studentId, mode, relId, trainerId, studentId);

        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id, relationship_id, student_id, trainer_id, data_scope, decision, access_level, valid_from, granted_by)
                VALUES (gen_random_uuid(), ?, ?, ?, 'WORKOUT_PLAN', 'ALLOW', 'MANAGE'::fitness.data_access_level,
                        clock_timestamp() - interval '1 day', ?)
                """, relId, studentId, trainerId, studentId);
    }

    @Test
    @DisplayName("T01/T27: Student in SELF_DIRECTED mode generates and accepts AI reschedule proposal atomically")
    void generateAndAcceptReschedule_selfDirected_success() {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        // 1. Generate Proposal
        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-1", "Schedule conflict", "MORNING"
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        assertThat(proposal.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(proposal.recommendationId()).isNotNull();
        assertThat(proposal.expiresAt()).isAfter(Instant.now());

        // 2. Accept Proposal
        var acceptReq = new AcceptRescheduleApiRequest(
                "accept-key-1", "Accepted reschedule proposal", 1, 0L, null, null
        );
        AcceptRescheduleApiResponse acceptRes = rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq);

        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(acceptRes.replayed()).isFalse();
        assertThat(acceptRes.resultingEntityId()).isNotNull();

        // 3. Verify Database State
        // Old occurrence is marked RESCHEDULED
        String oldStatus = jdbc.queryForObject(
                "SELECT status::text FROM fitness.planned_workouts WHERE id=?", String.class, occurrenceId
        );
        assertThat(oldStatus).isEqualTo("RESCHEDULED");

        // Replacement occurrence exists and is SCHEDULED
        String newStatus = jdbc.queryForObject(
                "SELECT status::text FROM fitness.planned_workouts WHERE id=?", String.class, acceptRes.resultingEntityId()
        );
        assertThat(newStatus).isEqualTo("SCHEDULED");

        // Recommendation status is APPLIED
        AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(rec.decidedBy()).isEqualTo(f.studentId());

        // Application record is saved
        var app = recommendationRepository.findApplicationByRecommendationId(proposal.recommendationId()).orElseThrow();
        assertThat(app.appliedBy()).isEqualTo(f.studentId());
        assertThat(app.resultingEntityId()).isEqualTo(acceptRes.resultingEntityId());

        // Audit log exists
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE action='AI_WORKOUT_RESCHEDULE_APPLIED' AND target_id=?",
                Integer.class, acceptRes.resultingEntityId()
        );
        assertThat(auditCount).isOne();
    }

    @Test
    @DisplayName("T02: Student in HUMAN_COACH mode cannot unilaterally accept; Trainer has authority")
    void studentInHumanCoachMode_acceptBlocked_T02() {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");

        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(3).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        // Student generates proposal (allowed)
        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-hc", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);
        assertThat(proposal.status()).isEqualTo(AiRecommendationStatus.PENDING);

        // Student attempts to accept -> BLOCKED (403)
        var studentAcceptReq = new AcceptRescheduleApiRequest(
                "student-accept-key", "Student attempting direct apply", 1, 0L, null, null
        );
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), studentAcceptReq))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Student in HUMAN_COACH mode cannot unilaterally accept AI workout rescheduling");

        // Assigned Trainer accepts -> SUCCEEDS (200)
        var trainerAcceptReq = new AcceptRescheduleApiRequest(
                "trainer-accept-key", "Coach approved new slot", 1, 0L, null, null
        );
        AcceptRescheduleApiResponse trainerAcceptRes = rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), trainerAcceptReq);

        assertThat(trainerAcceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(trainerAcceptRes.appliedBy()).isEqualTo(trainerId);
    }

    @Test
    @DisplayName("T03: Unrelated trainer cannot access or accept proposal for another student")
    void unrelatedTrainer_blocked_T03() {
        TestFixture f = createFixture(false);
        UUID legitTrainer = UUID.randomUUID();
        UUID rogueTrainer = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), legitTrainer, "HUMAN_COACH");

        jdbc.update("INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone) VALUES (?, ?, 'hash', 'Rogue', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh')",
                rogueTrainer, rogueTrainer + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id, role_id, assigned_by, assigned_at) SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'TRAINER' ON CONFLICT DO NOTHING", rogueTrainer, rogueTrainer);
        jdbc.update("INSERT INTO fitness.trainer_profiles(user_id, bio, verification_status, activity_status, is_active) VALUES (?, 'Rogue trainer', 'VERIFIED', 'ACTIVE', true)", rogueTrainer);

        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(3).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        // Rogue trainer cannot generate proposal (hidden with 404 to avoid enumeration)
        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "rogue-gen", "Spying", null
        );
        assertThatThrownBy(() -> rescheduleService.generateReschedule(rogueTrainer, generateReq))
                .isInstanceOf(AiRunNotFoundException.class);

        // Student generates proposal
        var legitGenReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "legit-gen", "Legit", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), legitGenReq);

        // Rogue trainer cannot view proposal (403)
        assertThatThrownBy(() -> rescheduleService.getReschedule(rogueTrainer, proposal.recommendationId()))
                .isInstanceOf(AiRunAccessDeniedException.class);

        // Rogue trainer cannot accept proposal (403)
        var rogueAcceptReq = new AcceptRescheduleApiRequest(
                "rogue-accept", "Rogue accept", 1, 0L, null, null
        );
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(rogueTrainer, proposal.recommendationId(), rogueAcceptReq))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Only the currently assigned coach has decision authority");
    }

    @Test
    @DisplayName("T05: Occurrence version conflict between generation and accept aborts and rolls back")
    void occurrenceVersionConflict_failsAndRollsBack_T05() {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-conflict", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        // Simulate concurrent modification to occurrence version
        jdbc.update("UPDATE fitness.planned_workouts SET version = version + 1 WHERE id=?", occurrenceId);

        var acceptReq = new AcceptRescheduleApiRequest(
                "accept-key-conflict", "Applying with outdated version", 1, 0L, null, null
        );

        assertThatThrownBy(() -> rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Workout occurrence version conflict");

        // Verify recommendation remains PENDING and no replacement created
        AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);
    }

    @Test
    @DisplayName("T06/T25: Idempotent replay returns cached receipt; differing parameters throw conflict")
    void idempotentAcceptRetry_replaysReceipt_T06_T25() {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-idem", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        var acceptReq = new AcceptRescheduleApiRequest(
                "idem-key-1", "Accept note", 1, 0L, null, null
        );
        AcceptRescheduleApiResponse firstRes = rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq);
        assertThat(firstRes.replayed()).isFalse();

        // 1. Replay with identical parameters -> replayed: true (T06)
        AcceptRescheduleApiResponse replayRes = rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq);
        assertThat(replayRes.replayed()).isTrue();
        assertThat(replayRes.resultingEntityId()).isEqualTo(firstRes.resultingEntityId());

        // Verify only 1 replacement occurrence exists
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?", Integer.class, occurrenceId
        );
        assertThat(count).isOne();

        // 2. Replay with same key but differing note -> 409 Conflict (T25)
        var differingReq = new AcceptRescheduleApiRequest(
                "idem-key-1", "DIFFERENT NOTE", 1, 0L, null, null
        );
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), differingReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Idempotency conflict");
    }

    @Test
    @DisplayName("T23: Expired proposal cannot be accepted and transitions to EXPIRED")
    void expiredProposal_failsAndMarksExpired_T23() {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-exp", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        // Manually expire recommendation in DB
        jdbc.update("UPDATE fitness.ai_recommendations SET expires_at = clock_timestamp() - interval '10 minutes' WHERE id=?",
                proposal.recommendationId());

        var acceptReq = new AcceptRescheduleApiRequest("accept-key-exp", "Late accept", 1, 0L, null, null);

        assertThatThrownBy(() -> rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Recommendation has expired");

        AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.EXPIRED);
    }

    @Test
    @DisplayName("Concurrent double accept with different keys: exactly one succeeds, other gets conflict")
    void concurrentDoubleAccept_differentKeys_onlyOneSucceeds() throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-conc", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<AcceptRescheduleApiResponse> successes = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        var acceptReq = new AcceptRescheduleApiRequest("accept-key-conc-" + index, "Note " + index, 1, 0L, null, null);
                        AcceptRescheduleApiResponse res = rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq);
                        successes.add(res);
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean done = doneLatch.await(5, TimeUnit.SECONDS);
            assertThat(done).isTrue();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(3, TimeUnit.SECONDS);
        }

        assertThat(successes).hasSize(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).isInstanceOf(AiRunLifecycleConflictException.class);

        // Exactly one rescheduled occurrence exists
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?", Integer.class, occurrenceId
        );
        assertThat(count).isOne();
    }

    @Test
    @DisplayName("Concurrent accept and reject: serialized consistently, exactly one transition wins")
    void concurrentAcceptAndReject_serializedConsistently() throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-ar", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        try {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    var acceptReq = new AcceptRescheduleApiRequest("accept-key-ar", "Accept note", 1, 0L, null, null);
                    rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq);
                    successCount.incrementAndGet();
                } catch (AiRunLifecycleConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            pool.submit(() -> {
                try {
                    startLatch.await();
                    var rejectReq = new RejectRescheduleApiRequest("Reject note");
                    rescheduleService.rejectReschedule(f.studentId(), proposal.recommendationId(), rejectReq);
                    successCount.incrementAndGet();
                } catch (AiRunLifecycleConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            boolean done = doneLatch.await(5, TimeUnit.SECONDS);
            assertThat(done).isTrue();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(3, TimeUnit.SECONDS);
        }

        assertThat(successCount.get()).isOne();
        assertThat(conflictCount.get()).isOne();

        AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(rec.status()).isIn(AiRecommendationStatus.APPLIED, AiRecommendationStatus.REJECTED);
    }

    @Test
    @DisplayName("Rejection marks recommendation REJECTED and leaves planned workout unchanged")
    void rejectReschedule_leavesWorkoutUnchanged() {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-rej", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        var rejectReq = new RejectRescheduleApiRequest("Not a good day for workout");
        RejectRescheduleApiResponse rejectRes = rescheduleService.rejectReschedule(f.studentId(), proposal.recommendationId(), rejectReq);

        assertThat(rejectRes.status()).isEqualTo(AiRecommendationStatus.REJECTED);

        AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.REJECTED);

        // Occurrence still SCHEDULED and no replacement
        String status = jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, occurrenceId);
        assertThat(status).isEqualTo("SCHEDULED");
        Integer replacementCount = jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?", Integer.class, occurrenceId);
        assertThat(replacementCount).isZero();
    }

    @Test
    @DisplayName("Trainer data sharing permission revoked blocks both read and accept")
    void trainerPermissionRevoked_blocksAccept() {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");

        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(3).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-perm", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        assertThat(rescheduleService.getReschedule(trainerId, proposal.recommendationId()).id())
                .isEqualTo(proposal.recommendationId());

        // Student revokes WORKOUT_PLAN permission
        jdbc.update("UPDATE fitness.data_sharing_permissions SET revoked_at=clock_timestamp(), revoke_reason='STUDENT_REVOKED', version=version+1 WHERE student_id=? AND data_scope='WORKOUT_PLAN'::fitness.data_scope_code",
                f.studentId());

        assertThatThrownBy(() -> rescheduleService.getReschedule(trainerId, proposal.recommendationId()))
                .isInstanceOf(AiRunAccessDeniedException.class);

        var trainerAcceptReq = new AcceptRescheduleApiRequest("trainer-key-revoked", "Trying to apply", 1, 0L, null, null);
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), trainerAcceptReq))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("authority is no longer valid");
    }

    @Test
    @DisplayName("Coaching period ended before accept aborts with lifecycle conflict (409)")
    void coachingPeriodChanged_abortsAccept() {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");

        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(3).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-period", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        // Coaching period is ended
        jdbc.update("UPDATE fitness.coaching_periods SET ended_at = clock_timestamp() WHERE student_id=?", f.studentId());

        var trainerAcceptReq = new AcceptRescheduleApiRequest("trainer-key-period", "Apply after period end", 1, 0L, null, null);
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), trainerAcceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Coaching period has changed");
    }
}
