package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiContextSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunInputReference;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.exercise.application.port.out.ExerciseCatalogRepository;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRunF01ServiceUnitTest {

    @Mock
    private AiRunRepository runRepository;

    @Mock
    private AiProvenanceRepository provenanceRepository;

    @Mock
    private UserAccountStatusQuery accountStatusQuery;

    @Mock
    private UserRoleQuery userRoleQuery;

    @Mock
    private ExerciseCatalogRepository exerciseCatalogRepository;

    @Mock
    private F01InternalEnvelopeBuilder envelopeBuilder;

    @Mock
    private F01PersonalContextBuilder personalContextBuilder;

    private ObjectMapper objectMapper;
    private AiRunF01Service service;

    private final UUID actorId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AiRunF01Service(
                runRepository,
                provenanceRepository,
                accountStatusQuery,
                userRoleQuery,
                exerciseCatalogRepository,
                envelopeBuilder,
                personalContextBuilder,
                objectMapper
        );
    }

    @Test
    @DisplayName("revalidateActorAuthorization: Null actorId throws AiRunAccessDeniedException")
    void revalidateActorAuthorization_nullActor_throwsAccessDenied() {
        assertThatThrownBy(() -> service.revalidateActorAuthorization(null))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Authenticated actor ID is required");
    }

    @Test
    @DisplayName("revalidateActorAuthorization: Non-existent account throws AccountUnavailableException")
    void revalidateActorAuthorization_accountNotFound_throwsAccountUnavailable() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revalidateActorAuthorization(actorId))
                .isInstanceOf(AccountUnavailableException.class)
                .hasMessageContaining("Actor account does not exist");
    }

    @Test
    @DisplayName("revalidateActorAuthorization: Suspended or deactivated account throws AccountUnavailableException")
    void revalidateActorAuthorization_deactivatedAccount_throwsAccountUnavailable() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        assertThatThrownBy(() -> service.revalidateActorAuthorization(actorId))
                .isInstanceOf(AccountUnavailableException.class)
                .hasMessageContaining("Actor account is not active");
    }

    @Test
    @DisplayName("revalidateActorAuthorization: Active account without STUDENT or TRAINER role throws AiRunAccessDeniedException")
    void revalidateActorAuthorization_noStudentOrTrainerRole_throwsAccessDenied() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(false);
        when(userRoleQuery.hasActiveRole(actorId, "TRAINER")).thenReturn(false);

        assertThatThrownBy(() -> service.revalidateActorAuthorization(actorId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("User lacks STUDENT or TRAINER capability to access AI general knowledge output");
    }

    @Test
    @DisplayName("revalidateActorAuthorization: Active student succeeds")
    void revalidateActorAuthorization_activeStudent_succeeds() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(true);

        service.revalidateActorAuthorization(actorId);
    }

    @Test
    @DisplayName("revalidateActorAuthorization: Active trainer succeeds")
    void revalidateActorAuthorization_activeTrainer_succeeds() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(false);
        when(userRoleQuery.hasActiveRole(actorId, "TRAINER")).thenReturn(true);

        service.revalidateActorAuthorization(actorId);
    }

    @Test
    @DisplayName("revalidateActorAuthorization: Loss of STUDENT role while retaining valid TRAINER role is not blocked")
    void revalidateActorAuthorization_studentRoleRevokedButTrainerActive_succeeds() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(false);
        when(userRoleQuery.hasActiveRole(actorId, "TRAINER")).thenReturn(true);

        // Act & Assert - does not throw
        service.revalidateActorAuthorization(actorId);
    }

    @Test
    @DisplayName("getF01Run: Revalidates actor before retrieving run - blocks if account is inactive")
    void getF01Run_revalidatesAndBlocksInactiveAccount() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.DISABLED));

        assertThatThrownBy(() -> service.getF01Run(actorId, runId))
                .isInstanceOf(AccountUnavailableException.class);

        verifyNoInteractions(runRepository);
    }

    @Test
    @DisplayName("getF01Run: Revalidates actor before retrieving run - blocks if role is missing")
    void getF01Run_revalidatesAndBlocksMissingRole() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(false);
        when(userRoleQuery.hasActiveRole(actorId, "TRAINER")).thenReturn(false);

        assertThatThrownBy(() -> service.getF01Run(actorId, runId))
                .isInstanceOf(AiRunAccessDeniedException.class);

        verifyNoInteractions(runRepository);
    }

    @Test
    @DisplayName("getF01Run: Revalidates actor then enforces run ownership")
    void getF01Run_enforcesOwnership() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(true);

        UUID differentUser = UUID.randomUUID();
        AiRun runOfAnotherUser = AiRun.builder()
                .id(runId)
                .requestedBy(differentUser)
                .status(AiRunStatus.SUCCEEDED)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .queuedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(runOfAnotherUser));

        assertThatThrownBy(() -> service.getF01Run(actorId, runId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("only the run owner can access this AI Run");
    }

    @Test
    @DisplayName("getF01Run: Valid owner with active account retrieves run successfully")
    void getF01Run_validOwner_succeeds() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(true);

        AiRun myRun = AiRun.builder()
                .id(runId)
                .requestedBy(actorId)
                .status(AiRunStatus.SUCCEEDED)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .queuedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(myRun));

        AiRun result = service.getF01Run(actorId, runId);
        assertThat(result).isSameAs(myRun);
    }

    @Test
    @DisplayName("getF01Run: Trainer reading back historical run after coaching authority revoked throws AiRunAccessDeniedException (T04 readback guard)")
    void getF01Run_trainerRevoked_throwsAccessDenied_T04() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(false);
        when(userRoleQuery.hasActiveRole(actorId, "TRAINER")).thenReturn(true);

        AiRun trainerRun = AiRun.builder()
                .id(runId)
                .requestedBy(actorId)
                .status(AiRunStatus.SUCCEEDED)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .queuedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(trainerRun));

        UUID studentId = UUID.randomUUID();
        when(provenanceRepository.findInputReferencesByRunId(runId)).thenReturn(List.of(
                new AiRunInputReference(runId, "STUDENT", studentId, "PERSONAL_CONTEXT_SUBJECT")
        ));

        org.mockito.Mockito.doThrow(new AiRunAccessDeniedException("Trainer lacks active coaching relationship"))
                .when(personalContextBuilder).validateTrainerAuthority(actorId, studentId);

        assertThatThrownBy(() -> service.getF01Run(actorId, runId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Trainer lacks active coaching relationship");
    }

    @Test
    @DisplayName("getF01Run: Run owner who lost TRAINER capability cannot access historical run containing another student context")
    void getF01Run_trainerCapabilityRevoked_throwsAccessDenied_T04() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        // User still has STUDENT role, but TRAINER role was revoked
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(true);
        when(userRoleQuery.hasActiveRole(actorId, "TRAINER")).thenReturn(false);

        AiRun historicalRun = AiRun.builder()
                .id(runId)
                .requestedBy(actorId)
                .status(AiRunStatus.SUCCEEDED)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .queuedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(historicalRun));

        UUID studentId = UUID.randomUUID();
        when(provenanceRepository.findInputReferencesByRunId(runId)).thenReturn(List.of(
                new AiRunInputReference(runId, "STUDENT", studentId, "PERSONAL_CONTEXT_SUBJECT")
        ));

        assertThatThrownBy(() -> service.getF01Run(actorId, runId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("active trainer role required to access student context");

        verifyNoInteractions(personalContextBuilder);
    }

    @Test
    @DisplayName("createF01RunContext: Dual-role account (STUDENT + TRAINER) requesting for assigned student resolves acting role as TRAINER")
    void createF01RunContext_dualRoleUser_requestingForAssignedStudent_resolvesActingRoleToTrainer() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(true);
        when(userRoleQuery.hasActiveRole(actorId, "TRAINER")).thenReturn(true);

        UUID assignedStudentId = UUID.randomUUID();
        com.fitnesscoaching.platform.modules.ai.application.port.in.CreateF01AiRunCommand command =
                new com.fitnesscoaching.platform.modules.ai.application.port.in.CreateF01AiRunCommand(
                        "Khuyến nghị bài tập cho học viên?",
                        "vi",
                        null,
                        UUID.randomUUID(),
                        assignedStudentId,
                        true
                );

        when(personalContextBuilder.buildPersonalContext(actorId, "TRAINER", assignedStudentId))
                .thenReturn(new com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope.PersonalContext(
                        assignedStudentId, "HUMAN_COACH", "INTERMEDIATE", null, null, null, null
                ));

        when(runRepository.create(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        when(envelopeBuilder.build(
                org.mockito.ArgumentMatchers.any(),
                eq(actorId),
                eq("TRAINER"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(mock(com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope.class));

        var context = service.createF01RunContext(actorId, command);

        assertThat(context).isNotNull();
        assertThat(context.run()).isNotNull();
        verify(personalContextBuilder).buildPersonalContext(actorId, "TRAINER", assignedStudentId);
        verify(envelopeBuilder).build(
                org.mockito.ArgumentMatchers.any(),
                eq(actorId),
                eq("TRAINER"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("getF01Run: When student revoked FITNESS_GOAL sharing, trainer cannot readback run containing goal context (T04)")
    void getF01Run_granularSharingGoalRevoked_throwsAccessDenied_T04() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(false);
        when(userRoleQuery.hasActiveRole(actorId, "TRAINER")).thenReturn(true);

        AiRun historicalRun = AiRun.builder()
                .id(runId)
                .requestedBy(actorId)
                .status(AiRunStatus.SUCCEEDED)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .queuedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(historicalRun));

        UUID studentId = UUID.randomUUID();
        when(provenanceRepository.findInputReferencesByRunId(runId)).thenReturn(List.of(
                new AiRunInputReference(runId, "STUDENT", studentId, "PERSONAL_CONTEXT_SUBJECT")
        ));

        // Snapshot containing activeGoal
        String snapshotPayload = "{\"personalContext\":{\"studentId\":\"" + studentId + "\",\"activeGoal\":{\"goalType\":\"Giảm mỡ\"}}}";
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(
                new AiContextSnapshot(UUID.randomUUID(), runId, "1.0.0", snapshotPayload, "{}", "{}", "{}", "hash", Instant.now())
        ));

        org.mockito.Mockito.doThrow(new AiRunAccessDeniedException("Access denied: FITNESS_GOAL sharing permission has been revoked or is inactive"))
                .when(personalContextBuilder).validateGranularSharingAuthority(actorId, studentId, true, false);

        assertThatThrownBy(() -> service.getF01Run(actorId, runId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("FITNESS_GOAL sharing permission has been revoked");
    }

    @Test
    @DisplayName("getF01Run: When student revoked WORKOUT_PLAN sharing, trainer cannot readback run containing plan context (T04)")
    void getF01Run_granularSharingPlanRevoked_throwsAccessDenied_T04() {
        when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(false);
        when(userRoleQuery.hasActiveRole(actorId, "TRAINER")).thenReturn(true);

        AiRun historicalRun = AiRun.builder()
                .id(runId)
                .requestedBy(actorId)
                .status(AiRunStatus.SUCCEEDED)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .queuedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(historicalRun));

        UUID studentId = UUID.randomUUID();
        when(provenanceRepository.findInputReferencesByRunId(runId)).thenReturn(List.of(
                new AiRunInputReference(runId, "STUDENT", studentId, "PERSONAL_CONTEXT_SUBJECT")
        ));

        // Snapshot containing activePlanName
        String snapshotPayload = "{\"personalContext\":{\"studentId\":\"" + studentId + "\",\"currentActivePlanName\":\"Giáo án tăng cơ\"}}";
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(
                new AiContextSnapshot(UUID.randomUUID(), runId, "1.0.0", snapshotPayload, "{}", "{}", "{}", "hash", Instant.now())
        ));

        org.mockito.Mockito.doThrow(new AiRunAccessDeniedException("Access denied: WORKOUT_PLAN sharing permission has been revoked or is inactive"))
                .when(personalContextBuilder).validateGranularSharingAuthority(actorId, studentId, false, true);

        assertThatThrownBy(() -> service.getF01Run(actorId, runId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("WORKOUT_PLAN sharing permission has been revoked");
    }
}
