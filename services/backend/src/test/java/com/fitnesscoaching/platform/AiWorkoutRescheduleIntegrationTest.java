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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentService;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fitnesscoaching.platform.modules.ai.application.port.out.F05AiServiceClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

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
    @Autowired private WorkoutExecutionCommandUseCase execution;
    @Autowired private AppointmentService appointmentService;
    @MockitoSpyBean private F05AiServiceClient aiServiceClient;

    @BeforeEach
    void resetMocks() {
        if (aiServiceClient != null) {
            reset(aiServiceClient);
        }
    }

    private record TestFixture(UUID studentId, UUID planId, UUID versionId, UUID sessionId) {}

    private TestFixture createFixture(boolean coachRequired) {
        UUID student = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        jdbc.update("INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone) VALUES (?, ?, 'hash', 'Student', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh')",
                student, student + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id, role_id, assigned_by, assigned_at) SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'STUDENT' ON CONFLICT DO NOTHING", student, student);
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

    private UUID missedOccurrence(TestFixture f) {
        Instant start = Instant.now().minus(2, ChronoUnit.DAYS);
        return timedOccurrence(f, start, start.plus(1, ChronoUnit.HOURS));
    }

    private UUID timedOccurrence(TestFixture f, Instant start, Instant end) {
        UUID id = UUID.randomUUID();
        String supervision = jdbc.queryForObject(
                "SELECT supervision_requirement::text FROM fitness.workout_plan_sessions WHERE id=?",
                String.class, f.sessionId());
        UUID periodId = jdbc.queryForList(
                "SELECT id FROM fitness.coaching_periods WHERE student_id=? AND started_at <= clock_timestamp() AND (ended_at IS NULL OR ended_at > clock_timestamp()) LIMIT 1",
                UUID.class, f.studentId())
                .stream().findFirst().orElse(null);
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,coaching_period_id,supervision_requirement,planned_start_at,planned_end_at,original_planned_start_at,
                 created_by,schedule_local_start,schedule_local_end,schedule_timezone,schedule_start_utc_offset,schedule_end_utc_offset)
                VALUES (?,?,?,?,?::fitness.supervision_requirement,?,?,?,?, ?,?,'Asia/Ho_Chi_Minh','+07:00','+07:00')
                """, id, f.studentId(), f.sessionId(), periodId, supervision, Timestamp.from(start), end == null ? null : Timestamp.from(end),
                Timestamp.from(start), f.studentId(),
                LocalDateTime.ofInstant(start, ZoneOffset.ofHours(7)).toString(),
                end == null ? null : LocalDateTime.ofInstant(end, ZoneOffset.ofHours(7)).toString());
        return id;
    }

    @Test
    void missedWorkoutStartedAfterGenerationCannotBeApplied() {
        TestFixture f = createFixture(false);
        UUID source = missedOccurrence(f);
        var proposal = rescheduleService.generateReschedule(f.studentId(),
                new WorkoutRescheduleApiRequest(f.studentId(), source, 0L, "missed-before-start", "Missed", null));
        var actual = execution.start(new WorkoutExecutionCommandUseCase.StartCommand(source, f.studentId(), 0, "late-start"));
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(),
                new AcceptRescheduleApiRequest("after-start", null, 1, 0L, null, null)))
                .isInstanceOf(AiRunLifecycleConflictException.class);
        assertThat(recommendationRepository.findById(proposal.recommendationId()).orElseThrow().status())
                .isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                Integer.class, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT performed_start_at FROM fitness.workout_session_logs WHERE id=?",
                Timestamp.class, actual.id()).toInstant()).isEqualTo(actual.performedStartedAt());
    }

    @Test
    void generationRejectsUnfinishedWindowUnknownEndAndStartedSources() {
        TestFixture f = createFixture(false);
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        for (Instant end : new Instant[]{Instant.now().plus(1, ChronoUnit.HOURS), null}) {
            UUID source = timedOccurrence(f, start, end);
            assertThatThrownBy(() -> rescheduleService.generateReschedule(f.studentId(),
                    new WorkoutRescheduleApiRequest(f.studentId(), source, 0L, "invalid-" + source, "Missed", null)))
                    .isInstanceOf(AiRunLifecycleConflictException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendations WHERE target_entity_id=?",
                    Integer.class, source)).isZero();
        }
        UUID source = missedOccurrence(f);
        execution.start(new WorkoutExecutionCommandUseCase.StartCommand(source, f.studentId(), 0, "started-before-generation"));
        assertThatThrownBy(() -> rescheduleService.generateReschedule(f.studentId(),
                new WorkoutRescheduleApiRequest(f.studentId(), source, 1L, "blocked-started", "Missed", null)))
                .isInstanceOf(AiRunLifecycleConflictException.class);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("T06/T25: Idempotent replay returns cached receipt; differing parameters throw conflict")
    void idempotentAcceptRetry_replaysReceipt_T06_T25(boolean missed) {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = missed ? missedOccurrence(f) : createOccurrence(f, baseStart);

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Concurrent double accept with different keys: exactly one succeeds, other gets conflict")
    void concurrentDoubleAccept_differentKeys_onlyOneSucceeds(boolean missed) throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = missed ? missedOccurrence(f) : createOccurrence(f, baseStart);

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Concurrent accept and reject: serialized consistently, exactly one transition wins")
    void concurrentAcceptAndReject_serializedConsistently(boolean missed) throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = missed ? missedOccurrence(f) : createOccurrence(f, baseStart);

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

    private UUID insertConfirmedAppointment(UUID studentId, UUID trainerId, UUID periodId, UUID plannedWorkoutId, Instant startAt, Instant endAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.appointments
                (id, student_id, trainer_id, coaching_period_id, planned_workout_id, start_at, end_at, display_timezone,
                 local_start, local_end, start_utc_offset, end_utc_offset, guarded_v2, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Asia/Ho_Chi_Minh',
                 ?::timestamp, ?::timestamp, '+07:00', '+07:00', true, 'CONFIRMED', ?)
                """, id, studentId, trainerId, periodId, plannedWorkoutId,
                Timestamp.from(startAt), Timestamp.from(endAt),
                LocalDateTime.ofInstant(startAt, ZoneOffset.ofHours(7)).toString(),
                LocalDateTime.ofInstant(endAt, ZoneOffset.ofHours(7)).toString(),
                studentId);
        return id;
    }

    @Test
    @DisplayName("COACH_REQUIRED session with confirmed appointment accepts and transfers link to successor")
    void coachRequiredSession_withConfirmedAppointment_acceptsAndTransfersLink() {
        TestFixture f = createFixture(true);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");
        UUID periodId = jdbc.queryForObject("SELECT id FROM fitness.coaching_periods WHERE student_id=?", UUID.class, f.studentId());

        LocalDate destDate = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(3);
        LocalDateTime baseStart = destDate.minusDays(1).atTime(9, 0);
        Instant plannedStartInstant = baseStart.toInstant(ZoneOffset.ofHours(7));
        Instant plannedEndInstant = plannedStartInstant.plus(1, ChronoUnit.HOURS);
        UUID occurrenceId = timedOccurrence(f, plannedStartInstant, plannedEndInstant);

        // Destination slot generated by provider is destDate at 09:00 - 10:00 (+07:00)
        Instant appStart = destDate.atTime(9, 0).toInstant(ZoneOffset.ofHours(7));
        Instant appEnd = appStart.plus(1, ChronoUnit.HOURS);

        UUID appointmentId = insertConfirmedAppointment(f.studentId(), trainerId, periodId, occurrenceId, appStart, appEnd);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-coach-req", "Need reschedule with PT", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        assertThat(proposal.structuredPayload().path("reschedule").path("supervisionRequirement").asText())
                .isEqualTo("COACH_REQUIRED");

        // Trainer accepts proposal
        var trainerAcceptReq = new AcceptRescheduleApiRequest("trainer-accept-coach-req", "PT approved new slot", 1, 0L, null, null);
        AcceptRescheduleApiResponse acceptResp = rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), trainerAcceptReq);

        assertThat(acceptResp.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        UUID replacementId = acceptResp.resultingEntityId();
        assertThat(replacementId).isNotNull();

        // Old occurrence is RESCHEDULED
        String oldStatus = jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, occurrenceId);
        assertThat(oldStatus).isEqualTo("RESCHEDULED");

        // Appointment link transferred to replacement occurrence
        UUID linkedWorkout = jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?", UUID.class, appointmentId);
        assertThat(linkedWorkout).isEqualTo(replacementId);

        // Audit log exists
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE action='AI_WORKOUT_RESCHEDULE_APPLIED' AND target_id=?",
                Integer.class, replacementId);
        assertThat(auditCount).isOne();
    }

    @Test
    @DisplayName("COACH_REQUIRED session without linked appointment aborts accept with conflict (409)")
    void coachRequiredSession_withoutAppointment_abortsWithConflict() {
        TestFixture f = createFixture(true);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");

        LocalDate destDate = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(3);
        LocalDateTime baseStart = destDate.minusDays(1).atTime(9, 0);
        Instant plannedStartInstant = baseStart.toInstant(ZoneOffset.ofHours(7));
        Instant plannedEndInstant = plannedStartInstant.plus(1, ChronoUnit.HOURS);
        UUID occurrenceId = timedOccurrence(f, plannedStartInstant, plannedEndInstant);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-coach-unsupported", "Reschedule no PT", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        var trainerAcceptReq = new AcceptRescheduleApiRequest("trainer-accept-no-pt", "Try accepting without appointment", 1, 0L, null, null);
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), trainerAcceptReq))
                .isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code")
                .isEqualTo("WORKOUT_SCHEDULE_COACH_REQUIRED_RESCHEDULE_UNSUPPORTED");

        // Original occurrence unchanged
        String oldStatus = jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, occurrenceId);
        assertThat(oldStatus).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("Linked appointment changed after generation aborts accept with lifecycle conflict (409)")
    void linkedAppointmentChangedAfterGeneration_abortsAccept() {
        TestFixture f = createFixture(true);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");
        UUID periodId = jdbc.queryForObject("SELECT id FROM fitness.coaching_periods WHERE student_id=?", UUID.class, f.studentId());

        LocalDate destDate = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(3);
        LocalDateTime baseStart = destDate.minusDays(1).atTime(9, 0);
        Instant plannedStartInstant = baseStart.toInstant(ZoneOffset.ofHours(7));
        Instant plannedEndInstant = plannedStartInstant.plus(1, ChronoUnit.HOURS);
        UUID occurrenceId = timedOccurrence(f, plannedStartInstant, plannedEndInstant);

        Instant appStart = destDate.atTime(9, 0).toInstant(ZoneOffset.ofHours(7));
        Instant appEnd = appStart.plus(1, ChronoUnit.HOURS);
        UUID appointmentId = insertConfirmedAppointment(f.studentId(), trainerId, periodId, occurrenceId, appStart, appEnd);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-app-change", "Change test", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        // Appointment changed: version bumped or status changed to CANCELLED
        jdbc.update("UPDATE fitness.appointments SET status='CANCELLED', version=version+1 WHERE id=?", appointmentId);

        var trainerAcceptReq = new AcceptRescheduleApiRequest("trainer-accept-app-changed", "Apply after app changed", 1, 0L, null, null);
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), trainerAcceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Linked appointment has changed");
    }

    @Test
    @DisplayName("Workout schedule overlap on destination slot aborts accept with conflict (409)")
    void workoutScheduleConflictOnDestinationSlot_abortsAcceptWithOverlapConflict() {
        TestFixture f = createFixture(false);
        LocalDate destDate = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(3);
        LocalDateTime baseStart = destDate.minusDays(1).atTime(9, 0);
        Instant plannedStartInstant = baseStart.toInstant(ZoneOffset.ofHours(7));
        Instant plannedEndInstant = plannedStartInstant.plus(1, ChronoUnit.HOURS);
        UUID occurrenceId = timedOccurrence(f, plannedStartInstant, plannedEndInstant);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-overlap", "Will overlap", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        // Before accept, another workout is scheduled overlapping the destination slot (08:30 - 09:30 vs 09:00 - 10:00)
        Instant overlapStart = destDate.atTime(8, 30).toInstant(ZoneOffset.ofHours(7));
        Instant overlapEnd = overlapStart.plus(1, ChronoUnit.HOURS);
        timedOccurrence(f, overlapStart, overlapEnd);

        var acceptReq = new AcceptRescheduleApiRequest("accept-key-overlap", "Accept overlapping", 1, 0L, null, null);
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq))
                .isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code")
                .isEqualTo("WORKOUT_SCHEDULE_OVERLAP");

        // Original occurrence unchanged
        String oldStatus = jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, occurrenceId);
        assertThat(oldStatus).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("Audit failure during accept rolls back domain workout change and recommendation decision atomically")
    void acceptReschedule_whenAuditFails_rollsBackEntireTransaction() {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-audit-fail", "Audit fail test", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        // Install temporary trigger on fitness.audit_logs to simulate failure during mandatory audit recording
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION fitness.test_fail_reschedule_audit_trigger() RETURNS trigger AS $$
            BEGIN
                IF NEW.action = 'AI_WORKOUT_RESCHEDULE_APPLIED' THEN
                    RAISE EXCEPTION 'Simulated mandatory audit failure';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
        """);
        jdbc.execute("""
            CREATE TRIGGER trg_test_fail_reschedule_audit
            BEFORE INSERT ON fitness.audit_logs
            FOR EACH ROW EXECUTE FUNCTION fitness.test_fail_reschedule_audit_trigger();
        """);

        try {
            var acceptReq = new AcceptRescheduleApiRequest("accept-audit-fail", "Should rollback", 1, 0L, null, null);
            assertThatThrownBy(() -> rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq))
                    .hasMessageContaining("Simulated mandatory audit failure");

            // Recommendation must still be PENDING (not partially APPLIED)
            AiRecommendation recAfter = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
            assertThat(recAfter.status()).isEqualTo(AiRecommendationStatus.PENDING);

            // Planned workout must still be SCHEDULED (not RESCHEDULED)
            String status = jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, occurrenceId);
            assertThat(status).isEqualTo("SCHEDULED");

            // No replacement occurrence created in database
            Integer replacementCount = jdbc.queryForObject(
                    "SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?",
                    Integer.class, occurrenceId);
            assertThat(replacementCount).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_test_fail_reschedule_audit ON fitness.audit_logs");
            jdbc.execute("DROP FUNCTION IF EXISTS fitness.test_fail_reschedule_audit_trigger()");
        }
    }

    @Test
    @DisplayName("Concurrent GET and Accept near expiry: GET does not overwrite APPLIED with EXPIRED")
    void getReschedule_whenExpiringConcurrentlyWithAccept_doesNotOverwriteAppliedStatus() throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-get-race", "Race test", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        // Accept the recommendation
        var acceptReq = new AcceptRescheduleApiRequest("accept-race-key", "Accept before get expires", 1, 0L, null, null);
        AcceptRescheduleApiResponse acceptResp = rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq);
        assertThat(acceptResp.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        // Even if recommendation has an expired expires_at timestamp, calling getReschedule MUST NOT overwrite APPLIED
        jdbc.update("UPDATE fitness.ai_recommendations SET expires_at=clock_timestamp() - interval '10 seconds' WHERE id=?", proposal.recommendationId());

        AiRecommendation rec = rescheduleService.getReschedule(f.studentId(), proposal.recommendationId());
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.APPLIED);
    }

    @Test
    @DisplayName("Concurrent GET near expiry and Accept race resolves to consistent terminal state without status corruption")
    void concurrentGetAndAcceptNearExpiry_resolvesToConsistentTerminalState() throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-key-get-accept-race", "Race test concurrent", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        // Set expires_at to 10 ms in the future to simulate execution right on the expiry boundary
        jdbc.update("UPDATE fitness.ai_recommendations SET expires_at=clock_timestamp() + interval '5 milliseconds' WHERE id=?",
                proposal.recommendationId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        java.util.concurrent.atomic.AtomicReference<AiRecommendation> getResult = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<AcceptRescheduleApiResponse> acceptResult = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> acceptError = new java.util.concurrent.atomic.AtomicReference<>();

        pool.submit(() -> {
            try {
                startLatch.await();
                Thread.sleep(10); // sleep past expiry
                getResult.set(rescheduleService.getReschedule(f.studentId(), proposal.recommendationId()));
            } catch (Throwable t) {
                // ignore
            } finally {
                doneLatch.countDown();
            }
        });

        pool.submit(() -> {
            try {
                startLatch.await();
                var acceptReq = new AcceptRescheduleApiRequest("accept-key-race-thread", "Note race", 1, 0L, null, null);
                acceptResult.set(rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq));
            } catch (Throwable t) {
                acceptError.set(t);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        pool.shutdownNow();

        // Final recommendation in DB must be EITHER APPLIED or EXPIRED, never mixed/corrupted
        AiRecommendation finalRec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(finalRec.status()).isIn(AiRecommendationStatus.APPLIED, AiRecommendationStatus.EXPIRED);

        // If accept succeeded, final DB status MUST be APPLIED (GET cannot overwrite it)
        if (acceptResult.get() != null) {
            assertThat(finalRec.status()).isEqualTo(AiRecommendationStatus.APPLIED);
            if (getResult.get() != null) {
                assertThat(getResult.get().status()).isEqualTo(AiRecommendationStatus.APPLIED);
            }
        } else {
            // Accept failed because it expired first
            assertThat(acceptError.get()).isInstanceOf(AiRunLifecycleConflictException.class);
            assertThat(finalRec.status()).isEqualTo(AiRecommendationStatus.EXPIRED);
        }
    }

    @Test
    @DisplayName("T04: In-flight revocation of MANAGE permission fails delivery, commits FAILED run with null output, and prevents recommendation/audit")
    void inFlightRevocation_managePermissionRevoked_failsDeliveryAndPersistsFailedRun_T04() throws Exception {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");

        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(3).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        CountDownLatch providerStartedLatch = new CountDownLatch(1);
        CountDownLatch resumeProviderLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            providerStartedLatch.countDown();
            resumeProviderLatch.await(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(aiServiceClient).generateRescheduleJson(any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var generateReq = new WorkoutRescheduleApiRequest(
                    f.studentId(), occurrenceId, 0L, "in-flight-manage-revoked", "Conflict", null
            );

            Future<WorkoutRescheduleApiResponse> future = executor.submit(() ->
                    rescheduleService.generateReschedule(trainerId, generateReq)
            );

            boolean reached = providerStartedLatch.await(5, TimeUnit.SECONDS);
            assertThat(reached).isTrue();

            // While provider is computing, student revokes MANAGE permission and grants VIEW only
            jdbc.update("""
                    UPDATE fitness.data_sharing_permissions
                    SET revoked_at = clock_timestamp(), revoke_reason = 'STUDENT_REVOKED', version = version + 1
                    WHERE student_id = ? AND data_scope = 'WORKOUT_PLAN'::fitness.data_scope_code AND revoked_at IS NULL
                    """, f.studentId());
            jdbc.update("""
                    INSERT INTO fitness.data_sharing_permissions
                    (id, relationship_id, student_id, trainer_id, data_scope, decision, access_level, valid_from, granted_by)
                    SELECT gen_random_uuid(), relationship_id, student_id, trainer_id, 'WORKOUT_PLAN'::fitness.data_scope_code,
                           'ALLOW'::fitness.permission_decision, 'VIEW'::fitness.data_access_level, revoked_at, granted_by
                    FROM fitness.data_sharing_permissions
                    WHERE student_id = ? AND data_scope = 'WORKOUT_PLAN'::fitness.data_scope_code AND revoked_at IS NOT NULL
                    ORDER BY version DESC LIMIT 1
                    """, f.studentId());

            resumeProviderLatch.countDown();

            ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)
            );
            assertThat(ex.getCause())
                    .isInstanceOf(AiRunAccessDeniedException.class)
                    .hasMessageContaining("MANAGE permission for WORKOUT_PLAN was revoked during generation");

            // Verify in PostgreSQL that AI Run status is FAILED, error_code is AUTHORITY_REVOKED, and output_payload is NULL
            Map<String, Object> runRow = jdbc.queryForMap(
                    "SELECT status::text as status, error_code, error_message, output_payload FROM fitness.ai_runs WHERE requested_by = ? ORDER BY created_at DESC LIMIT 1",
                    trainerId
            );
            assertThat(runRow.get("status")).isEqualTo("FAILED");
            assertThat(runRow.get("error_code")).isEqualTo("AUTHORITY_REVOKED");
            assertThat(runRow.get("output_payload")).isNull();

            // Verify NO recommendation was created and NO proposed audit was logged
            Integer recCount = jdbc.queryForObject(
                    "SELECT count(*) FROM fitness.ai_recommendations WHERE last_modified_by = ?",
                    Integer.class, trainerId
            );
            assertThat(recCount).isZero();

            Integer auditCount = jdbc.queryForObject(
                    "SELECT count(*) FROM fitness.audit_logs WHERE action = 'AI_WORKOUT_RESCHEDULE_PROPOSED' AND actor_user_id = ?",
                    Integer.class, trainerId
            );
            assertThat(auditCount).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("T04: In-flight revocation of VIEW permission fails delivery, commits FAILED run with null output, and prevents recommendation/audit")
    void inFlightRevocation_viewPermissionRevoked_failsDeliveryAndPersistsFailedRun_T04() throws Exception {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");

        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(3).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        CountDownLatch providerStartedLatch = new CountDownLatch(1);
        CountDownLatch resumeProviderLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            providerStartedLatch.countDown();
            resumeProviderLatch.await(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(aiServiceClient).generateRescheduleJson(any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var generateReq = new WorkoutRescheduleApiRequest(
                    f.studentId(), occurrenceId, 0L, "in-flight-view-revoked", "Conflict", null
            );

            Future<WorkoutRescheduleApiResponse> future = executor.submit(() ->
                    rescheduleService.generateReschedule(trainerId, generateReq)
            );

            boolean reached = providerStartedLatch.await(5, TimeUnit.SECONDS);
            assertThat(reached).isTrue();

            // While provider is computing, student revokes permission completely
            jdbc.update("""
                    UPDATE fitness.data_sharing_permissions
                    SET revoked_at = clock_timestamp(), revoke_reason = 'STUDENT_REVOKED', version = version + 1
                    WHERE student_id = ? AND data_scope = 'WORKOUT_PLAN'::fitness.data_scope_code
                    """, f.studentId());

            resumeProviderLatch.countDown();

            ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)
            );
            assertThat(ex.getCause())
                    .isInstanceOf(AiRunAccessDeniedException.class);

            // Verify in PostgreSQL that AI Run status is FAILED, error_code is AUTHORITY_REVOKED, and output_payload is NULL
            Map<String, Object> runRow = jdbc.queryForMap(
                    "SELECT status::text as status, error_code, error_message, output_payload FROM fitness.ai_runs WHERE requested_by = ? ORDER BY created_at DESC LIMIT 1",
                    trainerId
            );
            assertThat(runRow.get("status")).isEqualTo("FAILED");
            assertThat(runRow.get("error_code")).isEqualTo("AUTHORITY_REVOKED");
            assertThat(runRow.get("output_payload")).isNull();

            Integer recCount = jdbc.queryForObject(
                    "SELECT count(*) FROM fitness.ai_recommendations WHERE last_modified_by = ?",
                    Integer.class, trainerId
            );
            assertThat(recCount).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("T04: In-flight account deactivation fails delivery and commits FAILED run with null output")
    void inFlightRevocation_accountDeactivated_failsDeliveryAndPersistsFailedRun_T04() throws Exception {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");

        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(3).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        CountDownLatch providerStartedLatch = new CountDownLatch(1);
        CountDownLatch resumeProviderLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            providerStartedLatch.countDown();
            resumeProviderLatch.await(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(aiServiceClient).generateRescheduleJson(any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var generateReq = new WorkoutRescheduleApiRequest(
                    f.studentId(), occurrenceId, 0L, "in-flight-deactivated", "Conflict", null
            );

            Future<WorkoutRescheduleApiResponse> future = executor.submit(() ->
                    rescheduleService.generateReschedule(trainerId, generateReq)
            );

            boolean reached = providerStartedLatch.await(5, TimeUnit.SECONDS);
            assertThat(reached).isTrue();

            // Admin deactivates trainer account (suspends user account)
            jdbc.update("UPDATE fitness.users SET status = 'SUSPENDED' WHERE id = ?", trainerId);

            resumeProviderLatch.countDown();

            ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)
            );
            assertThat(ex.getCause())
                    .isInstanceOf(AiRunAccessDeniedException.class)
                    .hasMessageContaining("Actor account is no longer active");

            Map<String, Object> runRow = jdbc.queryForMap(
                    "SELECT status::text as status, error_code, error_message, output_payload FROM fitness.ai_runs WHERE requested_by = ? ORDER BY created_at DESC LIMIT 1",
                    trainerId
            );
            assertThat(runRow.get("status")).isEqualTo("FAILED");
            assertThat(runRow.get("error_code")).isEqualTo("AUTHORITY_REVOKED");
            assertThat(runRow.get("output_payload")).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("T04: In-flight coaching period termination fails delivery and commits FAILED run with null output")
    void inFlightRevocation_coachingPeriodEnded_failsDeliveryAndPersistsFailedRun_T04() throws Exception {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");

        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(3).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        CountDownLatch providerStartedLatch = new CountDownLatch(1);
        CountDownLatch resumeProviderLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            providerStartedLatch.countDown();
            resumeProviderLatch.await(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(aiServiceClient).generateRescheduleJson(any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var generateReq = new WorkoutRescheduleApiRequest(
                    f.studentId(), occurrenceId, 0L, "in-flight-period-ended", "Conflict", null
            );

            Future<WorkoutRescheduleApiResponse> future = executor.submit(() ->
                    rescheduleService.generateReschedule(trainerId, generateReq)
            );

            boolean reached = providerStartedLatch.await(5, TimeUnit.SECONDS);
            assertThat(reached).isTrue();

            // Coaching period is terminated
            jdbc.update("UPDATE fitness.coaching_periods SET ended_at = clock_timestamp() WHERE student_id = ?", f.studentId());

            resumeProviderLatch.countDown();

            ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)
            );
            assertThat(ex.getCause())
                    .isInstanceOf(AiRunAccessDeniedException.class)
                    .hasMessageContaining("relationship or period was revoked or changed");

            Map<String, Object> runRow = jdbc.queryForMap(
                    "SELECT status::text as status, error_code, error_message, output_payload FROM fitness.ai_runs WHERE requested_by = ? ORDER BY created_at DESC LIMIT 1",
                    trainerId
            );
            assertThat(runRow.get("status")).isEqualTo("FAILED");
            assertThat(runRow.get("error_code")).isEqualTo("AUTHORITY_REVOKED");
            assertThat(runRow.get("output_payload")).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("T04: Readback proposal guard - losing MANAGE retains VIEW readback; losing VIEW blocks readback")
    void readbackGuards_distinguishManageAndDismissView_T04() {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");

        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(3).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "readback-guard-key", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);
        assertThat(proposal.status()).isEqualTo(AiRecommendationStatus.PENDING);

        // Scenario 1: Student downgrades trainer from MANAGE to VIEW
        jdbc.update("""
                UPDATE fitness.data_sharing_permissions
                SET revoked_at = clock_timestamp(), revoke_reason = 'STUDENT_REVOKED', version = version + 1
                WHERE student_id = ? AND data_scope = 'WORKOUT_PLAN'::fitness.data_scope_code AND revoked_at IS NULL
                """, f.studentId());
        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id, relationship_id, student_id, trainer_id, data_scope, decision, access_level, valid_from, granted_by)
                SELECT gen_random_uuid(), relationship_id, student_id, trainer_id, 'WORKOUT_PLAN'::fitness.data_scope_code,
                       'ALLOW'::fitness.permission_decision, 'VIEW'::fitness.data_access_level, revoked_at, granted_by
                FROM fitness.data_sharing_permissions
                WHERE student_id = ? AND data_scope = 'WORKOUT_PLAN'::fitness.data_scope_code AND revoked_at IS NOT NULL
                ORDER BY version DESC LIMIT 1
                """, f.studentId());

        // Trainer STILL can read back proposal via getReschedule
        AiRecommendation readback = rescheduleService.getReschedule(trainerId, proposal.recommendationId());
        assertThat(readback).isNotNull();
        assertThat(readback.id()).isEqualTo(proposal.recommendationId());

        // But Trainer CANNOT accept because MANAGE is missing
        var trainerAcceptReq = new AcceptRescheduleApiRequest(
                "trainer-accept-no-manage", "Trying to apply with VIEW only", 1, 0L, null, null
        );
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), trainerAcceptReq))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("authority is no longer valid");

        // Scenario 2: Student revokes VIEW completely
        jdbc.update("""
                UPDATE fitness.data_sharing_permissions
                SET revoked_at = clock_timestamp(), revoke_reason = 'STUDENT_REVOKED', version = version + 1
                WHERE student_id = ? AND data_scope = 'WORKOUT_PLAN'::fitness.data_scope_code AND revoked_at IS NULL
                """, f.studentId());

        // Trainer CANNOT even read back proposal (403 Access Denied)
        assertThatThrownBy(() -> rescheduleService.getReschedule(trainerId, proposal.recommendationId()))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Trainer VIEW permission for WORKOUT_PLAN is revoked");
    }

    @Test
    @DisplayName("F05-BL-01: Legacy snapshot missing daysSinceLastWorkout and continuityWatermark is handled gracefully during accept")
    void legacySnapshot_missingNewFields_handledGracefullyDuringAccept() throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-legacy-snapshot", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        // Fetch original snapshot and remove daysSinceLastWorkout and continuityWatermark fields to simulate legacy DB record
        String originalSnapshotJson = jdbc.queryForObject(
                "SELECT source_snapshot FROM fitness.ai_recommendations WHERE id = ?",
                String.class, proposal.recommendationId()
        );
        tools.jackson.databind.node.ObjectNode snapshotNode = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(originalSnapshotJson);
        snapshotNode.remove("daysSinceLastWorkout");
        snapshotNode.remove("continuityWatermark");
        String legacySnapshotJson = objectMapper.writeValueAsString(snapshotNode);

        // Update database with legacy snapshot JSON
        jdbc.update("UPDATE fitness.ai_recommendations SET source_snapshot = ?::jsonb WHERE id = ?",
                legacySnapshotJson, proposal.recommendationId());

        // Verify acceptReschedule succeeds without error
        var acceptReq = new AcceptRescheduleApiRequest("accept-legacy-snap", "Accepting legacy snapshot", 1, 0L, null, null);
        AcceptRescheduleApiResponse acceptRes = rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), acceptReq);
        assertThat(acceptRes.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(acceptRes.resultingEntityId()).isNotNull();
    }

    @Test
    @DisplayName("F05-BL-01: Continuity and provenance (including daysSinceLastWorkout and continuityWatermark) are persisted in source snapshot and conveyed in usedData")
    void continuityAndProvenance_persistedInSnapshotAndPayload_T01() throws Exception {
        TestFixture f = createFixture(false);

        // Seed a completed workout session log 3 days ago to establish actual continuity
        Instant pastStart = Instant.now().minus(3, ChronoUnit.DAYS);
        Instant pastEnd = pastStart.plus(1, ChronoUnit.HOURS);
        UUID loggedOccurrence = timedOccurrence(f, pastStart, pastEnd);
        jdbc.update("""
                INSERT INTO fitness.workout_session_logs
                (id, student_id, planned_workout_id, workout_plan_version_id, performed_start_at, performed_end_at, status, logged_by)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'COMPLETED'::fitness.actual_workout_status, ?)
                """, f.studentId(), loggedOccurrence, f.versionId(), Timestamp.from(pastStart), Timestamp.from(pastEnd), f.studentId());

        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-provenance-test", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        // Check source_snapshot in database
        String snapshotJson = jdbc.queryForObject(
                "SELECT source_snapshot FROM fitness.ai_recommendations WHERE id = ?",
                String.class, proposal.recommendationId()
        );
        tools.jackson.databind.JsonNode snap = objectMapper.readTree(snapshotJson);
        assertThat(snap.has("continuityState")).isTrue();
        assertThat(snap.get("continuityState").asText()).isEqualTo("RECENT_ACTIVITY_OBSERVED");
        assertThat(snap.has("daysSinceLastWorkout")).isTrue();
        assertThat(snap.get("daysSinceLastWorkout").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(snap.has("continuityWatermark")).isTrue();
        assertThat(snap.has("asOf")).isTrue();
        assertThat(snap.has("timezone")).isTrue();

        // Check structuredPayload in database
        tools.jackson.databind.JsonNode payload = proposal.structuredPayload();
        tools.jackson.databind.JsonNode usedData = payload.path("usedData");
        assertThat(usedData.isArray()).isTrue();
        boolean hasContinuity = false;
        for (tools.jackson.databind.JsonNode item : usedData) {
            if (item.asText().startsWith("continuityState:")) {
                hasContinuity = true;
                break;
            }
        }
        assertThat(hasContinuity).isTrue();
    }

    @Test
    @DisplayName("Transaction Refactor: Mandatory audit failure during generation atomically rolls back proposal and commits FAILED run")
    void generateReschedule_whenAuditFails_rollsBackProposalAndMarksRunFailed() {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        // Install temporary trigger on fitness.audit_logs to simulate failure during proposal audit recording
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION fitness.test_fail_reschedule_proposed_audit_trigger() RETURNS trigger AS $$
            BEGIN
                IF NEW.action = 'AI_WORKOUT_RESCHEDULE_PROPOSED' THEN
                    RAISE EXCEPTION 'Simulated proposal audit failure';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
        """);
        jdbc.execute("""
            CREATE TRIGGER trg_test_fail_reschedule_proposed_audit
            BEFORE INSERT ON fitness.audit_logs
            FOR EACH ROW EXECUTE FUNCTION fitness.test_fail_reschedule_proposed_audit_trigger();
        """);

        try {
            var generateReq = new WorkoutRescheduleApiRequest(
                    f.studentId(), occurrenceId, 0L, "gen-audit-fail-key", "Audit fail proposal test", null
            );
            assertThatThrownBy(() -> rescheduleService.generateReschedule(f.studentId(), generateReq))
                    .hasMessageContaining("Simulated proposal audit failure");

            // Verify in PostgreSQL that NO recommendation was persisted
            Integer recCount = jdbc.queryForObject(
                    "SELECT count(*) FROM fitness.ai_recommendations WHERE target_entity_id = ?",
                    Integer.class, occurrenceId
            );
            assertThat(recCount).isZero();

            // Verify that the AI Run was recorded as FAILED with error_code PERSISTENCE_FAILED, never left in RUNNING or SUCCEEDED
            Map<String, Object> runRow = jdbc.queryForMap(
                    "SELECT status::text as status, error_code, error_message, output_payload FROM fitness.ai_runs WHERE student_id = ? ORDER BY created_at DESC LIMIT 1",
                    f.studentId()
            );
            assertThat(runRow.get("status")).isEqualTo("FAILED");
            assertThat(runRow.get("error_code")).isEqualTo("PERSISTENCE_FAILED");
            assertThat(runRow.get("output_payload")).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_test_fail_reschedule_proposed_audit ON fitness.audit_logs");
            jdbc.execute("DROP FUNCTION IF EXISTS fitness.test_fail_reschedule_proposed_audit_trigger()");
        }
    }

    private record Bl03Fixture(UUID student, UUID trainer, UUID period, UUID occurrence, UUID appointment, Instant start) {}

    private Bl03Fixture linkedBl03Fixture(boolean coachRequired) {
        var f = createFixture(coachRequired);
        UUID trainer = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainer, "HUMAN_COACH");
        UUID period = jdbc.queryForObject("SELECT id FROM fitness.coaching_periods WHERE student_id=?", UUID.class, f.studentId());
        Instant start = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(3).atTime(9, 0).toInstant(ZoneOffset.ofHours(7));
        UUID occurrence = timedOccurrence(f, start, start.plus(1, ChronoUnit.HOURS));
        UUID appointment = insertConfirmedAppointment(f.studentId(), trainer, period, occurrence, start, start.plus(1, ChronoUnit.HOURS));
        return new Bl03Fixture(f.studentId(), trainer, period, occurrence, appointment, start);
    }

    private AppointmentService.CommandResult requestBl03(Bl03Fixture f, Instant start, Instant end, long version) {
        return appointmentService.requestReschedule(new AppointmentService.RescheduleCommand(f.student(), f.appointment(), version,
                new AppointmentService.LocalInterval(LocalDateTime.ofInstant(start, ZoneOffset.ofHours(7)),
                        LocalDateTime.ofInstant(end, ZoneOffset.ofHours(7)), "Asia/Ho_Chi_Minh", "+07:00", "+07:00"),
                "Coordinate Appointment separately", UUID.randomUUID()));
    }

    private void decideBl03(Bl03Fixture f, UUID request, long version) {
        appointmentService.decideReschedule(new AppointmentService.RescheduleDecisionCommand(
                f.trainer(), request, "ACCEPT", 0L, version, UUID.randomUUID()));
    }

    private WorkoutRescheduleApiResponse generateBl03(Bl03Fixture f) {
        return rescheduleService.generateReschedule(f.student(), new WorkoutRescheduleApiRequest(
                f.student(), f.occurrence(), 0L, UUID.randomUUID().toString(), "Review current confirmed Appointment", null));
    }

    private AcceptRescheduleApiRequest acceptBl03(Bl03Fixture f, long version) {
        return new AcceptRescheduleApiRequest(UUID.randomUUID().toString(), "Reviewed", 1, 0L, version, f.appointment());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "EXPIRED", "CANCELLED"})
    void f05Bl03_unacceptedRequests_doNotAuthorizeProposedSlot(String status) {
        var f = linkedBl03Fixture(false);
        var change = requestBl03(f, f.start().plus(2, ChronoUnit.DAYS), f.start().plus(2, ChronoUnit.DAYS).plusSeconds(3600), 0L);
        if ("REJECTED".equals(status)) {
            appointmentService.decideReschedule(new AppointmentService.RescheduleDecisionCommand(
                    f.trainer(), change.request().requestId(), "REJECT", 0L, 0L, UUID.randomUUID()));
        } else if (!"PENDING".equals(status)) {
            // V45 supports only PENDING/ACCEPTED/REJECTED: unsupported states cannot grant consent.
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fitness.appointment_requests_v2 SET status=?,decided_at=clock_timestamp(),decided_by=?,version=version+1 WHERE id=?",
                    status, f.student(), change.request().requestId()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        var proposal = generateBl03(f);
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(f.trainer(), proposal.recommendationId(), acceptBl03(f, 0L)))
                .isInstanceOf(WorkoutScheduleFailure.class);
        assertThat(jdbc.queryForObject("SELECT start_at FROM fitness.appointments WHERE id=?", Timestamp.class, f.appointment()).toInstant())
                .isEqualTo(f.start());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id=?", Integer.class, f.occurrence()))
                .isZero();
    }

    @Test
    void f05Bl03_coachRequired_disjointMoveBlocked_butContainingChangeAndFreshProposalWork() {
        var f = linkedBl03Fixture(true);
        var disjoint = requestBl03(f, f.start().plus(2, ChronoUnit.DAYS), f.start().plus(2, ChronoUnit.DAYS).plusSeconds(3600), 0L);
        assertThatThrownBy(() -> decideBl03(f, disjoint.request().requestId(), 0L))
                .isInstanceOf(com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentFailure.class)
                .hasMessageContaining("APPOINTMENT_COACH_REQUIRED_DEPENDENCY");
        appointmentService.decideReschedule(new AppointmentService.RescheduleDecisionCommand(
                f.trainer(), disjoint.request().requestId(), "REJECT", 0L, 0L, UUID.randomUUID()));
        var containing = requestBl03(f, f.start().minusSeconds(3600), f.start().plusSeconds(3600), 0L);
        decideBl03(f, containing.request().requestId(), 0L);
        var proposal = generateBl03(f);
        assertThat(proposal.structuredPayload().path("reschedule").path("supervisionRequirement").asText()).isEqualTo("COACH_REQUIRED");
        var applied = rescheduleService.acceptReschedule(f.trainer(), proposal.recommendationId(), acceptBl03(f, 1L));
        assertThat(applied.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?", UUID.class, f.appointment()))
                .isEqualTo(applied.resultingEntityId());
        assertThat(jdbc.queryForObject("SELECT planned_end_at-planned_start_at=interval '1 hour' FROM fitness.planned_workouts WHERE id=?",
                Boolean.class, applied.resultingEntityId())).isTrue();
    }

    @Test
    void f05Bl03_cancelledAppointment_blocksApplyWithoutCancellingWorkout() {
        var f = linkedBl03Fixture(false);
        var proposal = generateBl03(f);
        appointmentService.cancel(new AppointmentService.CancelCommand(f.student(), f.appointment(), 0L, "Cancelled meeting", UUID.randomUUID()));
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(f.trainer(), proposal.recommendationId(), acceptBl03(f, 0L)))
                .isInstanceOf(AiRunLifecycleConflictException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, f.occurrence())).isEqualTo("SCHEDULED");
        assertThat(recommendationRepository.findById(proposal.recommendationId()).orElseThrow().status()).isEqualTo(AiRecommendationStatus.PENDING);
    }

    @Test
    void f05Bl03_appointmentDecisionAndF05ApplyRace_onlyOneCommits() throws Exception {
        var f = linkedBl03Fixture(false);
        Instant firstStart = f.start().plus(2, ChronoUnit.DAYS);
        var first = requestBl03(f, firstStart, firstStart.plusSeconds(3600), 0L);
        decideBl03(f, first.request().requestId(), 0L);
        var proposal = generateBl03(f);
        var second = requestBl03(f, firstStart.plus(1, ChronoUnit.DAYS), firstStart.plus(1, ChronoUnit.DAYS).plusSeconds(3600), 1L);
        var gate = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var apply = executor.submit(() -> {
                ready.countDown(); gate.await();
                try { rescheduleService.acceptReschedule(f.trainer(), proposal.recommendationId(), acceptBl03(f, 1L)); return true; }
                catch (AiRunLifecycleConflictException | WorkoutScheduleFailure expected) { return false; }
            });
            var change = executor.submit(() -> {
                ready.countDown(); gate.await();
                try { decideBl03(f, second.request().requestId(), 1L); return true; }
                catch (com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentFailure expected) { return false; }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            gate.countDown();
            boolean applied = apply.get(15, TimeUnit.SECONDS);
            boolean changed = change.get(15, TimeUnit.SECONDS);
            assertThat(applied ^ changed).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id=?",
                    Integer.class, proposal.recommendationId())).isEqualTo(applied ? 1 : 0);
        } finally { gate.countDown(); executor.shutdownNow(); }
    }

    @Test
    void f05Bl03_legacyReceiptFingerprint_stillReplays() throws Exception {
        TestFixture f = createFixture(false);
        UUID occurrence = createOccurrence(f, LocalDate.now(ZoneOffset.UTC).plusDays(3).atTime(9, 0));
        var proposal = rescheduleService.generateReschedule(f.studentId(),
                new WorkoutRescheduleApiRequest(f.studentId(), occurrence, 0L, "legacy-generate", "Legacy", null));
        var request = new AcceptRescheduleApiRequest("legacy-accept", "Legacy", 1, 0L, null, null);
        var applied = rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), request);
        String oldIdentity = proposal.recommendationId() + ":1:" + f.studentId() + ":0:Legacy:"
                + recommendationRepository.findById(proposal.recommendationId()).orElseThrow().structuredPayload();
        String fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(oldIdentity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("""
                UPDATE fitness.ai_recommendation_applications
                SET application_payload=jsonb_set(application_payload-'expectedAppointmentVersion'-'expectedAppointmentId',
                    '{payloadFingerprint}',to_jsonb(?::text)) WHERE ai_recommendation_id=?
                """, fingerprint, proposal.recommendationId());
        var replay = rescheduleService.acceptReschedule(f.studentId(), proposal.recommendationId(), request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.resultingEntityId()).isEqualTo(applied.resultingEntityId());
    }

    @Test
    void f05Bl03_legacyExplicitAppointmentReceipt_replaysOnlyMatchingSource() {
        var f = linkedBl03Fixture(false);
        Instant moved = f.start().plus(2, ChronoUnit.DAYS);
        var change = requestBl03(f, moved, moved.plusSeconds(3600), 0L);
        decideBl03(f, change.request().requestId(), 0L);
        var proposal = generateBl03(f);
        var request = acceptBl03(f, 1L);
        var applied = rescheduleService.acceptReschedule(f.trainer(), proposal.recommendationId(), request);
        // Legacy encoding uses the original fingerprint, but predates saving Appointment expectation fields.
        jdbc.update("UPDATE fitness.ai_recommendation_applications SET application_payload=application_payload-'expectedAppointmentVersion'-'expectedAppointmentId' WHERE ai_recommendation_id=?",
                proposal.recommendationId());
        var replay = rescheduleService.acceptReschedule(f.trainer(), proposal.recommendationId(), request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.resultingEntityId()).isEqualTo(applied.resultingEntityId());
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(f.trainer(), proposal.recommendationId(),
                new AcceptRescheduleApiRequest(request.commandKey(), request.decisionNote(), 1, 0L, 99L, f.appointment())))
                .isInstanceOf(AiRunLifecycleConflictException.class);
    }

    @Test
    @DisplayName("F05-BL-03: Student requests appointment reschedule -> Trainer accepts -> F05 refreshed -> Proposal accepted successfully")
    void f05Bl03_studentRequestsAppointmentReschedule_trainerAccepts_proposalAccepted() {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");
        UUID periodId = jdbc.queryForObject("SELECT id FROM fitness.coaching_periods WHERE student_id=?", UUID.class, f.studentId());

        LocalDate date1 = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(3);
        LocalDate date2 = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(5);

        Instant start1 = date1.atTime(9, 0).toInstant(ZoneOffset.ofHours(7));
        Instant end1 = start1.plus(1, ChronoUnit.HOURS);

        Instant start2 = date2.atTime(14, 0).toInstant(ZoneOffset.ofHours(7));
        Instant end2 = start2.plus(1, ChronoUnit.HOURS);

        UUID occurrenceId = timedOccurrence(f, start1, end1);
        UUID appointmentId = insertConfirmedAppointment(f.studentId(), trainerId, periodId, null, start1, end1);

        // Link occurrence with appointment
        jdbc.update("UPDATE fitness.appointments SET planned_workout_id=? WHERE id=?", occurrenceId, appointmentId);

        // 1. Student requests Appointment reschedule to slot 2 via Schedule bilateral workflow
        var interval2 = new AppointmentService.LocalInterval(
                date2.atTime(14, 0), date2.atTime(15, 0), "Asia/Ho_Chi_Minh", "+07:00", "+07:00"
        );
        var reqCmd = new AppointmentService.RescheduleCommand(
                f.studentId(), appointmentId, 0L, interval2, "Student requested afternoon slot", UUID.randomUUID()
        );
        var reqResult = appointmentService.requestReschedule(reqCmd);
        assertThat(reqResult.request().status()).isEqualTo("PENDING");

        // While pending, appointment remains in original slot
        Instant liveStartPending = jdbc.queryForObject("SELECT start_at FROM fitness.appointments WHERE id=?", Timestamp.class, appointmentId).toInstant();
        assertThat(liveStartPending).isEqualTo(start1);

        // 2. Trainer accepts Appointment reschedule
        var decideCmd = new AppointmentService.RescheduleDecisionCommand(
                trainerId, reqResult.request().requestId(), "ACCEPT", 0L, 0L, UUID.randomUUID()
        );
        var decideResult = appointmentService.decideReschedule(decideCmd);
        assertThat(decideResult.appointment().version()).isEqualTo(1L);
        assertThat(decideResult.appointment().startAt()).isEqualTo(start2);

        // 3. Generate fresh F05 Reschedule Proposal
        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-f05-bl03-student-req", "Align with updated coach appointment", "AFTERNOON"
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);
        assertThat(proposal.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(proposal.structuredPayload().path("reschedule").path("destination").path("proposedStartAt").asText())
                .isEqualTo(start2.toString());

        // 4. Trainer accepts F05 proposal with expected appointment version 1
        var trainerAcceptReq = new AcceptRescheduleApiRequest(
                "trainer-acc-bl03-1", "Coach approved aligned workout", 1, 0L, 1L, appointmentId
        );
        AcceptRescheduleApiResponse acceptResp = rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), trainerAcceptReq);

        assertThat(acceptResp.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        UUID replacementId = acceptResp.resultingEntityId();
        assertThat(replacementId).isNotNull();

        // 5. Verify database invariants
        String oldStatus = jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, occurrenceId);
        assertThat(oldStatus).isEqualTo("RESCHEDULED");

        String newStatus = jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, replacementId);
        assertThat(newStatus).isEqualTo("SCHEDULED");

        UUID activeLinkedWorkout = jdbc.queryForObject("SELECT planned_workout_id FROM fitness.appointments WHERE id=?", UUID.class, appointmentId);
        assertThat(activeLinkedWorkout).isEqualTo(replacementId);

        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE action='AI_WORKOUT_RESCHEDULE_APPLIED' AND target_id=?",
                Integer.class, replacementId);
        assertThat(auditCount).isOne();
    }

    @Test
    @DisplayName("F05-BL-03: Trainer requests appointment reschedule -> Student accepts -> Proposal accepted successfully")
    void f05Bl03_trainerRequestsAppointmentReschedule_studentAccepts_proposalAccepted() {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");
        UUID periodId = jdbc.queryForObject("SELECT id FROM fitness.coaching_periods WHERE student_id=?", UUID.class, f.studentId());

        LocalDate date1 = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(4);
        LocalDate date2 = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(6);

        Instant start1 = date1.atTime(9, 0).toInstant(ZoneOffset.ofHours(7));
        Instant end1 = start1.plus(1, ChronoUnit.HOURS);
        Instant start2 = date2.atTime(18, 0).toInstant(ZoneOffset.ofHours(7));
        Instant end2 = start2.plus(1, ChronoUnit.HOURS);

        UUID occurrenceId = timedOccurrence(f, start1, end1);
        UUID appointmentId = insertConfirmedAppointment(f.studentId(), trainerId, periodId, null, start1, end1);
        jdbc.update("UPDATE fitness.appointments SET planned_workout_id=? WHERE id=?", occurrenceId, appointmentId);

        // 1. Trainer requests Appointment reschedule to evening slot
        var interval2 = new AppointmentService.LocalInterval(
                date2.atTime(18, 0), date2.atTime(19, 0), "Asia/Ho_Chi_Minh", "+07:00", "+07:00"
        );
        var reqCmd = new AppointmentService.RescheduleCommand(
                trainerId, appointmentId, 0L, interval2, "Trainer proposed evening slot", UUID.randomUUID()
        );
        var reqResult = appointmentService.requestReschedule(reqCmd);
        assertThat(reqResult.request().status()).isEqualTo("PENDING");

        // 2. Student accepts Appointment reschedule
        var decideCmd = new AppointmentService.RescheduleDecisionCommand(
                f.studentId(), reqResult.request().requestId(), "ACCEPT", 0L, 0L, UUID.randomUUID()
        );
        var decideResult = appointmentService.decideReschedule(decideCmd);
        assertThat(decideResult.appointment().version()).isEqualTo(1L);

        // 3. Fresh F05 proposal generated
        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-f05-bl03-trainer-req", "Evening slot aligned", "EVENING"
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);
        assertThat(proposal.status()).isEqualTo(AiRecommendationStatus.PENDING);

        // 4. Trainer accepts F05 proposal
        var trainerAcceptReq = new AcceptRescheduleApiRequest(
                "trainer-acc-bl03-2", "Coach approved", 1, 0L, 1L, appointmentId
        );
        AcceptRescheduleApiResponse acceptResp = rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), trainerAcceptReq);
        assertThat(acceptResp.status()).isEqualTo(AiRecommendationStatus.APPLIED);
    }

    @Test
    @DisplayName("F05-BL-03: Pending or Rejected change request does not take effect; old appointment remains intact")
    void f05Bl03_pendingOrRejectedChangeRequest_doesNotChangeAppointment() {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");
        UUID periodId = jdbc.queryForObject("SELECT id FROM fitness.coaching_periods WHERE student_id=?", UUID.class, f.studentId());

        LocalDate date1 = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(3);
        LocalDate date2 = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(5);

        Instant start1 = date1.atTime(9, 0).toInstant(ZoneOffset.ofHours(7));
        Instant end1 = start1.plus(1, ChronoUnit.HOURS);
        Instant start2 = date2.atTime(14, 0).toInstant(ZoneOffset.ofHours(7));
        Instant end2 = start2.plus(1, ChronoUnit.HOURS);

        UUID occurrenceId = timedOccurrence(f, start1, end1);
        UUID appointmentId = insertConfirmedAppointment(f.studentId(), trainerId, periodId, null, start1, end1);
        jdbc.update("UPDATE fitness.appointments SET planned_workout_id=? WHERE id=?", occurrenceId, appointmentId);

        // 1. Student requests reschedule
        var interval2 = new AppointmentService.LocalInterval(
                date2.atTime(14, 0), date2.atTime(15, 0), "Asia/Ho_Chi_Minh", "+07:00", "+07:00"
        );
        var reqCmd = new AppointmentService.RescheduleCommand(
                f.studentId(), appointmentId, 0L, interval2, "Pending request test", UUID.randomUUID()
        );
        var reqResult = appointmentService.requestReschedule(reqCmd);
        assertThat(reqResult.request().status()).isEqualTo("PENDING");

        // 2. Trainer REJECTS the reschedule request
        var decideCmd = new AppointmentService.RescheduleDecisionCommand(
                trainerId, reqResult.request().requestId(), "REJECT", 0L, 0L, UUID.randomUUID()
        );
        appointmentService.decideReschedule(decideCmd);

        // Verify Appointment start time and version remain unchanged
        Map<String, Object> appRow = jdbc.queryForMap(
                "SELECT start_at, version, status FROM fitness.appointments WHERE id=?", appointmentId
        );
        assertThat(((Timestamp) appRow.get("start_at")).toInstant()).isEqualTo(start1);
        assertThat(((Number) appRow.get("version")).longValue()).isEqualTo(0L);
        assertThat(appRow.get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("F05-BL-03: Stale proposal blocked when appointment version advances; fresh proposal succeeds")
    void f05Bl03_staleProposalBlockedWhenAppointmentVersionAdvances_freshProposalSucceeds() {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");
        UUID periodId = jdbc.queryForObject("SELECT id FROM fitness.coaching_periods WHERE student_id=?", UUID.class, f.studentId());

        LocalDate date1 = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(3);
        LocalDate date2 = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(5);

        Instant start1 = date1.atTime(9, 0).toInstant(ZoneOffset.ofHours(7));
        Instant end1 = start1.plus(1, ChronoUnit.HOURS);
        Instant start2 = date2.atTime(14, 0).toInstant(ZoneOffset.ofHours(7));
        Instant end2 = start2.plus(1, ChronoUnit.HOURS);

        UUID occurrenceId = timedOccurrence(f, start1, end1);
        UUID appointmentId = insertConfirmedAppointment(f.studentId(), trainerId, periodId, null, start1, end1);
        jdbc.update("UPDATE fitness.appointments SET planned_workout_id=? WHERE id=?", occurrenceId, appointmentId);

        // 1. Generate Proposal while appointment is at version 0
        var generateReq1 = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-f05-bl03-stale", "Before bilateral change", null
        );
        WorkoutRescheduleApiResponse staleProposal = rescheduleService.generateReschedule(f.studentId(), generateReq1);

        // 2. Both parties complete appointment reschedule -> appointment version advances to 1
        var interval2 = new AppointmentService.LocalInterval(
                date2.atTime(14, 0), date2.atTime(15, 0), "Asia/Ho_Chi_Minh", "+07:00", "+07:00"
        );
        var reqCmd = new AppointmentService.RescheduleCommand(
                f.studentId(), appointmentId, 0L, interval2, "Moving to slot 2", UUID.randomUUID()
        );
        var reqResult = appointmentService.requestReschedule(reqCmd);
        appointmentService.decideReschedule(new AppointmentService.RescheduleDecisionCommand(
                trainerId, reqResult.request().requestId(), "ACCEPT", 0L, 0L, UUID.randomUUID()
        ));

        // 3. Attempt to accept STALE proposal -> MUST FAIL with 409
        var staleAcceptReq = new AcceptRescheduleApiRequest(
                "trainer-acc-stale", "Attempting stale accept", 1, 0L, 0L, appointmentId
        );
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(trainerId, staleProposal.recommendationId(), staleAcceptReq))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Linked appointment has changed since proposal was generated.");

        // Stale recommendation remains PENDING and occurrence remains SCHEDULED
        assertThat(recommendationRepository.findById(staleProposal.recommendationId()).orElseThrow().status())
                .isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.planned_workouts WHERE id=?", String.class, occurrenceId))
                .isEqualTo("SCHEDULED");

        // 4. Generate FRESH proposal capturing updated appointment version 1
        var generateReq2 = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-f05-bl03-fresh", "Fresh proposal after bilateral change", "AFTERNOON"
        );
        WorkoutRescheduleApiResponse freshProposal = rescheduleService.generateReschedule(f.studentId(), generateReq2);

        // 5. Accept fresh proposal -> SUCCEEDS
        var freshAcceptReq = new AcceptRescheduleApiRequest(
                "trainer-acc-fresh", "Approved fresh proposal", 1, 0L, 1L, appointmentId
        );
        AcceptRescheduleApiResponse freshResp = rescheduleService.acceptReschedule(trainerId, freshProposal.recommendationId(), freshAcceptReq);
        assertThat(freshResp.status()).isEqualTo(AiRecommendationStatus.APPLIED);
    }

    @Test
    @DisplayName("F05-BL-03: Concurrency with CountDownLatch — competing accepts for same recommendation")
    void f05Bl03_concurrentCompetingAccepts_onlyOneSucceeds() throws Exception {
        TestFixture f = createFixture(false);
        LocalDateTime baseStart = LocalDate.now(ZoneOffset.UTC).plusDays(2).atTime(9, 0);
        UUID occurrenceId = createOccurrence(f, baseStart);

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-f05-bl03-concurrent", "Conflict", null
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        rescheduleService.acceptReschedule(
                                f.studentId(),
                                proposal.recommendationId(),
                                new AcceptRescheduleApiRequest("competing-cmd-" + idx, "Concurrency test", 1, 0L, null, null)
                        );
                        successCount.incrementAndGet();
                    } catch (AiRunLifecycleConflictException e) {
                        conflictCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(1);

            // Exactly 1 application persisted
            Integer appCount = jdbc.queryForObject(
                    "SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id=?",
                    Integer.class, proposal.recommendationId()
            );
            assertThat(appCount).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("F05-BL-03: Idempotent replay preserves fingerprint and receipt; differing params trigger conflict")
    void f05Bl03_idempotentReplayPreservesFingerprintAndReceipt() {
        TestFixture f = createFixture(false);
        UUID trainerId = UUID.randomUUID();
        setupCoachingPeriod(f.studentId(), trainerId, "HUMAN_COACH");
        UUID periodId = jdbc.queryForObject("SELECT id FROM fitness.coaching_periods WHERE student_id=?", UUID.class, f.studentId());

        LocalDate date1 = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(3);
        LocalDate date2 = LocalDate.now(ZoneOffset.ofHours(7)).plusDays(5);
        Instant start1 = date1.atTime(9, 0).toInstant(ZoneOffset.ofHours(7));
        Instant end1 = start1.plus(1, ChronoUnit.HOURS);
        Instant start2 = date2.atTime(14, 0).toInstant(ZoneOffset.ofHours(7));
        Instant end2 = start2.plus(1, ChronoUnit.HOURS);

        UUID occurrenceId = timedOccurrence(f, start1, end1);
        UUID appointmentId = insertConfirmedAppointment(f.studentId(), trainerId, periodId, null, start1, end1);
        jdbc.update("UPDATE fitness.appointments SET planned_workout_id=? WHERE id=?", occurrenceId, appointmentId);

        // Bilateral reschedule appointment to slot 2
        var interval2 = new AppointmentService.LocalInterval(
                date2.atTime(14, 0), date2.atTime(15, 0), "Asia/Ho_Chi_Minh", "+07:00", "+07:00"
        );
        var reqResult = appointmentService.requestReschedule(new AppointmentService.RescheduleCommand(
                f.studentId(), appointmentId, 0L, interval2, "Reschedule for idemp test", UUID.randomUUID()
        ));
        appointmentService.decideReschedule(new AppointmentService.RescheduleDecisionCommand(
                trainerId, reqResult.request().requestId(), "ACCEPT", 0L, 0L, UUID.randomUUID()
        ));

        var generateReq = new WorkoutRescheduleApiRequest(
                f.studentId(), occurrenceId, 0L, "gen-f05-bl03-idemp", "Idempotency test", "AFTERNOON"
        );
        WorkoutRescheduleApiResponse proposal = rescheduleService.generateReschedule(f.studentId(), generateReq);

        // First apply
        var acceptReq1 = new AcceptRescheduleApiRequest(
                "cmd-idemp-bl03", "Initial decision note", 1, 0L, 1L, appointmentId
        );
        AcceptRescheduleApiResponse resp1 = rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), acceptReq1);
        assertThat(resp1.replayed()).isFalse();
        assertThat(resp1.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        // Replay with identical parameters
        var acceptReq2 = new AcceptRescheduleApiRequest(
                "cmd-idemp-bl03", "Initial decision note", 1, 0L, 1L, appointmentId
        );
        AcceptRescheduleApiResponse resp2 = rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), acceptReq2);
        assertThat(resp2.replayed()).isTrue();
        assertThat(resp2.resultingEntityId()).isEqualTo(resp1.resultingEntityId());

        // Replay with differing expectedAppointmentVersion -> throws conflict
        var acceptReqDifferingVer = new AcceptRescheduleApiRequest(
                "cmd-idemp-bl03", "Initial decision note", 1, 0L, 99L, appointmentId
        );
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), acceptReqDifferingVer))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Idempotency conflict");

        // Replay with differing expectedAppointmentId -> throws conflict
        var acceptReqDifferingId = new AcceptRescheduleApiRequest(
                "cmd-idemp-bl03", "Initial decision note", 1, 0L, 1L, UUID.randomUUID()
        );
        assertThatThrownBy(() -> rescheduleService.acceptReschedule(trainerId, proposal.recommendationId(), acceptReqDifferingId))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Idempotency conflict");
    }
}
