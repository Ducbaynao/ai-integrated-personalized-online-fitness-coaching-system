package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.AcceptRecommendationCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.AcceptRecommendationResult;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.GenerateProposalCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.RejectRecommendationCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.RejectRecommendationResult;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.WorkoutProposalResult;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.ActivateCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.CreateDraftCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.ExercisePrescription;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionTemplate;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AiWorkoutProposalFullSliceIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_f02_slice_test").withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired
    private AiWorkoutProposalUseCase proposalService;

    @Autowired
    private AiRecommendationRepository recommendationRepository;

    @Autowired
    private WorkoutPlanQueryUseCase workoutPlanQuery;

    @Autowired
    private WorkoutPlanCommandUseCase workoutPlanCommands;

    @Autowired
    private JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void seedDefaultExercises() {
        UUID adminId = person("ADMIN");
        List<UUID> vars = List.of(
                UUID.fromString("a0000000-0000-4000-8000-000000000001"),
                UUID.fromString("a0000000-0000-4000-8000-000000000002"),
                UUID.fromString("a0000000-0000-4000-8000-000000000003"),
                UUID.fromString("a0000000-0000-4000-8000-000000000004")
        );
        for (int i = 0; i < vars.size(); i++) {
            UUID varId = vars.get(i);
            UUID exId = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?) ON CONFLICT (id) DO NOTHING",
                    exId, "default-ex-" + i + "-" + UUID.randomUUID(), "Default Exercise " + i, adminId);
            jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true) ON CONFLICT (id) DO NOTHING",
                    varId, exId, "default-var-" + i + "-" + UUID.randomUUID(), "Default Variation " + i);
        }
    }

    private UUID person(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?,'hash','Person','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')",
                id, id + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?",
                id, id, role);
        if (role.equals("STUDENT")) {
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        }
        if (role.equals("TRAINER")) {
            jdbc.update("""
                    INSERT INTO fitness.trainer_profiles
                    (user_id,verification_status,verified_at,is_accepting_students,is_active,activity_status)
                    VALUES (?,'VERIFIED',clock_timestamp(),true,true,'ACTIVE')
                    """, id);
        }
        return id;
    }

    private UUID createExerciseAndVariation(UUID creatorId) {
        UUID exercise = UUID.randomUUID();
        UUID variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                exercise, "exercise-" + exercise, "Test Exercise", creatorId);
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                variation, exercise, "variation-" + variation, "Test Variation");
        return variation;
    }

    private UUID createSelfDirectedCoachingPeriod(UUID studentId) {
        UUID periodId = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,created_by) VALUES (?,?,'SELF_DIRECTED',clock_timestamp()-interval '1 day',?)",
                periodId, studentId, studentId);
        return periodId;
    }

    private record HumanCoachSetup(UUID studentId, UUID trainerId, UUID relationshipId, UUID periodId) {}

    private HumanCoachSetup createHumanCoachSetup() {
        UUID student = person("STUDENT");
        UUID trainer = person("TRAINER");
        UUID relationship = UUID.randomUUID();
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
                VALUES (?,?,?,?, 'WORKOUT_PLAN','ALLOW','MANAGE'::fitness.data_access_level,
                        clock_timestamp()-interval '1 day',?)
                """, permission, relationship, student, trainer, student);
        return new HumanCoachSetup(student, trainer, relationship, period);
    }

    @Test
    @DisplayName("Full slice SELF_DIRECTED: Proposal generated -> PENDING -> Accept -> APPLIED -> Workout Plan ACTIVE in store")
    void fullSlice_selfDirected_generateAndAccept_succeeds() {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);

        // 1. Generate Proposal
        GenerateProposalCommand genCmd = new GenerateProposalCommand(
                studentId, studentId, "gen-key-1", Map.of("daysPerWeek", 3)
        );
        WorkoutProposalResult proposal = proposalService.generateProposal(genCmd);

        assertThat(proposal.recommendationId()).isNotNull();
        assertThat(proposal.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(proposal.decisionAuthority()).isEqualTo(AiDecisionAuthority.STUDENT);

        // 2. Accept Proposal
        AcceptRecommendationCommand acceptCmd = new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "accept-key-1", "Applying 1-week starter plan"
        );
        AcceptRecommendationResult acceptResult = proposalService.acceptRecommendation(acceptCmd);

        assertThat(acceptResult.recommendationId()).isEqualTo(proposal.recommendationId());
        assertThat(acceptResult.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(acceptResult.resultingEntityType()).isEqualTo("WORKOUT_PLAN");
        assertThat(acceptResult.resultingEntityId()).isNotNull();
        assertThat(acceptResult.replayed()).isFalse();

        // 3. Verify in Workout Plan Domain
        var activePlan = workoutPlanQuery.current(studentId, studentId);
        assertThat(activePlan).isNotNull();
        assertThat(activePlan.plan().id()).isEqualTo(acceptResult.resultingEntityId());
        assertThat(activePlan.plan().status()).isEqualTo(WorkoutPlanStatus.ACTIVE);
        assertThat(activePlan.plan().aggregateVersion()).isEqualTo(1);

        // 4. Verify Application Record in DB
        var appOpt = recommendationRepository.findApplicationByRecommendationId(proposal.recommendationId());
        assertThat(appOpt).isPresent();
        assertThat(appOpt.get().appliedBy()).isEqualTo(studentId);
        assertThat(appOpt.get().resultingEntityId()).isEqualTo(activePlan.plan().id());
    }

    @Test
    @DisplayName("Full slice HUMAN_COACH: Trainer generates -> Student cannot accept (403) -> Assigned Trainer accepts -> APPLIED")
    void fullSlice_humanCoach_authorityEnforced() {
        HumanCoachSetup setup = createHumanCoachSetup();
        UUID unrelatedTrainer = person("TRAINER");

        // 1. Trainer generates proposal
        GenerateProposalCommand genCmd = new GenerateProposalCommand(
                setup.trainerId(), setup.studentId(), "trainer-gen-1", Map.of("daysPerWeek", 4)
        );
        WorkoutProposalResult proposal = proposalService.generateProposal(genCmd);

        assertThat(proposal.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(proposal.decisionAuthority()).isEqualTo(AiDecisionAuthority.TRAINER);

        // 2. Student attempts to accept -> 403 Forbidden
        AcceptRecommendationCommand studentAccept = new AcceptRecommendationCommand(
                setup.studentId(), proposal.recommendationId(), "student-attempt", "I want to apply"
        );
        assertThatThrownBy(() -> proposalService.acceptRecommendation(studentAccept))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Only the currently assigned coach has decision authority");

        // 3. Unassigned trainer attempts to accept -> 403 Forbidden
        AcceptRecommendationCommand strangerAccept = new AcceptRecommendationCommand(
                unrelatedTrainer, proposal.recommendationId(), "stranger-attempt", "Hacking"
        );
        assertThatThrownBy(() -> proposalService.acceptRecommendation(strangerAccept))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Only the currently assigned coach has decision authority");

        // 4. Assigned trainer accepts -> APPLIED
        AcceptRecommendationCommand trainerAccept = new AcceptRecommendationCommand(
                setup.trainerId(), proposal.recommendationId(), "trainer-accept-1", "Coach approved"
        );
        AcceptRecommendationResult result = proposalService.acceptRecommendation(trainerAccept);

        assertThat(result.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(result.appliedBy()).isEqualTo(setup.trainerId());

        var activePlan = workoutPlanQuery.current(setup.trainerId(), setup.studentId());
        assertThat(activePlan).isNotNull();
        assertThat(activePlan.plan().id()).isEqualTo(result.resultingEntityId());
    }

    @Test
    @DisplayName("Reject proposal: transitions to REJECTED and creates NO workout plan")
    void rejectProposal_createsNoWorkoutPlan() {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);

        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                studentId, studentId, "gen-reject-1", Map.of()
        ));

        RejectRecommendationResult rejectResult = proposalService.rejectRecommendation(new RejectRecommendationCommand(
                studentId, proposal.recommendationId(), "Too much volume for deload week"
        ));

        assertThat(rejectResult.status()).isEqualTo(AiRecommendationStatus.REJECTED);
        assertThat(rejectResult.decisionNote()).isEqualTo("Too much volume for deload week");

        // Check recommendation in DB
        AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.REJECTED);
        assertThat(rec.decisionNote()).isEqualTo("Too much volume for deload week");

        // Workout plan store has no active plan
        assertThatThrownBy(() -> workoutPlanQuery.current(studentId, studentId))
                .isInstanceOf(WorkoutPlanFailure.class);
    }

    @Test
    @DisplayName("Idempotency: Identical commandKey replays receipt; mismatched payload throws conflict")
    void idempotencyReplay_andConflictHandling() {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);

        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                studentId, studentId, "gen-idemp-1", Map.of()
        ));

        // 1. Initial Accept
        AcceptRecommendationResult first = proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "idemp-key-1", "Initial note"
        ));
        assertThat(first.replayed()).isFalse();

        // 2. Exact Replay
        AcceptRecommendationResult replay = proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "idemp-key-1", "Initial note"
        ));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.resultingEntityId()).isEqualTo(first.resultingEntityId());

        // 3. Same commandKey but differing note -> Conflict
        assertThatThrownBy(() -> proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "idemp-key-1", "Different note"
        ))).isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Idempotency conflict");

        // 4. Different commandKey on already applied recommendation -> Conflict
        assertThatThrownBy(() -> proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "new-key-2", "New note"
        ))).isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("already been applied");
    }

    @Test
    @DisplayName("Expired recommendation cannot be accepted (409)")
    void expiredProposal_cannotBeAccepted() {
        UUID studentId = person("STUDENT");
        UUID periodId = createSelfDirectedCoachingPeriod(studentId);

        UUID runId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fitness.ai_runs (
                id, requested_by, student_id, coaching_period_id, request_type, status, purpose,
                evaluation_only, input_payload, created_at
            ) VALUES (?, ?, ?, ?, 'F02_WORKOUT_PLAN_PROPOSAL', 'SUCCEEDED', 'BUSINESS_ASSISTANCE',
                      false, '{}'::jsonb, now() - interval '10 days')
            """, runId, studentId, studentId, periodId);

        UUID recId = UUID.randomUUID();
        Instant expiredAt = Instant.now().minus(3, ChronoUnit.DAYS);

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .aiRunId(runId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .recommendationType("WORKOUT_PLAN_PROPOSAL")
                .title("Expired plan")
                .explanation("Details")
                .structuredPayload("{\"plan\":{\"name\":\"Expired plan\",\"sessions\":[]}}")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .targetEntityType("WORKOUT_PLAN")
                .expiresAt(expiredAt)
                .createdAt(expiredAt.minus(7, ChronoUnit.DAYS))
                .updatedAt(expiredAt.minus(7, ChronoUnit.DAYS))
                .build();
        recommendationRepository.save(rec);

        assertThatThrownBy(() -> proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                studentId, recId, "accept-expired", "Trying to apply"
        ))).isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("Active workout plan exists (same owner): accept publishes new version on existing plan")
    void existingActivePlan_whenOwnerAccepts_publishesNewVersionSuccessfully() {
        UUID studentId = person("STUDENT");
        UUID periodId = createSelfDirectedCoachingPeriod(studentId);
        UUID variationId = createExerciseAndVariation(studentId);

        // Pre-create an ACTIVE workout plan for student (version 1)
        var draft = workoutPlanCommands.createDraft(new CreateDraftCommand(
                studentId, studentId, "Existing Active Plan", "Already running",
                List.of(new WorkoutSessionTemplate(UUID.randomUUID(), 1, 1, 1, "Session 1", "Strength", 45, null,
                        List.of(new ExercisePrescription(UUID.randomUUID(), variationId, 1, 3, 8, 12, null, 60, null, null)))),
                "existing-draft-key"
        ));
        workoutPlanCommands.activate(new ActivateCommand(draft.planId(), studentId, 0L, "existing-activate-key"));

        // Verify plan is active at version 1
        var planV1 = workoutPlanQuery.current(studentId, studentId);
        assertThat(planV1.plan().status()).isEqualTo(WorkoutPlanStatus.ACTIVE);
        assertThat(planV1.plan().aggregateVersion()).isEqualTo(1);
        UUID originalPlanId = draft.planId();

        // Now student generates a proposal
        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                studentId, studentId, "gen-publish-version-test", Map.of()
        ));

        // Accept proposal -> Should publish new version on the existing active plan
        AcceptRecommendationResult acceptResult = proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "accept-publish-ver-key", "Applying updated AI version"
        ));

        assertThat(acceptResult.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(acceptResult.resultingEntityType()).isEqualTo("WORKOUT_PLAN");
        assertThat(acceptResult.resultingEntityId()).isEqualTo(originalPlanId);

        // Verify existing plan is still active and aggregate version incremented
        var planV2 = workoutPlanQuery.current(studentId, studentId);
        assertThat(planV2.plan().id()).isEqualTo(originalPlanId);
        assertThat(planV2.plan().status()).isEqualTo(WorkoutPlanStatus.ACTIVE);
        assertThat(planV2.plan().aggregateVersion()).isEqualTo(2);

        // Verify application record contains PUBLISHED_NEW_VERSION and planVersionId
        var appOpt = recommendationRepository.findApplicationByRecommendationId(proposal.recommendationId());
        assertThat(appOpt).isPresent();
        assertThat(appOpt.get().applicationPayload()).contains("PUBLISHED_NEW_VERSION");
        assertThat(appOpt.get().resultingEntityId()).isEqualTo(originalPlanId);
        assertThat(appOpt.get().applicationPayload()).contains("planVersionId");
    }

    @Test
    @DisplayName("Active workout plan exists (trainer owner): student in SELF_DIRECTED cannot publish version directly, requires successor")
    void existingActivePlan_whenStudentAttemptsToModifyTrainerPlan_throwsSuccessorRequiredConflict() {
        HumanCoachSetup setup = createHumanCoachSetup();
        UUID variationId = createExerciseAndVariation(setup.trainerId());

        // Trainer creates and activates plan for student
        var draft = workoutPlanCommands.createDraft(new CreateDraftCommand(
                setup.studentId(), setup.trainerId(), "Trainer Plan", "Created by trainer",
                List.of(new WorkoutSessionTemplate(UUID.randomUUID(), 1, 1, 1, "Session 1", "Strength", 45, null,
                        List.of(new ExercisePrescription(UUID.randomUUID(), variationId, 1, 3, 8, 12, null, 60, null, null)))),
                "trainer-draft-key"
        ));
        workoutPlanCommands.activate(new ActivateCommand(draft.planId(), setup.trainerId(), 0L, "trainer-activate-key"));

        // Close previous HUMAN_COACH coaching period at statement_timestamp()
        jdbc.update("UPDATE fitness.coaching_periods SET ended_at = statement_timestamp() WHERE student_id = ?", setup.studentId());
        // Transition to SELF_DIRECTED coaching period starting at statement_timestamp()
        UUID newPeriodId = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,created_by) VALUES (?,?,'SELF_DIRECTED',statement_timestamp(),?)",
                newPeriodId, setup.studentId(), setup.studentId());

        // Student generates proposal in SELF_DIRECTED
        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                setup.studentId(), setup.studentId(), "student-gen-sd-1", Map.of()
        ));

        // Attempt to accept proposal -> student cannot publish version to trainer's plan directly
        assertThatThrownBy(() -> proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                setup.studentId(), proposal.recommendationId(), "student-accept-trainer-plan", "Note"
        ))).isInstanceOf(WorkoutPlanFailure.class)
                .extracting(ex -> ((WorkoutPlanFailure) ex).error())
                .isEqualTo(WorkoutPlanError.WORKOUT_PLAN_SUCCESSOR_REQUIRED);

        // Verify transaction rolled back cleanly
        AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(recommendationRepository.findApplicationByRecommendationId(proposal.recommendationId())).isEmpty();
    }

    @Test
    @DisplayName("Active workout plan: publishing new version preserves existing planned workouts and executions history")
    void existingActivePlan_publishesNewVersion_preservesExistingMaterializedPlannedWorkoutsAndExecutions() {
        UUID studentId = person("STUDENT");
        UUID periodId = createSelfDirectedCoachingPeriod(studentId);
        UUID variationId = createExerciseAndVariation(studentId);

        // Pre-create an ACTIVE workout plan for student
        var draft = workoutPlanCommands.createDraft(new CreateDraftCommand(
                studentId, studentId, "V1 Plan", "Version 1",
                List.of(new WorkoutSessionTemplate(UUID.randomUUID(), 1, 1, 1, "Session 1", "Strength", 45, null,
                        List.of(new ExercisePrescription(UUID.randomUUID(), variationId, 1, 3, 8, 12, null, 60, null, null)))),
                "v1-draft-key"
        ));
        workoutPlanCommands.activate(new ActivateCommand(draft.planId(), studentId, 0L, "v1-activate-key"));

        var planV1 = workoutPlanQuery.current(studentId, studentId);
        UUID v1VersionId = planV1.currentVersion().id();

        UUID sessionId = jdbc.queryForObject(
                "SELECT id FROM fitness.workout_plan_sessions WHERE workout_plan_version_id = ? LIMIT 1",
                UUID.class, v1VersionId
        );

        // Simulate an existing materialized planned workout occurrence and execution record in DB
        UUID plannedWorkoutId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fitness.planned_workouts
            (id, student_id, coaching_period_id, workout_plan_session_id, planned_start_at, original_planned_start_at, status, created_by)
            VALUES (?, ?, ?, ?, clock_timestamp() - interval '1 day', clock_timestamp() - interval '1 day', 'COMPLETED'::fitness.planned_workout_status, ?)
        """, plannedWorkoutId, studentId, periodId, sessionId, studentId);

        UUID executionLogId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fitness.workout_session_logs
            (id, student_id, coaching_period_id, workout_plan_version_id, planned_workout_id,
             performed_start_at, performed_end_at, status, logged_by)
            VALUES (?, ?, ?, ?, ?, clock_timestamp() - interval '1 day', clock_timestamp() - interval '23 hours', 'COMPLETED'::fitness.actual_workout_status, ?)
        """, executionLogId, studentId, periodId, v1VersionId, plannedWorkoutId, studentId);

        // Student generates proposal and accepts
        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                studentId, studentId, "gen-v2-preserve-test", Map.of()
        ));
        AcceptRecommendationResult acceptResult = proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "accept-v2-preserve-key", "Upgrading to V2"
        ));
        assertThat(acceptResult.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        // Verify planned workout and execution log are completely intact and unchanged
        Integer plannedCount = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.planned_workouts WHERE id = ? AND workout_plan_session_id = ? AND status = 'COMPLETED'::fitness.planned_workout_status",
                Integer.class, plannedWorkoutId, sessionId
        );
        assertThat(plannedCount).isEqualTo(1);

        Integer executionCount = jdbc.queryForObject(
                "SELECT count(*) FROM fitness.workout_session_logs WHERE id = ? AND workout_plan_version_id = ? AND status = 'COMPLETED'::fitness.actual_workout_status",
                Integer.class, executionLogId, v1VersionId
        );
        assertThat(executionCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Active workout plan: plan aggregate version changed after preview throws conflict and rolls back")
    void existingActivePlan_whenPlanVersionChangedAfterPreview_causesConflictAndRollback() {
        UUID studentId = person("STUDENT");
        UUID periodId = createSelfDirectedCoachingPeriod(studentId);
        UUID variationId = createExerciseAndVariation(studentId);

        // Pre-create an ACTIVE workout plan for student (v1)
        var draft = workoutPlanCommands.createDraft(new CreateDraftCommand(
                studentId, studentId, "V1 Plan", "Version 1",
                List.of(new WorkoutSessionTemplate(UUID.randomUUID(), 1, 1, 1, "Session 1", "Strength", 45, null,
                        List.of(new ExercisePrescription(UUID.randomUUID(), variationId, 1, 3, 8, 12, null, 60, null, null)))),
                "v1-draft-key-race"
        ));
        workoutPlanCommands.activate(new ActivateCommand(draft.planId(), studentId, 0L, "v1-activate-key-race"));

        // Student generates proposal while plan is at aggregateVersion = 1
        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                studentId, studentId, "gen-plan-race-test", Map.of()
        ));

        // Another action publishes a new version in the background, incrementing aggregateVersion to 2
        workoutPlanCommands.publishVersion(new WorkoutPlanCommandUseCase.PublishVersionCommand(
                draft.planId(), studentId, 1L, "background-publish-key", "REGULAR_UPDATE", "Updated in bg",
                List.of(new WorkoutSessionTemplate(UUID.randomUUID(), 1, 1, 1, "Session 1 Updated", "Strength", 45, null,
                        List.of(new ExercisePrescription(UUID.randomUUID(), variationId, 1, 3, 8, 12, null, 60, null, null))))
        ));

        // Now student sends accept expecting aggregateVersion = 1 (what was seen at preview)
        AcceptRecommendationCommand acceptCmd = new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "accept-stale-plan-ver-key", "Note", 1,
                draft.planId(), 1L, null
        );

        assertThatThrownBy(() -> proposalService.acceptRecommendation(acceptCmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Workout plan version conflict: expected plan aggregate version 1 but current is 2");

        // Transaction rolled back: recommendation remains PENDING
        AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(recommendationRepository.findApplicationByRecommendationId(proposal.recommendationId())).isEmpty();
    }

    @Test
    @DisplayName("No active plan at preview: active plan appeared before accept throws conflict and rolls back")
    void noActivePlan_whenActivePlanAppearedAfterPreview_causesConflictAndRollback() {
        UUID studentId = person("STUDENT");
        UUID periodId = createSelfDirectedCoachingPeriod(studentId);
        UUID variationId = createExerciseAndVariation(studentId);

        // Student generates proposal when NO active plan exists
        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                studentId, studentId, "gen-appeared-plan-test", Map.of()
        ));

        // Before accepting, another writer activates a workout plan for the student
        var draft = workoutPlanCommands.createDraft(new CreateDraftCommand(
                studentId, studentId, "Competitor Plan", "Created concurrently",
                List.of(new WorkoutSessionTemplate(UUID.randomUUID(), 1, 1, 1, "Session 1", "Strength", 45, null,
                        List.of(new ExercisePrescription(UUID.randomUUID(), variationId, 1, 3, 8, 12, null, 60, null, null)))),
                "competitor-draft-key"
        ));
        workoutPlanCommands.activate(new ActivateCommand(draft.planId(), studentId, 0L, "competitor-activate-key"));

        // Student sends accept with expectedNoActivePlan = true (as previewed)
        AcceptRecommendationCommand acceptCmd = new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "accept-appeared-plan-key", "Note", 1,
                null, null, true
        );

        assertThatThrownBy(() -> proposalService.acceptRecommendation(acceptCmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Active workout plan appeared after proposal was previewed");

        // Recommendation remains PENDING
        AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(recommendationRepository.findApplicationByRecommendationId(proposal.recommendationId())).isEmpty();
    }

    @Test
    @DisplayName("Audit failure during accept: rolls back entire transaction leaving recommendation PENDING with no new plan or application")
    void auditFailureDuringAccept_rollsBackEntireTransaction() {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);

        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                studentId, studentId, "gen-audit-fail-test", Map.of()
        ));

        // Install temporary trigger on fitness.audit_logs to simulate failure during mandatory audit recording
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION fitness.test_fail_audit_trigger() RETURNS trigger AS $$
            BEGIN
                IF NEW.action = 'AI_RECOMMENDATION_APPLIED' THEN
                    RAISE EXCEPTION 'Simulated mandatory audit failure';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
        """);
        jdbc.execute("""
            CREATE TRIGGER trg_test_fail_audit
            BEFORE INSERT ON fitness.audit_logs
            FOR EACH ROW EXECUTE FUNCTION fitness.test_fail_audit_trigger();
        """);

        try {
            // Attempt to accept proposal -> must fail when recording immutable audit inside transaction
            assertThatThrownBy(() -> proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                    studentId, proposal.recommendationId(), "accept-audit-fail-key", "Note"
            ))).isInstanceOf(Exception.class)
                    .hasMessageContaining("Simulated mandatory audit failure");

            // Independent reads outside test transaction:
            // 1. Recommendation must STILL be PENDING
            AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
            assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);
            assertThat(rec.decidedBy()).isNull();
            assertThat(rec.targetEntityId()).isNull();

            // 2. No application / receipt row created
            assertThat(recommendationRepository.findApplicationByRecommendationId(proposal.recommendationId())).isEmpty();

            // 3. No workout plan or versions created for this student
            Integer planCount = jdbc.queryForObject(
                    "SELECT count(*) FROM fitness.workout_plans WHERE student_id = ?",
                    Integer.class,
                    studentId
            );
            assertThat(planCount).isZero();

        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_test_fail_audit ON fitness.audit_logs;");
            jdbc.execute("DROP FUNCTION IF EXISTS fitness.test_fail_audit_trigger();");
        }
    }

    @Test
    @DisplayName("Concurrent double-accept: exactly one succeeds as primary, second succeeds as replay, only 1 plan created")
    void concurrentDoubleAccept_createsExactlyOnePlan() throws Exception {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);

        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                studentId, studentId, "gen-concurrent-1", Map.of()
        ));

        int threadCount = 2;
        var executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        var startLatch = new java.util.concurrent.CountDownLatch(1);
        var doneLatch = new java.util.concurrent.CountDownLatch(threadCount);

        var futures = new java.util.ArrayList<java.util.concurrent.Future<AcceptRecommendationResult>>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                try {
                    return proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                            studentId, proposal.recommendationId(), "same-command-key-123", "Same note"
                    ));
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        List<AcceptRecommendationResult> results = new java.util.ArrayList<>();
        for (var f : futures) {
            results.add(f.get());
        }

        long nonReplayedCount = results.stream().filter(r -> !r.replayed()).count();
        long replayedCount = results.stream().filter(r -> r.replayed()).count();

        assertThat(nonReplayedCount).isEqualTo(1);
        assertThat(replayedCount).isEqualTo(1);
        assertThat(results.get(0).resultingEntityId()).isEqualTo(results.get(1).resultingEntityId());

        // Check exactly 1 active plan exists for student
        var activePlan = workoutPlanQuery.current(studentId, studentId);
        assertThat(activePlan.plan().id()).isEqualTo(results.get(0).resultingEntityId());
        assertThat(activePlan.plan().aggregateVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("Coaching period transition before accept: throws AiRunLifecycleConflictException")
    void periodTransitionBeforeAccept_throwsConflict() {
        UUID studentId = person("STUDENT");
        UUID period1 = createSelfDirectedCoachingPeriod(studentId);

        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                studentId, studentId, "gen-period-trans", Map.of()
        ));

        // End period1 and start period2
        jdbc.update("UPDATE fitness.coaching_periods SET ended_at = clock_timestamp() WHERE id = ?", period1);
        UUID period2 = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,created_by) VALUES (?,?,'SELF_DIRECTED',clock_timestamp(),?)",
                period2, studentId, studentId);

        assertThatThrownBy(() -> proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "accept-trans", "Accept"
        ))).isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Coaching period has transitioned");
    }

    @Test
    @DisplayName("Mode transition before accept (SELF_DIRECTED -> HUMAN_COACH): student loses authority (403)")
    void modeTransitionBeforeAccept_studentLosesAuthority() {
        UUID studentId = person("STUDENT");
        UUID period1 = createSelfDirectedCoachingPeriod(studentId);

        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                studentId, studentId, "gen-mode-trans", Map.of()
        ));

        // Transition student to HUMAN_COACH
        jdbc.update("UPDATE fitness.coaching_periods SET ended_at = clock_timestamp() WHERE id = ?", period1);
        UUID trainerId = person("TRAINER");
        UUID relId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                (id,student_id,trainer_id,status,requested_by,requested_at,accepted_at,started_at)
                VALUES (?,?,?,'ACTIVE',?,clock_timestamp(),clock_timestamp(),clock_timestamp())
                """, relId, studentId, trainerId, studentId);
        UUID period2 = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_periods
                (id,student_id,mode,coaching_relationship_id,trainer_id,started_at,created_by)
                VALUES (?,?,'HUMAN_COACH',?,?,clock_timestamp(),?)
                """, period2, studentId, relId, trainerId, trainerId);

        // Student tries to accept -> Denied because current mode is HUMAN_COACH
        assertThatThrownBy(() -> proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "accept-after-transition", "Accept"
        ))).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Actor who lost authority cannot replay previously accepted recommendation")
    void revokedAuthority_cannotReplay() {
        UUID studentId = person("STUDENT");
        UUID period1 = createSelfDirectedCoachingPeriod(studentId);

        WorkoutProposalResult proposal = proposalService.generateProposal(new GenerateProposalCommand(
                studentId, studentId, "gen-revoked-replay", Map.of()
        ));

        // Accept while having authority
        AcceptRecommendationResult acceptResult = proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "replay-key-auth", "Accepted"
        ));
        assertThat(acceptResult.replayed()).isFalse();

        // End period1 and change student coaching context to a new period with HUMAN_COACH
        jdbc.update("UPDATE fitness.coaching_periods SET ended_at = clock_timestamp() WHERE id = ?", period1);
        UUID trainerId = person("TRAINER");
        UUID relId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                (id,student_id,trainer_id,status,requested_by,requested_at,accepted_at,started_at)
                VALUES (?,?,?,'ACTIVE',?,clock_timestamp(),clock_timestamp(),clock_timestamp())
                """, relId, studentId, trainerId, studentId);
        UUID period2 = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_periods
                (id,student_id,mode,coaching_relationship_id,trainer_id,started_at,created_by)
                VALUES (?,?,'HUMAN_COACH',?,?,clock_timestamp(),?)
                """, period2, studentId, relId, trainerId, trainerId);

        // Student attempts to replay with identical commandKey -> Access Denied / Conflict prior to replay
        assertThatThrownBy(() -> proposalService.acceptRecommendation(new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "replay-key-auth", "Accepted"
        ))).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Full slice Edit Draft: Student modifies proposal -> increments revision to 2 -> accept applies modified version")
    void editDraft_andApplyEditedVersion_succeeds() {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);

        // 1. Generate Proposal
        GenerateProposalCommand genCmd = new GenerateProposalCommand(
                studentId, studentId, "gen-key-edit-1", Map.of("daysPerWeek", 3)
        );
        WorkoutProposalResult proposal = proposalService.generateProposal(genCmd);
        assertThat(proposal.recommendationId()).isNotNull();

        // 2. Modify draft payload
        String originalJson = proposal.structuredPayload();
        String modifiedJson = originalJson.replace("1-Week Full Body Starter", "Customized Strength Plan");

        AiWorkoutProposalUseCase.UpdateDraftCommand editCmd = new AiWorkoutProposalUseCase.UpdateDraftCommand(
                studentId,
                proposal.recommendationId(),
                1,
                "Customized Strength Plan",
                "Customized description",
                modifiedJson
        );
        AiWorkoutProposalUseCase.UpdateDraftResult editResult = proposalService.updateDraft(editCmd);

        assertThat(editResult.revision()).isEqualTo(2);
        assertThat(editResult.isCustomized()).isTrue();
        assertThat(editResult.title()).isEqualTo("Customized Strength Plan");
        assertThat(editResult.originalPayload()).isEqualTo(originalJson);

        // Verify in DB directly
        AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(rec.revision()).isEqualTo(2);
        assertThat(rec.isCustomized()).isTrue();
        assertThat(rec.lastModifiedBy()).isEqualTo(studentId);
        assertThat(rec.title()).isEqualTo("Customized Strength Plan");

        // 3. Accept modified proposal with expectedRevision = 2
        AcceptRecommendationCommand acceptCmd = new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "accept-key-edit-1", "Applying edited plan", 2
        );
        AcceptRecommendationResult acceptResult = proposalService.acceptRecommendation(acceptCmd);
        assertThat(acceptResult.status()).isEqualTo(AiRecommendationStatus.APPLIED);

        // 4. Verify resulting plan has modified title
        var activePlan = workoutPlanQuery.current(studentId, studentId);
        assertThat(activePlan).isNotNull();
        assertThat(activePlan.plan().name()).isEqualTo("Customized Strength Plan");

        // 5. Verify application receipt has appliedRevision: 2, isCustomized: true
        var appOpt = recommendationRepository.findApplicationByRecommendationId(proposal.recommendationId());
        assertThat(appOpt).isPresent();
        assertThat(appOpt.get().applicationPayload()).contains("\"appliedRevision\": 2");
        assertThat(appOpt.get().applicationPayload()).contains("\"isCustomized\": true");
    }

    @Test
    @DisplayName("Full slice Edit Draft: Stale revision conflict throws 409 and does not overwrite")
    void editDraft_staleRevision_throwsConflict() {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);

        WorkoutProposalResult proposal = proposalService.generateProposal(
                new GenerateProposalCommand(studentId, studentId, "gen-key-stale-1", Map.of("daysPerWeek", 3))
        );

        // Edit once -> moves to revision 2
        proposalService.updateDraft(new AiWorkoutProposalUseCase.UpdateDraftCommand(
                studentId, proposal.recommendationId(), 1, "Rev 2", "Desc", proposal.structuredPayload()
        ));

        // Stale edit with expectedRevision = 1
        assertThatThrownBy(() -> proposalService.updateDraft(new AiWorkoutProposalUseCase.UpdateDraftCommand(
                studentId, proposal.recommendationId(), 1, "Stale Rev 1", "Desc", proposal.structuredPayload()
        )))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Revision conflict: expected revision 1 but current is 2");
    }

    @Test
    @DisplayName("Full slice Accept Recommendation: Stale revision causes conflict and rolls back")
    void acceptRecommendation_staleRevision_causesConflictAndRollback() {
        UUID studentId = person("STUDENT");
        createSelfDirectedCoachingPeriod(studentId);

        WorkoutProposalResult proposal = proposalService.generateProposal(
                new GenerateProposalCommand(studentId, studentId, "gen-key-stale-acc", Map.of("daysPerWeek", 3))
        );

        // Edit to revision 2
        proposalService.updateDraft(new AiWorkoutProposalUseCase.UpdateDraftCommand(
                studentId, proposal.recommendationId(), 1, "Rev 2", "Desc", proposal.structuredPayload()
        ));

        // Attempt to accept with expectedRevision = 1
        AcceptRecommendationCommand acceptCmd = new AcceptRecommendationCommand(
                studentId, proposal.recommendationId(), "accept-stale", "Note", 1
        );

        assertThatThrownBy(() -> proposalService.acceptRecommendation(acceptCmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Revision conflict: expected revision 1 but current is 2");

        // Verify recommendation remains PENDING and no active plan is created
        AiRecommendation rec = recommendationRepository.findById(proposal.recommendationId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThatThrownBy(() -> workoutPlanQuery.current(studentId, studentId)).isInstanceOf(WorkoutPlanFailure.class);
    }
}
