package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}
