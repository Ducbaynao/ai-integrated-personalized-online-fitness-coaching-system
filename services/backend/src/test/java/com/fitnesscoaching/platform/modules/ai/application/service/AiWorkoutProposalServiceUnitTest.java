package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.SimulatedF02AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.AcceptRecommendationCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.AcceptRecommendationResult;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.GenerateProposalCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.RejectRecommendationCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.RejectRecommendationResult;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
    private AuditService auditService;
    @Mock
    private CurrentCoachingContextQuery coachingContextQuery;

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
        service = new AiWorkoutProposalService(
                aiRunRepository,
                recommendationRepository,
                aiServiceClient,
                contextBuilder,
                outputValidator,
                workoutPlanCommandUseCase,
                auditService,
                objectMapper,
                coachingContextQuery
        );
    }

    @Test
    @DisplayName("SELF_DIRECTED mode: Student generates proposal successfully")
    void selfDirected_studentGeneratesProposal_succeeds() {
        when(contextBuilder.resolveCoachingContext(studentId))
                .thenReturn(new F02ContextBuilder.CoachingContextDetails(periodId, "SELF_DIRECTED", null));
        when(contextBuilder.buildRequest(eq(studentId), any(), any()))
                .thenReturn(new F02AiServiceClient.F02ServiceRequest(
                        studentId, periodId, "SELF_DIRECTED", "BEGINNER", "GENERAL_FITNESS", List.of(), Map.of(), List.of()
                ));
        when(outputValidator.validate(any()))
                .thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));

        when(recommendationRepository.save(any(AiRecommendation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        GenerateProposalCommand cmd = new GenerateProposalCommand(studentId, studentId, "cmd-1", Map.of());
        WorkoutProposalResult result = service.generateProposal(cmd);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(result.decisionAuthority()).isEqualTo(AiDecisionAuthority.STUDENT);
        verify(aiRunRepository).create(any(AiRun.class));
        verify(recommendationRepository).save(any(AiRecommendation.class));
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("SELF_DIRECTED mode: Non-student actor is forbidden from generating proposal")
    void selfDirected_trainerGeneratesProposal_throwsAccessDenied() {
        when(contextBuilder.resolveCoachingContext(studentId))
                .thenReturn(new F02ContextBuilder.CoachingContextDetails(periodId, "SELF_DIRECTED", null));

        GenerateProposalCommand cmd = new GenerateProposalCommand(trainerId, studentId, "cmd-1", Map.of());

        assertThatThrownBy(() -> service.generateProposal(cmd))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("only the student may request");

        verify(aiRunRepository, never()).create(any());
    }

    @Test
    @DisplayName("HUMAN_COACH mode: Assigned trainer generates proposal successfully")
    void humanCoach_assignedTrainerGeneratesProposal_succeeds() {
        when(contextBuilder.resolveCoachingContext(studentId))
                .thenReturn(new F02ContextBuilder.CoachingContextDetails(periodId, "HUMAN_COACH", trainerId));
        when(contextBuilder.buildRequest(eq(studentId), any(), any()))
                .thenReturn(new F02AiServiceClient.F02ServiceRequest(
                        studentId, periodId, "HUMAN_COACH", "BEGINNER", "GENERAL_FITNESS", List.of(), Map.of(), List.of()
                ));
        when(outputValidator.validate(any()))
                .thenReturn(F02RuntimeOutputValidator.ValidationResult.valid(List.of()));

        when(recommendationRepository.save(any(AiRecommendation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        GenerateProposalCommand cmd = new GenerateProposalCommand(trainerId, studentId, "cmd-2", Map.of());
        WorkoutProposalResult result = service.generateProposal(cmd);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(AiRecommendationStatus.PENDING);
        assertThat(result.decisionAuthority()).isEqualTo(AiDecisionAuthority.TRAINER);
        verify(recommendationRepository).save(any(AiRecommendation.class));
    }

    @Test
    @DisplayName("HUMAN_COACH mode: Student is forbidden from generating proposal directly")
    void humanCoach_studentGeneratesProposal_throwsAccessDenied() {
        when(contextBuilder.resolveCoachingContext(studentId))
                .thenReturn(new F02ContextBuilder.CoachingContextDetails(periodId, "HUMAN_COACH", trainerId));

        GenerateProposalCommand cmd = new GenerateProposalCommand(studentId, studentId, "cmd-3", Map.of());

        assertThatThrownBy(() -> service.generateProposal(cmd))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("only the assigned coach may request");
    }

    @Test
    @DisplayName("Accept recommendation: Atomic accept-and-apply creates and activates plan")
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
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload(proposalPayload)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());

        when(workoutPlanCommandUseCase.createDraft(any()))
                .thenReturn(new CommandResult(createdPlanId, createdVersionId, 1, WorkoutPlanStatus.DRAFT, 0L, now, false));
        when(workoutPlanCommandUseCase.activate(any()))
                .thenReturn(new CommandResult(createdPlanId, createdVersionId, 1, WorkoutPlanStatus.ACTIVE, 1L, now, false));

        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, now, null
                )));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(studentId, recId, "accept-cmd-1", "Looks great!");
        AcceptRecommendationResult result = service.acceptRecommendation(cmd);

        assertThat(result.recommendationId()).isEqualTo(recId);
        assertThat(result.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(result.resultingEntityId()).isEqualTo(createdPlanId);
        assertThat(result.replayed()).isFalse();

        verify(workoutPlanCommandUseCase).createDraft(any());
        verify(workoutPlanCommandUseCase).activate(any());
        verify(recommendationRepository).updateDecision(eq(recId), eq(AiRecommendationStatus.APPLIED), eq(studentId), any(), eq("Looks great!"), eq("WORKOUT_PLAN"), eq(createdPlanId));
        verify(recommendationRepository).saveApplication(any(AiRecommendationApplication.class));
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("Accept recommendation: Replaying identical commandKey returns replayed receipt without re-executing domain commands")
    void acceptRecommendation_idempotentReplay() {
        UUID recId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Instant appliedAt = Instant.now().minus(1, ChronoUnit.HOURS);

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.APPLIED)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .structuredPayload("{}")
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, appliedAt, null
                )));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.of(
                new AiRecommendationApplication(
                        UUID.randomUUID(), recId, studentId, "WORKOUT_PLAN", planId,
                        "{\"commandKey\":\"accept-cmd-1\",\"actorId\":\"" + studentId + "\",\"decisionNote\":\"\",\"createdPlanId\":\"" + planId + "\"}",
                        appliedAt
                )
        ));

        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(studentId, recId, "accept-cmd-1", null);
        AcceptRecommendationResult result = service.acceptRecommendation(cmd);

        assertThat(result.replayed()).isTrue();
        assertThat(result.resultingEntityId()).isEqualTo(planId);
        verify(workoutPlanCommandUseCase, never()).createDraft(any());
        verify(workoutPlanCommandUseCase, never()).activate(any());
    }

    @Test
    @DisplayName("Accept recommendation: Replay with conflicting payload throws lifecycle conflict")
    void acceptRecommendation_idempotencyConflict() {
        UUID recId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Instant appliedAt = Instant.now().minus(1, ChronoUnit.HOURS);

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.APPLIED)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .structuredPayload("{}")
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                        periodId, CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, appliedAt, null
                )));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.of(
                new AiRecommendationApplication(
                        UUID.randomUUID(), recId, studentId, "WORKOUT_PLAN", planId,
                        "{\"commandKey\":\"accept-cmd-1\",\"actorId\":\"" + studentId + "\",\"decisionNote\":\"Old note\",\"createdPlanId\":\"" + planId + "\"}",
                        appliedAt
                )
        ));

        // Different decisionNote -> conflict
        AcceptRecommendationCommand cmd = new AcceptRecommendationCommand(studentId, recId, "accept-cmd-1", "Different note!");
        assertThatThrownBy(() -> service.acceptRecommendation(cmd))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("Idempotency conflict");
    }

    @Test
    @DisplayName("Accept recommendation: Audit service failure bubbles up to trigger transaction rollback")
    void acceptRecommendation_auditServiceFailure_throwsException() {
        UUID recId = UUID.randomUUID();
        UUID createdPlanId = UUID.randomUUID();
        UUID createdVersionId = UUID.randomUUID();
        Instant now = Instant.now();

        String proposalPayload = """
                {
                  "name": "Weekly Strength",
                  "description": "4-day split",
                  "weeklySchedule": []
                }
                """;

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(studentId)
                .coachingPeriodId(periodId)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .title("Proposed Workout Plan")
                .explanation("Details")
                .structuredPayload(proposalPayload)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        when(recommendationRepository.findByIdForUpdate(recId)).thenReturn(Optional.of(rec));
        when(recommendationRepository.findApplicationByRecommendationId(recId)).thenReturn(Optional.empty());

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
}
