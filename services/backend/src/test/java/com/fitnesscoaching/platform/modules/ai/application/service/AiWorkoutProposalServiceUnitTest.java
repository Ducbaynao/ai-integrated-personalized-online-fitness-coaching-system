package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.SimulatedF02AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.AcceptRecommendationCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.AcceptRecommendationResult;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.GenerateProposalCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.RejectRecommendationCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.RejectRecommendationResult;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.UpdateDraftCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.UpdateDraftResult;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.WorkoutProposalResult;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F02AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationApplication;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.CurrentCoachingContext;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.Mode;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.CommandResult;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanSummary;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.ReadContext;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.PublishVersionCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.DecisionOwnerType;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F02AiServiceClient.F02ServiceRequest;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import java.time.LocalDate;
import com.fitnesscoaching.platform.modules.ai.domain.F02SourceSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import org.springframework.beans.factory.ObjectProvider;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AiWorkoutProposalServiceUnitTest {

    @Mock
    private AiRunRepository aiRunRepository;
    @Mock
    private AiRecommendationRepository recommendationRepository;
    @Mock
    private F02ContextBuilder contextBuilder;
    @Mock
    private F02RuntimeOutputValidator outputValidator;
    @Mock
    private WorkoutPlanCommandUseCase workoutPlanCommandUseCase;
    @Mock
    private WorkoutPlanQueryUseCase workoutPlanQueryUseCase;
    @Mock
    private AuditService auditService;
    @Mock
    private CurrentCoachingContextQuery coachingContextQuery;
    @Mock
    private UserAccountStatusQuery accountStatusQuery;
    @Mock
    private ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider;
    @Mock
    private GetCurrentFitnessGoalUseCase goalService;

    private ObjectMapper objectMapper;
    private F02AiServiceClient aiServiceClient;
    private AiWorkoutProposalService service;

    private final UUID studentId = UUID.randomUUID();
    private final UUID trainerId = UUID.randomUUID();
    private final UUID periodId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aiServiceClient = new SimulatedF02AiServiceClient(objectMapper);
        lenient().when(accountStatusQuery.getAccountStatus(any())).thenReturn(Optional.of(AccountStatus.ACTIVE));
        lenient().when(goalProvider.getIfAvailable()).thenReturn(goalService);
        lenient().when(goalService.getCurrentFitnessGoal(any())).thenAnswer(inv -> {
            UUID s = inv.getArgument(0);
            UUID g = UUID.randomUUID();
            FitnessGoalVersion v = new FitnessGoalVersion(
                    UUID.randomUUID(), g, 1, LocalDate.now(), LocalDate.now().plusMonths(3), 90,
                    Instant.now(), null, null, "Initial", "v1", s, null, Instant.now(), null, null, null, List.of(), List.of()
            );
            return Optional.of(new FitnessGoal(
                    g, s, "Goal", GoalStatus.ACTIVE, s, Instant.now(), null, null, null, null, Instant.now(), Instant.now(), v
            ));
        });
        lenient().when(goalService.getCurrentFitnessGoal(any(), anyBoolean())).thenAnswer(inv -> {
            UUID s = inv.getArgument(0);
            UUID g = UUID.randomUUID();
            FitnessGoalVersion v = new FitnessGoalVersion(
                    UUID.randomUUID(), g, 1, LocalDate.now(), LocalDate.now().plusMonths(3), 90,
                    Instant.now(), null, null, "Initial", "v1", s, null, Instant.now(), null, null, null, List.of(), List.of()
            );
            return Optional.of(new FitnessGoal(
                    g, s, "Goal", GoalStatus.ACTIVE, s, Instant.now(), null, null, null, null, Instant.now(), Instant.now(), v
            ));
        });
        lenient().when(goalService.lockAndVerifyActiveGoal(any(), any(), anyInt())).thenAnswer(inv -> {
            UUID s = inv.getArgument(0);
            UUID g = inv.getArgument(1);
            int v = inv.getArgument(2);
            FitnessGoalVersion fv = new FitnessGoalVersion(
                    UUID.randomUUID(), g, v, LocalDate.now(), LocalDate.now().plusMonths(3), 90,
                    Instant.now(), null, null, "Initial", "v" + v, s, null, Instant.now(), null, null, null, List.of(), List.of()
            );
            return Optional.of(new FitnessGoal(
                    g, s, "Goal", GoalStatus.ACTIVE, s, Instant.now(), null, null, null, null, Instant.now(), Instant.now(), fv
            ));
        });
        lenient().when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));
        lenient().when(outputValidator.validate(any(), any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));
        service = new AiWorkoutProposalService(
                aiRunRepository,
                recommendationRepository,
                aiServiceClient,
                contextBuilder,
                outputValidator,
                workoutPlanCommandUseCase,
                workoutPlanQueryUseCase,
                auditService,
                objectMapper,
                coachingContextQuery,
                accountStatusQuery,
                goalProvider
        );
    }

    @Test
    @DisplayName("SELF_DIRECTED mode: Student generates proposal successfully")
    void selfDirected_studentGeneratesProposal_succeeds() {
        when(contextBuilder.resolveCoachingContext(studentId))
                .thenReturn(new F02ContextBuilder.CoachingContextDetails(periodId, "SELF_DIRECTED", null));
        F02SourceSnapshot snapshot = new F02SourceSnapshot(
                UUID.randomUUID(), 1, null, null, periodId, "SELF_DIRECTED", null,
                TrainingContinuityState.CONFIRMED_NEW, null, Instant.now()
        );
        F02ServiceRequest req = new F02ServiceRequest(studentId, periodId, "SELF_DIRECTED", "INTERMEDIATE", "GENERAL_FITNESS", List.of(), Map.of(), List.of());
        when(contextBuilder.buildContextEnvelope(eq(studentId), any(), any(), any()))
                .thenReturn(new F02ContextBuilder.ContextEnvelopeResult(req, snapshot));
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));
        when(recommendationRepository.save(any(AiRecommendation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        GenerateProposalCommand cmd = new GenerateProposalCommand(studentId, studentId, "cmd-key-1", Map.of("daysPerWeek", 3));
        WorkoutProposalResult result = service.generateProposal(cmd);

        assertThat(result).isNotNull();
        assertThat(result.recommendationId()).isNotNull();
        assertThat(result.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(result.decisionAuthority()).isEqualTo(AiDecisionAuthority.STUDENT);
        assertThat(result.title()).isNotBlank();
        assertThat(result.explanation()).isNotBlank();
        assertThat(result.expiresAt()).isNotNull();
        assertThat(result.expiresAt()).isEqualTo(result.createdAt().plus(24, ChronoUnit.HOURS));

        verify(aiRunRepository).create(any(AiRun.class));
        verify(aiRunRepository).completeRun(any(), eq(AiRunStatus.SUCCEEDED), any(), isNull(), isNull(), isNull(), isNull(), anyInt(), any());
        verify(recommendationRepository).save(any(AiRecommendation.class));
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("SELF_DIRECTED mode: Trainer requests proposal -> Access Denied")
    void selfDirected_trainerGeneratesProposal_throwsAccessDenied() {
        when(contextBuilder.resolveCoachingContext(studentId))
                .thenReturn(new F02ContextBuilder.CoachingContextDetails(periodId, "SELF_DIRECTED", null));

        GenerateProposalCommand cmd = new GenerateProposalCommand(trainerId, studentId, "cmd-key-2", Map.of());
        assertThatThrownBy(() -> service.generateProposal(cmd))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("In SELF_DIRECTED mode, only the student may request a workout proposal.");

        verify(aiRunRepository, never()).create(any());
    }

    @Test
    @DisplayName("HUMAN_COACH mode: Assigned coach generates proposal successfully")
    void humanCoach_assignedTrainerGeneratesProposal_succeeds() {
        when(contextBuilder.resolveCoachingContext(studentId))
                .thenReturn(new F02ContextBuilder.CoachingContextDetails(periodId, "HUMAN_COACH", trainerId));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.HUMAN_COACH, null, trainerId, Instant.now(), null)));
        F02SourceSnapshot snapshot = new F02SourceSnapshot(
                UUID.randomUUID(), 1, null, null, periodId, "HUMAN_COACH", trainerId,
                TrainingContinuityState.RECENT_CONSISTENT, 3, Instant.now()
        );
        F02ServiceRequest req = new F02ServiceRequest(studentId, periodId, "HUMAN_COACH", "ADVANCED", "MUSCLE_GAIN", List.of(), Map.of(), List.of());
        when(contextBuilder.buildContextEnvelope(eq(studentId), any(), any(), any()))
                .thenReturn(new F02ContextBuilder.ContextEnvelopeResult(req, snapshot));
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));
        when(recommendationRepository.save(any(AiRecommendation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        GenerateProposalCommand cmd = new GenerateProposalCommand(trainerId, studentId, "cmd-key-3", Map.of("daysPerWeek", 4));
        WorkoutProposalResult result = service.generateProposal(cmd);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(result.decisionAuthority()).isEqualTo(AiDecisionAuthority.TRAINER);

        verify(aiRunRepository).create(any(AiRun.class));
        verify(recommendationRepository).save(any(AiRecommendation.class));
    }

    @Test
    @DisplayName("HUMAN_COACH mode: Student requests proposal -> Access Denied")
    void humanCoach_studentGeneratesProposal_throwsAccessDenied() {
        when(contextBuilder.resolveCoachingContext(studentId))
                .thenReturn(new F02ContextBuilder.CoachingContextDetails(periodId, "HUMAN_COACH", trainerId));

        GenerateProposalCommand cmd = new GenerateProposalCommand(studentId, studentId, "cmd-key-4", Map.of());
        assertThatThrownBy(() -> service.generateProposal(cmd))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("In HUMAN_COACH mode, only the assigned coach may request a workout proposal.");

        verify(aiRunRepository, never()).create(any());
    }

    @Test
    @DisplayName("Accept recommendation: Atomically invokes workout commands, updates recommendation, saves receipt and audit")
    void acceptRecommendation_succeeds() {
        UUID recId = UUID.randomUUID();
        UUID createdPlanId = UUID.randomUUID();
        UUID createdVersionId = UUID.randomUUID();
        Instant now = Instant.now();

        String proposalPayload = aiServiceClient.generateProposalJson(
                new F02AiServiceClient.F02ServiceRequest(studentId, periodId, "SELF_DIRECTED", "BEGINNER", "GENERAL_FITNESS", List.of(), Map.of(), List.of())
        );

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload(proposalPayload)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));

        when(workoutPlanCommandUseCase.createDraft(any()))
                .thenReturn(new CommandResult(createdPlanId, createdVersionId, 1, WorkoutPlanStatus.DRAFT, 0L, now, false));
        when(workoutPlanCommandUseCase.activate(any()))
                .thenReturn(new CommandResult(createdPlanId, createdVersionId, 1, WorkoutPlanStatus.ACTIVE, 1L, now, false));

        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(studentId, recId, "accept-cmd-1", "Looks great!", 1);
        AcceptRecommendationResult result = service.acceptRecommendation(cmd);

        assertThat(result.recommendationId()).isEqualTo(recId);
        assertThat(result.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(result.resultingEntityId()).isEqualTo(createdPlanId);
        assertThat(result.replayed()).isFalse();

        verify(outputValidator).validate(eq(proposalPayload), any());
        verify(workoutPlanCommandUseCase).createDraft(any());
        verify(workoutPlanCommandUseCase).activate(any());
        verify(recommendationRepository).updateDecision(eq(recId), eq(AiRecommendationStatus.APPLIED), eq(studentId), any(), eq("Looks great!"), eq("WORKOUT_PLAN"), eq(createdPlanId));
        verify(recommendationRepository).saveApplication(any(AiRecommendationApplication.class));
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("Update draft: Validates customized proposal, increments revision and records audit")
    void selfDirected_studentUpdatesDraft_succeeds() {
        UUID recId = UUID.randomUUID();
        Instant now = Instant.now();

        String originalPayload = aiServiceClient.generateProposalJson(
                new F02AiServiceClient.F02ServiceRequest(studentId, periodId, "SELF_DIRECTED", "BEGINNER", "GENERAL_FITNESS", List.of(), Map.of(), List.of())
        );

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("Original Plan")
                .explanation("Original Exp")
                .structuredPayload(originalPayload)
                .originalPayload(originalPayload)
                .revision(1)
                .isCustomized(false)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, now, null)));

        String modifiedPayload = originalPayload.replace("Session 1", "Modified Session 1");
        UpdateDraftCommand cmd = new UpdateDraftCommand(studentId, recId, 1, "Customized Plan", "Customized Exp", modifiedPayload);

        UpdateDraftResult result = service.updateDraft(cmd);

        assertThat(result.recommendationId()).isEqualTo(recId);
        assertThat(result.revision()).isEqualTo(2);
        assertThat(result.isCustomized()).isTrue();
        assertThat(result.title()).isEqualTo("Customized Plan");
        assertThat(result.explanation()).isEqualTo("Customized Exp");
        assertThat(result.structuredPayload()).isEqualTo(modifiedPayload);
        assertThat(result.originalPayload()).isEqualTo(originalPayload);

        verify(recommendationRepository).updateDraft(recId, 2, "Customized Plan", "Customized Exp", modifiedPayload, studentId);
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("Update draft: Revision conflict throws lifecycle conflict exception")
    void updateDraft_revisionConflict_throwsConflict() {
        UUID recId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .revision(2)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .structuredPayload("{}")
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, now, null)));

        UpdateDraftCommand cmd = new UpdateDraftCommand(studentId, recId, 1, "New Title", "New Exp", "{}");

        assertThatThrownBy(() -> service.updateDraft(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Revision conflict: expected revision 1 but current is 2");

        verify(recommendationRepository, never()).updateDraft(any(), any(int.class), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Update draft: Output validator failure throws AiOutputValidationException")
    void updateDraft_outputValidationFails_throwsAiOutputValidationException() {
        UUID recId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .revision(1)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .structuredPayload("{}")
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, now, null)));
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.invalid("Invalid variation"));

        UpdateDraftCommand cmd = new UpdateDraftCommand(studentId, recId, 1, "New Title", "New Exp", "{\"invalid\":true}");

        assertThatThrownBy(() -> service.updateDraft(cmd))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("Draft payload validation failed: Invalid variation");

        verify(recommendationRepository, never()).updateDraft(any(), any(int.class), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Accept recommendation: Revision conflict on accept throws lifecycle conflict exception")
    void acceptRecommendation_expectedRevisionMismatch_throwsConflict() {
        UUID recId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .revision(2)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .structuredPayload("{}")
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, now, null)));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(studentId, recId, "cmd-1", "Note", 1);

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Revision conflict: expected revision 1 but current is 2");

        verify(workoutPlanCommandUseCase, never()).createDraft(any());
    }

    @Test
    @DisplayName("Accept recommendation: Re-validation fails before applying throws AiOutputValidationException")
    void acceptRecommendation_outputValidationFailsOnApply_throwsAiOutputValidationException() {
        UUID recId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .revision(1)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .structuredPayload("{}")
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, now, null)));
        when(outputValidator.validate(any(), any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.invalid("Exercise variation retired"));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(studentId, recId, "cmd-1", "Note", 1);

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("Recommendation cannot be applied because payload is no longer valid: Exercise variation retired");

        verify(workoutPlanCommandUseCase, never()).createDraft(any());
    }

    @Test
    @DisplayName("Accept recommendation: Idempotent replay returns same result without creating new plan")
    void acceptRecommendation_idempotentReplay() {
        UUID recId = UUID.randomUUID();
        UUID existingPlanId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.APPLIED)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .structuredPayload("{}")
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));

        String savedPayload = """
                {
                  "commandKey": "accept-cmd-1",
                  "actorId": "%s",
                  "decisionNote": "Approved"
                }
                """.formatted(studentId);

        AiRecommendationApplication app = new AiRecommendationApplication(
                UUID.randomUUID(), recId, studentId, "WORKOUT_PLAN", existingPlanId, savedPayload, now
        );
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.of(app));

        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(studentId, recId, "accept-cmd-1", "Approved");
        AcceptRecommendationResult result = service.acceptRecommendation(cmd);

        assertThat(result.recommendationId()).isEqualTo(recId);
        assertThat(result.replayed()).isTrue();
        assertThat(result.resultingEntityId()).isEqualTo(existingPlanId);

        verify(workoutPlanCommandUseCase, never()).createDraft(any());
        verify(workoutPlanCommandUseCase, never()).activate(any());
    }

    @Test
    @DisplayName("Accept recommendation: Idempotency conflict with different payload throws exception")
    void acceptRecommendation_idempotencyConflict() {
        UUID recId = UUID.randomUUID();
        UUID existingPlanId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.APPLIED)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .structuredPayload("{}")
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));

        String savedPayload = """
                {
                  "commandKey": "accept-cmd-1",
                  "actorId": "%s",
                  "decisionNote": "Initial Approval"
                }
                """.formatted(studentId);

        AiRecommendationApplication app = new AiRecommendationApplication(
                UUID.randomUUID(), recId, studentId, "WORKOUT_PLAN", existingPlanId, savedPayload, now
        );
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.of(app));

        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(studentId, recId, "accept-cmd-1", "Differing note");

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Idempotency conflict");
    }

    @Test
    @DisplayName("Accept recommendation: Missing expectedRevision when draft modified throws conflict")
    void acceptRecommendation_whenDraftModifiedAndExpectedRevisionMissing_throwsConflict() {
        UUID recId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("Modified Plan")
                .explanation("Details")
                .structuredPayload("{}")
                .revision(2)
                .isCustomized(true)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());

        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        // Command without expectedRevision (null)
        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(
                studentId, recId, "accept-cmd-missing-rev", "Approving without specifying revision"
        );

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("expectedRevision is required when accepting");
    }

    @Test
    @DisplayName("Accept recommendation: Replay with differing expectedRevision throws lifecycle conflict")
    void acceptRecommendation_replayWithDifferentRevision_throwsConflict() {
        UUID recId = UUID.randomUUID();
        UUID existingPlanId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.APPLIED)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("Applied Plan")
                .explanation("Details")
                .structuredPayload("{}")
                .revision(2)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));

        String savedPayload = """
                {
                  "commandKey": "accept-cmd-rev-diff",
                  "actorId": "%s",
                  "decisionNote": "Initial Approval",
                  "appliedRevision": 2
                }
                """.formatted(studentId);

        AiRecommendationApplication app = new AiRecommendationApplication(
                UUID.randomUUID(), recId, studentId, "WORKOUT_PLAN", existingPlanId, savedPayload, now
        );
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.of(app));

        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        // Replay with expectedRevision = 1 (differing from appliedRevision 2)
        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(
                studentId, recId, "accept-cmd-rev-diff", "Initial Approval", 1
        );

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Idempotency conflict");
    }

    @Test
    @DisplayName("Accept recommendation: Audit service failure bubbles up and aborts execution")
    void acceptRecommendation_auditServiceFailure_throwsException() {
        UUID recId = UUID.randomUUID();
        UUID createdPlanId = UUID.randomUUID();
        UUID createdVersionId = UUID.randomUUID();
        Instant now = Instant.now();

        String proposalPayload = aiServiceClient.generateProposalJson(
                new F02AiServiceClient.F02ServiceRequest(studentId, periodId, "SELF_DIRECTED", "BEGINNER", "GENERAL_FITNESS", List.of(), Map.of(), List.of())
        );

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("Proposed Workout Plan")
                .explanation("Details")
                .structuredPayload("{\"plan\":{\"name\":\"Test Plan\",\"sessions\":[]}}")
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));

        when(workoutPlanCommandUseCase.createDraft(any()))
                .thenReturn(new CommandResult(createdPlanId, createdVersionId, 1, WorkoutPlanStatus.DRAFT, 0L, now, false));
        when(workoutPlanCommandUseCase.activate(any()))
                .thenReturn(new CommandResult(createdPlanId, createdVersionId, 1, WorkoutPlanStatus.ACTIVE, 1L, now, false));

        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        doThrow(new RuntimeException("Audit persistence failed")).when(auditService).recordAudit(any());

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(studentId, recId, "accept-cmd-fail-audit", "Note");

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Audit persistence failed");
    }

    @Test
    @DisplayName("Reject recommendation: Transitions to REJECTED and records audit")
    void rejectRecommendation_succeeds() {
        UUID recId = UUID.randomUUID();
        Instant now = Instant.now();
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .structuredPayload("{}")
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        RejectRecommendationCommand cmd = new RejectRecommendationCommand(studentId, recId, "Volume too high for this week");
        RejectRecommendationResult result = service.rejectRecommendation(cmd);

        assertThat(result.status()).isEqualTo(AiRecommendationStatus.REJECTED);
        assertThat(result.decisionNote()).isEqualTo("Volume too high for this week");
        verify(recommendationRepository).updateDecision(eq(recId), eq(AiRecommendationStatus.REJECTED), eq(studentId), any(), eq("Volume too high for this week"), any(), any());
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("Accept recommendation when active plan exists (SELF_DIRECTED): Publishes new version on existing plan")
    void selfDirected_studentAcceptsProposal_whenActivePlanExists_publishesNewVersion() {
        UUID recId = UUID.randomUUID();
        UUID activePlanId = UUID.randomUUID();
        UUID newVersionId = UUID.randomUUID();
        Instant now = Instant.now();

        String proposalPayload = aiServiceClient.generateProposalJson(
                new F02AiServiceClient.F02ServiceRequest(studentId, periodId, "SELF_DIRECTED", "BEGINNER", "GENERAL_FITNESS", List.of(), Map.of(), List.of())
        );

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Updated Plan")
                .explanation("Details")
                .structuredPayload("{\"plan\":{\"name\":\"Test Plan\",\"sessions\":[]}}")
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));

        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        PlanSummary activeSummary = new PlanSummary(
                activePlanId, studentId, null, periodId, "Current Active Plan", "Running", "STUDENT",
                WorkoutPlanStatus.ACTIVE, 1L, DecisionOwnerType.STUDENT, studentId, null, null,
                now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), null,
                UUID.randomUUID(), 1, now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), ReadContext.CURRENT
        );
        when(workoutPlanQueryUseCase.findActive(studentId, studentId))
                .thenReturn(Optional.of(new PlanDetail(activeSummary, null)));

        when(workoutPlanCommandUseCase.publishVersion(any()))
                .thenReturn(new CommandResult(activePlanId, newVersionId, 2, WorkoutPlanStatus.ACTIVE, 2L, now, false));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(studentId, recId, "accept-publish-key", "Apply update", 1);
        AcceptRecommendationResult result = service.acceptRecommendation(cmd);

        assertThat(result.recommendationId()).isEqualTo(recId);
        assertThat(result.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(result.resultingEntityId()).isEqualTo(activePlanId);
        assertThat(result.replayed()).isFalse();

        verify(workoutPlanCommandUseCase).publishVersion(any(PublishVersionCommand.class));
        verify(workoutPlanCommandUseCase, never()).createDraft(any());
        verify(workoutPlanCommandUseCase, never()).activate(any());
        verify(recommendationRepository).updateDecision(eq(recId), eq(AiRecommendationStatus.APPLIED), eq(studentId), any(), eq("Apply update"), eq("WORKOUT_PLAN"), eq(activePlanId));
        verify(recommendationRepository).saveApplication(any(AiRecommendationApplication.class));
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("Accept recommendation when active plan exists (HUMAN_COACH): Trainer publishes new version on managed plan")
    void humanCoach_trainerAcceptsProposal_whenActivePlanExists_publishesNewVersion() {
        UUID recId = UUID.randomUUID();
        UUID activePlanId = UUID.randomUUID();
        UUID newVersionId = UUID.randomUUID();
        Instant now = Instant.now();

        String proposalPayload = aiServiceClient.generateProposalJson(
                new F02AiServiceClient.F02ServiceRequest(studentId, periodId, "HUMAN_COACH", "INTERMEDIATE", "GENERAL_FITNESS", List.of(), Map.of(), List.of())
        );

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.TRAINER)
                .title("Trainer 1-Week Update")
                .explanation("Details")
                .structuredPayload("{\"plan\":{\"name\":\"Test Plan\",\"sessions\":[]}}")
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));

        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, now, null
                )));

        PlanSummary activeSummary = new PlanSummary(
                activePlanId, studentId, null, periodId, "Trainer Active Plan", "Running", "TRAINER",
                WorkoutPlanStatus.ACTIVE, 1L, DecisionOwnerType.TRAINER, trainerId, null, null,
                now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), null,
                UUID.randomUUID(), 1, now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), ReadContext.CURRENT
        );
        when(workoutPlanQueryUseCase.findActive(trainerId, studentId))
                .thenReturn(Optional.of(new PlanDetail(activeSummary, null)));

        when(workoutPlanCommandUseCase.publishVersion(any()))
                .thenReturn(new CommandResult(activePlanId, newVersionId, 2, WorkoutPlanStatus.ACTIVE, 2L, now, false));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(trainerId, recId, "accept-trainer-key", "Trainer approved", 1);
        AcceptRecommendationResult result = service.acceptRecommendation(cmd);

        assertThat(result.recommendationId()).isEqualTo(recId);
        assertThat(result.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(result.resultingEntityId()).isEqualTo(activePlanId);

        verify(workoutPlanCommandUseCase).publishVersion(any(PublishVersionCommand.class));
        verify(workoutPlanCommandUseCase, never()).createDraft(any());
    }

    @Test
    @DisplayName("Accept recommendation: When publishVersion fails due to successor required conflict, failure bubbles up")
    void acceptRecommendation_whenPublishVersionThrowsSuccessorRequired_bubblesUp() {
        UUID recId = UUID.randomUUID();
        UUID activePlanId = UUID.randomUUID();
        Instant now = Instant.now();

        String proposalPayload = aiServiceClient.generateProposalJson(
                new F02AiServiceClient.F02ServiceRequest(studentId, periodId, "SELF_DIRECTED", "BEGINNER", "GENERAL_FITNESS", List.of(), Map.of(), List.of())
        );

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload(proposalPayload)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));

        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        PlanSummary activeSummary = new PlanSummary(
                activePlanId, studentId, null, periodId, "Trainer Owned Plan", "Running", "TRAINER",
                WorkoutPlanStatus.ACTIVE, 1L, DecisionOwnerType.TRAINER, trainerId, null, null,
                now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), null,
                UUID.randomUUID(), 1, now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), ReadContext.CURRENT
        );
        when(workoutPlanQueryUseCase.findActive(studentId, studentId))
                .thenReturn(Optional.of(new PlanDetail(activeSummary, null)));

        when(workoutPlanCommandUseCase.publishVersion(any()))
                .thenThrow(new WorkoutPlanFailure(WorkoutPlanError.WORKOUT_PLAN_SUCCESSOR_REQUIRED, "Successor required"));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(studentId, recId, "accept-fail-successor", "Note", 1);

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(WorkoutPlanFailure.class)
                .extracting(ex -> ((WorkoutPlanFailure) ex).error())
                .isEqualTo(WorkoutPlanError.WORKOUT_PLAN_SUCCESSOR_REQUIRED);

        verify(recommendationRepository, never()).updateDecision(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Accept recommendation: Active plan appeared after preview throws conflict")
    void acceptRecommendation_whenActivePlanAppearedAfterPreview_throwsConflict() {
        UUID recId = UUID.randomUUID();
        UUID activePlanId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload("{\"plan\":{\"name\":\"Test Plan\",\"sessions\":[]}}")
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        PlanSummary activeSummary = new PlanSummary(
                activePlanId, studentId, null, periodId, "Newly Created Plan", "Running", "STUDENT",
                WorkoutPlanStatus.ACTIVE, 1L, DecisionOwnerType.STUDENT, studentId, null, null,
                now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), null,
                UUID.randomUUID(), 1, now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), ReadContext.CURRENT
        );
        when(workoutPlanQueryUseCase.findActive(studentId, studentId))
                .thenReturn(Optional.of(new PlanDetail(activeSummary, null)));

        // Expected no active plan because user previewed when there was none
        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(
                studentId, recId, "accept-appeared-plan", "Note", 1, null, null, true
        );

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Active workout plan appeared after proposal was previewed");

        verify(workoutPlanCommandUseCase, never()).publishVersion(any());
        verify(workoutPlanCommandUseCase, never()).createDraft(any());
    }

    @Test
    @DisplayName("Accept recommendation: Active plan aggregate version changed throws conflict")
    void acceptRecommendation_whenPlanAggregateVersionChanged_throwsConflict() {
        UUID recId = UUID.randomUUID();
        UUID activePlanId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload("{\"plan\":{\"name\":\"Test Plan\",\"sessions\":[]}}")
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        // Plan in DB has aggregateVersion = 2L
        PlanSummary activeSummary = new PlanSummary(
                activePlanId, studentId, null, periodId, "Active Plan", "Running", "STUDENT",
                WorkoutPlanStatus.ACTIVE, 2L, DecisionOwnerType.STUDENT, studentId, null, null,
                now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), null,
                UUID.randomUUID(), 2, now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), ReadContext.CURRENT
        );
        when(workoutPlanQueryUseCase.findActive(studentId, studentId))
                .thenReturn(Optional.of(new PlanDetail(activeSummary, null)));

        // User saw aggregateVersion = 1L at preview
        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(
                studentId, recId, "accept-stale-plan-ver", "Note", 1, activePlanId, 1L, null
        );

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Workout plan version conflict: expected plan aggregate version 1 but current is 2");

        verify(workoutPlanCommandUseCase, never()).publishVersion(any());
    }

    @Test
    @DisplayName("Accept recommendation: Active plan ID changed throws conflict")
    void acceptRecommendation_whenActivePlanIdChanged_throwsConflict() {
        UUID recId = UUID.randomUUID();
        UUID currentActivePlanId = UUID.randomUUID();
        UUID expectedPlanId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload("{\"plan\":{\"name\":\"Test Plan\",\"sessions\":[]}}")
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        // Plan currently in DB is currentActivePlanId
        PlanSummary activeSummary = new PlanSummary(
                currentActivePlanId, studentId, null, periodId, "Current Plan", "Running", "STUDENT",
                WorkoutPlanStatus.ACTIVE, 1L, DecisionOwnerType.STUDENT, studentId, null, null,
                now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), null,
                UUID.randomUUID(), 1, now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), ReadContext.CURRENT
        );
        when(workoutPlanQueryUseCase.findActive(studentId, studentId))
                .thenReturn(Optional.of(new PlanDetail(activeSummary, null)));

        // User saw expectedPlanId
        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(
                studentId, recId, "accept-mismatched-plan-id", "Note", 1, expectedPlanId, 1L, null
        );

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Active workout plan has changed since preview");

        verify(workoutPlanCommandUseCase, never()).publishVersion(any());
    }

    @Test
    @DisplayName("T04: Actor account inactive prior to generation -> Throws 403 Access Denied")
    void t04_actorAccountInactiveBeforeRequest_throwsAccessDenied() {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        GenerateProposalCommand cmd = new GenerateProposalCommand(studentId, studentId, "cmd-key-inactive", Map.of());
        assertThatThrownBy(() -> service.generateProposal(cmd))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Actor account is not active");

        verify(aiRunRepository, never()).create(any());
    }

    @Test
    @DisplayName("T04: Actor account revoked during provider call -> transitions run to FAILED and throws 403")
    void t04_actorAccountRevokedDuringProviderCall_failsRunAndThrowsAccessDenied() {
        when(contextBuilder.resolveCoachingContext(studentId))
                .thenReturn(new F02ContextBuilder.CoachingContextDetails(periodId, "SELF_DIRECTED", null));
        F02SourceSnapshot snapshot = new F02SourceSnapshot(
                UUID.randomUUID(), 1, null, null, periodId, "SELF_DIRECTED", null,
                TrainingContinuityState.CONFIRMED_NEW, null, Instant.now()
        );
        F02ServiceRequest req = new F02ServiceRequest(studentId, periodId, "SELF_DIRECTED", "BEGINNER", "GENERAL_FITNESS", List.of(), Map.of(), List.of());
        when(contextBuilder.buildContextEnvelope(eq(studentId), any(), any(), any()))
                .thenReturn(new F02ContextBuilder.ContextEnvelopeResult(req, snapshot));
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));

        // Active initially, but revoked at post-provider revalidation
        when(accountStatusQuery.getAccountStatus(studentId))
                .thenReturn(Optional.of(AccountStatus.ACTIVE))
                .thenReturn(Optional.of(AccountStatus.DISABLED));

        GenerateProposalCommand cmd = new GenerateProposalCommand(studentId, studentId, "cmd-key-revoked", Map.of());
        assertThatThrownBy(() -> service.generateProposal(cmd))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Actor account is no longer active");

        verify(aiRunRepository).create(any(AiRun.class));
        verify(aiRunRepository).completeRun(any(), eq(AiRunStatus.FAILED), isNull(), eq("AUTHORITY_REVOKED"),
                contains("no longer active"), isNull(), isNull(), anyInt(), any());
        verify(recommendationRepository, never()).save(any());
    }

    @Test
    @DisplayName("T04: Assigned coach revoked during provider call -> transitions run to FAILED and throws 403")
    void t04_trainerRevokedDuringProviderCall_failsRunAndThrowsAccessDenied() {
        when(contextBuilder.resolveCoachingContext(studentId))
                .thenReturn(new F02ContextBuilder.CoachingContextDetails(periodId, "HUMAN_COACH", trainerId));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.HUMAN_COACH, null, trainerId, Instant.now(), null)));
        F02SourceSnapshot snapshot = new F02SourceSnapshot(
                UUID.randomUUID(), 1, null, null, periodId, "HUMAN_COACH", trainerId,
                TrainingContinuityState.RECENT_CONSISTENT, 2, Instant.now()
        );
        F02ServiceRequest req = new F02ServiceRequest(studentId, periodId, "HUMAN_COACH", "ADVANCED", "MUSCLE_GAIN", List.of(), Map.of(), List.of());
        when(contextBuilder.buildContextEnvelope(eq(studentId), any(), any(), any()))
                .thenReturn(new F02ContextBuilder.ContextEnvelopeResult(req, snapshot));
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));

        // At post-provider revalidation, coach is unassigned
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.empty());

        GenerateProposalCommand cmd = new GenerateProposalCommand(trainerId, studentId, "cmd-key-coach-revoked", Map.of());
        assertThatThrownBy(() -> service.generateProposal(cmd))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Trainer coaching authority revoked");

        verify(aiRunRepository).create(any(AiRun.class));
        verify(aiRunRepository).completeRun(any(), eq(AiRunStatus.FAILED), isNull(), eq("AUTHORITY_REVOKED"),
                contains("authority revoked"), isNull(), isNull(), anyInt(), any());
        verify(recommendationRepository, never()).save(any());
    }

    @Test
    @DisplayName("T04: Actor account inactive when retrieving recommendation -> Throws 403 Access Denied")
    void t04_actorAccountInactiveWhenReadingRecommendation_throwsAccessDenied() {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        UUID recId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getRecommendation(studentId, recId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Actor account is not active");

        verify(recommendationRepository, never()).findById(any());
    }

    @Test
    @DisplayName("T05: Accept recommendation fails with 409 Conflict when Goal version changed since snapshot")
    void t05_acceptRecommendation_whenGoalVersionChangedSinceSnapshot_throwsConflict() {
        UUID recId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        Instant now = Instant.now();

        String snapshotJson = "{\"goalId\":\"" + goalId + "\",\"goalVersionNumber\":1,\"activePlanId\":null}";
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload("{\"plan\":{\"name\":\"Test Plan\",\"sessions\":[]}}")
                .sourceSnapshot(snapshotJson)
                .expiresAt(now.plus(24, ChronoUnit.HOURS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        // Goal in DB has version 2 now
        FitnessGoalVersion v2 = new FitnessGoalVersion(
                UUID.randomUUID(), goalId, 2, LocalDate.now(), LocalDate.now().plusMonths(3), 90,
                now, null, null, "Update", "v2", studentId, null, now, null, null, null, List.of(), List.of()
        );
        FitnessGoal goalV2 = new FitnessGoal(
                goalId, studentId, "Goal", GoalStatus.ACTIVE, studentId, now, null, null, null, null, now, now, v2
        );
        lenient().when(goalService.lockAndVerifyActiveGoal(eq(studentId), eq(goalId), eq(1))).thenReturn(Optional.of(goalV2));
        lenient().when(goalService.lockAndVerifyActiveGoal(any(), any(), anyInt())).thenReturn(Optional.of(goalV2));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(
                studentId, recId, "accept-stale-goal-ver", "Note", 1, null, null, null
        );

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Fitness goal version has changed since proposal was generated (was v1, now v2)");

        verify(workoutPlanCommandUseCase, never()).createDraft(any());
        verify(workoutPlanCommandUseCase, never()).publishVersion(any());
    }

    @Test
    @DisplayName("T05: Accept recommendation fails with 409 Conflict when Goal is no longer ACTIVE")
    void t05_acceptRecommendation_whenGoalNoLongerActiveSinceSnapshot_throwsConflict() {
        UUID recId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        Instant now = Instant.now();

        String snapshotJson = "{\"goalId\":\"" + goalId + "\",\"goalVersionNumber\":1,\"activePlanId\":null}";
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload("{\"plan\":{\"name\":\"Test Plan\",\"sessions\":[]}}")
                .sourceSnapshot(snapshotJson)
                .expiresAt(now.plus(24, ChronoUnit.HOURS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        // Goal in DB is ended / paused / absent
        lenient().when(goalService.lockAndVerifyActiveGoal(any(), any(), anyInt())).thenReturn(Optional.empty());

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(
                studentId, recId, "accept-inactive-goal", "Note", 1, null, null, null
        );

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Active fitness goal is no longer active");

        verify(workoutPlanCommandUseCase, never()).createDraft(any());
    }

    @Test
    @DisplayName("T05: Accept recommendation fails with 409 Conflict when active plan appeared since proposal snapshot")
    void t05_acceptRecommendation_whenActivePlanAppearedSinceSnapshot_throwsConflict() {
        UUID recId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        Instant now = Instant.now();

        // Snapshot had NO active plan
        String snapshotJson = "{\"goalId\":\"" + goalId + "\",\"goalVersionNumber\":1,\"activePlanId\":null}";
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload("{\"plan\":{\"name\":\"Test Plan\",\"sessions\":[]}}")
                .sourceSnapshot(snapshotJson)
                .expiresAt(now.plus(24, ChronoUnit.HOURS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(outputValidator.validate(any())).thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        // Goal is unchanged
        FitnessGoalVersion v1 = new FitnessGoalVersion(
                UUID.randomUUID(), goalId, 1, LocalDate.now(), LocalDate.now().plusMonths(3), 90,
                now, null, null, "Initial", "v1", studentId, null, now, null, null, null, List.of(), List.of()
        );
        FitnessGoal goalV1 = new FitnessGoal(
                goalId, studentId, "Goal", GoalStatus.ACTIVE, studentId, now, null, null, null, null, now, now, v1
        );
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(goalV1));

        // But now an active plan appeared!
        UUID newActivePlanId = UUID.randomUUID();
        PlanSummary activeSummary = new PlanSummary(
                newActivePlanId, studentId, goalId, periodId, "Surprise Active Plan", "Running", "STUDENT",
                WorkoutPlanStatus.ACTIVE, 1L, DecisionOwnerType.STUDENT, studentId, null, null,
                now.minus(1, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), null,
                UUID.randomUUID(), 1, now.minus(1, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), ReadContext.CURRENT
        );
        when(workoutPlanQueryUseCase.findActive(studentId, studentId))
                .thenReturn(Optional.of(new PlanDetail(activeSummary, null)));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(
                studentId, recId, "accept-appeared-plan", "Note", 1, null, null, null
        );

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Active workout plan appeared since proposal was generated");

        verify(workoutPlanCommandUseCase, never()).createDraft(any());
    }

    @Test
    @DisplayName("Fail-Closed: Malformed source snapshot throws 409 Conflict with malformed reason")
    void t05_acceptRecommendation_whenSnapshotMalformed_throwsConflict() {
        UUID recId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload("{\"plan\":{\"name\":\"Test Plan\",\"sessions\":[]}}")
                .sourceSnapshot("{this is completely broken json")
                .expiresAt(now.plus(24, ChronoUnit.HOURS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));
        lenient().when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(
                studentId, recId, "accept-malformed-snap", "Note", 1, null, null, null
        );

        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Source snapshot is malformed and cannot be verified");
    }

    @Test
    @DisplayName("Preview Reload: Active plan aggregate version bumped but client previewed and reloaded new version -> accepts successfully")
    void t05_acceptRecommendation_whenPlanAggregateVersionBumped_butClientReloadedPreview_succeeds() {
        UUID recId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        Instant now = Instant.now();

        String validPayload = aiServiceClient.generateProposalJson(
                new F02AiServiceClient.F02ServiceRequest(studentId, periodId, "SELF_DIRECTED", "BEGINNER", "GENERAL_FITNESS", List.of(), Map.of(), List.of())
        );

        // Snapshot had activePlanId and activePlanAggregateVersion = 1
        String snapshotJson = "{\"goalId\":\"" + goalId + "\",\"goalVersionNumber\":1,\"activePlanId\":\"" + planId + "\",\"activePlanAggregateVersion\":1}";
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload(validPayload)
                .sourceSnapshot(snapshotJson)
                .expiresAt(now.plus(24, ChronoUnit.HOURS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));
        lenient().when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        // Current active plan has been bumped to aggregate version 2!
        PlanSummary bumpedSummary = new PlanSummary(
                planId, studentId, goalId, periodId, "Current Plan", "Running", "STUDENT",
                WorkoutPlanStatus.ACTIVE, 2L, DecisionOwnerType.STUDENT, studentId, null, null,
                now.minus(2, ChronoUnit.HOURS), now.minus(2, ChronoUnit.HOURS), null,
                UUID.randomUUID(), 2, now.minus(2, ChronoUnit.HOURS), now.minus(2, ChronoUnit.HOURS), ReadContext.CURRENT
        );
        when(workoutPlanQueryUseCase.findActive(studentId, studentId))
                .thenReturn(Optional.of(new PlanDetail(bumpedSummary, null)));

        when(workoutPlanCommandUseCase.publishVersion(any()))
                .thenReturn(new CommandResult(planId, UUID.randomUUID(), 3, WorkoutPlanStatus.ACTIVE, 3L, now, false));

        // Client sent expectedPlanAggregateVersion = 2 (proving client reloaded preview)
        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(
                studentId, recId, "accept-reloaded-preview", "Note", 1, planId, 2L, null
        );

        AcceptRecommendationResult result = service.acceptRecommendation(cmd);

        assertThat(result).isNotNull();
        assertThat(result.recommendationId()).isEqualTo(recId);
        verify(workoutPlanCommandUseCase).publishVersion(any());
        assertThat(rec.sourceSnapshot()).isEqualTo(snapshotJson);
    }
}
