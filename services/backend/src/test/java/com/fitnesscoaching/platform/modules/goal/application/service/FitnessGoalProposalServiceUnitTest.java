package com.fitnesscoaching.platform.modules.goal.application.service;

import com.fitnesscoaching.platform.common.exception.*;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalProposalPage;
import com.fitnesscoaching.platform.modules.goal.application.port.in.*;
import com.fitnesscoaching.platform.modules.goal.application.port.out.FitnessGoalPersistencePort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalProposalPort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalStudentAuthorityPort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalTrainerAuthorityPort;
import com.fitnesscoaching.platform.modules.goal.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FitnessGoalProposalServiceUnitTest {

    @Mock
    private GoalProposalPort proposalPort;

    @Mock
    private GoalStudentAuthorityPort studentAuthorityPort;

    @Mock
    private GoalTrainerAuthorityPort trainerAuthorityPort;

    @Mock
    private FitnessGoalPersistencePort goalPort;

    @Mock
    private GoalValidationHelper validationHelper;

    @Mock
    private AuditService auditService;

    private FitnessGoalProposalService service;

    private UUID studentId;
    private UUID trainerId;
    private UUID goalId;
    private UUID versionId;
    private FitnessGoal activeGoal;
    private FitnessGoalVersion currentVersion;

    @BeforeEach
    void setUp() {
        service = new FitnessGoalProposalService(
                proposalPort,
                studentAuthorityPort,
                trainerAuthorityPort,
                goalPort,
                validationHelper,
                auditService
        );

        studentId = UUID.randomUUID();
        trainerId = UUID.randomUUID();
        goalId = UUID.randomUUID();
        versionId = UUID.randomUUID();

        currentVersion = new FitnessGoalVersion(
                versionId,
                goalId,
                1,
                "Base Title",
                LocalDate.now(),
                LocalDate.now().plusDays(90),
                90,
                Instant.now(),
                null,
                null,
                "Initial",
                "Summary",
                studentId,
                null,
                Instant.now(),
                Instant.now(),
                studentId,
                VersionLockReason.ACTIVATED,
                List.of(),
                List.of()
        );

        activeGoal = new FitnessGoal(
                goalId,
                studentId,
                "Base Title",
                GoalStatus.ACTIVE,
                studentId,
                Instant.now(),
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                currentVersion
        );
    }

    @Test
    @DisplayName("createProposal: succeeds when trainer is authorized, goal is active, and validation passes")
    void createProposal_success() {
        when(goalPort.findById(goalId)).thenReturn(Optional.of(activeGoal));
        doNothing().when(trainerAuthorityPort).verifyTrainerCanProposeGoal(trainerId, studentId);

        when(validationHelper.resolveAndValidateProposalTimeline(any(), any(), any(), any(), any(), any()))
                .thenReturn(new GoalValidationHelper.ResolvedTimeline(LocalDate.now(), LocalDate.now().plusDays(60), 60));

        GoalObjective obj = new GoalObjective(UUID.randomUUID(), null, (short) 1, "FAT_LOSS", "Fat Loss", ObjectivePriority.PRIMARY, 0, "Some notes");
        when(validationHelper.validateAndBuildObjectives(any())).thenReturn(List.of(obj));

        GoalTarget tgt = new GoalTarget(UUID.randomUUID(), null, 1, "WEIGHT", "Weight", null,
                BigDecimal.valueOf(80), BigDecimal.valueOf(75), null, null,
                (short) 1, "KG", "kg", null, LocalDate.now().plusDays(60), null, Instant.now());
        when(validationHelper.validateAndBuildTargets(any(), any())).thenReturn(List.of(tgt));

        UUID savedProposalId = UUID.randomUUID();
        when(proposalPort.saveProposal(any())).thenAnswer(inv -> {
            GoalProposal p = inv.getArgument(0);
            return new GoalProposal(
                    savedProposalId,
                    p.studentId(),
                    p.fitnessGoalId(),
                    p.baseGoalVersionId(),
                    p.source(),
                    p.createdBy(),
                    p.proposedTitle(),
                    p.proposedStartDate(),
                    p.proposedTargetDate(),
                    p.proposedDurationDays(),
                    p.reason(),
                    p.status(),
                    p.decidedBy(),
                    p.decidedAt(),
                    p.decisionNote(),
                    p.expiresAt(),
                    Instant.now(),
                    Instant.now(),
                    p.objectives(),
                    p.targets(),
                    currentVersion
            );
        });

        CreateGoalProposalCommand command = new CreateGoalProposalCommand(
                goalId,
                trainerId,
                "New Proposed Title",
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                60,
                "Goal adjustment reason",
                Instant.now().plus(14, ChronoUnit.DAYS),
                List.of(new CreateGoalObjectiveCommand((short) 1, "FAT_LOSS", ObjectivePriority.PRIMARY, 0, "Some notes")),
                List.of(new CreateGoalTargetCommand(1, "WEIGHT", null, BigDecimal.valueOf(80), BigDecimal.valueOf(75), null, null, (short) 1, "KG", null, LocalDate.now().plusDays(60), null))
        );

        GoalProposal result = service.createProposal(command);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(savedProposalId);
        assertThat(result.status()).isEqualTo(ProposalStatus.PENDING);
        assertThat(result.reason()).isEqualTo("Goal adjustment reason");

        verify(trainerAuthorityPort).verifyTrainerCanProposeGoal(trainerId, studentId);
        verify(proposalPort).saveProposal(any(GoalProposal.class));
        verify(auditService).recordAudit(argThat(record ->
                record.action().equals("GOAL_PROPOSAL_CREATED")
                        && record.actorUserId().equals(trainerId)
                        && record.targetType().equals("GOAL_PROPOSAL")
        ));
    }

    @Test
    @DisplayName("createProposal: throws ApplicationValidationException when expiresAt is in the past")
    void createProposal_expiresAtInPast_rejected() {
        CreateGoalProposalCommand command = new CreateGoalProposalCommand(
                goalId, trainerId, null, null, null, null, "Reason",
                Instant.now().minus(1, ChronoUnit.DAYS), List.of(), List.of()
        );

        assertThatThrownBy(() -> service.createProposal(command))
                .isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    @DisplayName("createProposal: throws TrainerNotEligibleException when trainer is not eligible")
    void createProposal_trainerIneligible() {
        when(goalPort.findById(goalId)).thenReturn(Optional.of(activeGoal));
        doThrow(new TrainerNotEligibleException("Trainer is not eligible"))
                .when(trainerAuthorityPort).verifyTrainerCanProposeGoal(trainerId, studentId);

        CreateGoalProposalCommand command = new CreateGoalProposalCommand(
                goalId, trainerId, null, null, null, null, "Reason", null, List.of(), List.of()
        );

        assertThatThrownBy(() -> service.createProposal(command))
                .isInstanceOf(TrainerNotEligibleException.class);

        verify(proposalPort, never()).saveProposal(any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("createProposal: throws CoachingRelationshipRequiredException when no active relationship exists")
    void createProposal_noCoachingRelationship() {
        when(goalPort.findById(goalId)).thenReturn(Optional.of(activeGoal));
        doThrow(new CoachingRelationshipRequiredException("No active coaching relationship"))
                .when(trainerAuthorityPort).verifyTrainerCanProposeGoal(trainerId, studentId);

        CreateGoalProposalCommand command = new CreateGoalProposalCommand(
                goalId, trainerId, null, null, null, null, "Reason", null, List.of(), List.of()
        );

        assertThatThrownBy(() -> service.createProposal(command))
                .isInstanceOf(CoachingRelationshipRequiredException.class);

        verify(proposalPort, never()).saveProposal(any());
    }

    @Test
    @DisplayName("createProposal: throws DataSharingPermissionRequiredException when FITNESS_GOAL sharing is not allowed")
    void createProposal_noDataSharingPermission() {
        when(goalPort.findById(goalId)).thenReturn(Optional.of(activeGoal));
        doThrow(new DataSharingPermissionRequiredException("Student has not granted FITNESS_GOAL permission"))
                .when(trainerAuthorityPort).verifyTrainerCanProposeGoal(trainerId, studentId);

        CreateGoalProposalCommand command = new CreateGoalProposalCommand(
                goalId, trainerId, null, null, null, null, "Reason", null, List.of(), List.of()
        );

        assertThatThrownBy(() -> service.createProposal(command))
                .isInstanceOf(DataSharingPermissionRequiredException.class);

        verify(proposalPort, never()).saveProposal(any());
    }

    @Test
    @DisplayName("createProposal: throws FitnessGoalNotFoundException when goal does not exist")
    void createProposal_goalNotFound() {
        when(goalPort.findById(goalId)).thenReturn(Optional.empty());

        CreateGoalProposalCommand command = new CreateGoalProposalCommand(
                goalId, trainerId, null, null, null, null, "Reason", null, List.of(), List.of()
        );

        assertThatThrownBy(() -> service.createProposal(command))
                .isInstanceOf(FitnessGoalNotFoundException.class);
    }

    @Test
    @DisplayName("createProposal: throws InvalidLifecycleTransitionException when goal is not ACTIVE")
    void createProposal_goalNotActive() {
        FitnessGoal draftGoal = new FitnessGoal(
                goalId, studentId, "Draft Title", GoalStatus.DRAFT, studentId,
                null, null, null, null, null, Instant.now(), Instant.now(), currentVersion
        );
        when(goalPort.findById(goalId)).thenReturn(Optional.of(draftGoal));

        CreateGoalProposalCommand command = new CreateGoalProposalCommand(
                goalId, trainerId, null, null, null, null, "Reason", null, List.of(), List.of()
        );

        assertThatThrownBy(() -> service.createProposal(command))
                .isInstanceOf(InvalidLifecycleTransitionException.class);
    }

    @Test
    @DisplayName("getStudentProposals: verifies student capability and returns page")
    void getStudentProposals_success() {
        doNothing().when(studentAuthorityPort).verifyStudentCanManageGoals(studentId);
        when(proposalPort.countByStudentId(studentId, ProposalStatus.PENDING)).thenReturn(1L);
        GoalProposal p = new GoalProposal(
                UUID.randomUUID(), studentId, goalId, versionId, ProposalSource.TRAINER,
                trainerId, "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30,
                "Reason", ProposalStatus.PENDING, null, null, null, null, Instant.now(), Instant.now(),
                List.of(), List.of(), null
        );
        when(proposalPort.findByStudentId(studentId, ProposalStatus.PENDING, 20, 0L))
                .thenReturn(List.of(p));

        GetStudentGoalProposalsQuery query = new GetStudentGoalProposalsQuery(studentId, ProposalStatus.PENDING, 0, 20);
        GoalProposalPage page = service.getStudentProposals(query);

        assertThat(page.items()).hasSize(1);
        assertThat(page.totalItems()).isEqualTo(1L);
        assertThat(page.totalPages()).isEqualTo(1);
        verify(studentAuthorityPort).verifyStudentCanManageGoals(studentId);
    }

    @Test
    @DisplayName("getStudentProposals: throws ApplicationValidationException for invalid pagination")
    void getStudentProposals_invalidPagination() {
        doNothing().when(studentAuthorityPort).verifyStudentCanManageGoals(studentId);

        assertThatThrownBy(() -> service.getStudentProposals(new GetStudentGoalProposalsQuery(studentId, null, -1, 20)))
                .isInstanceOf(ApplicationValidationException.class);

        assertThatThrownBy(() -> service.getStudentProposals(new GetStudentGoalProposalsQuery(studentId, null, 0, 0)))
                .isInstanceOf(ApplicationValidationException.class);

        assertThatThrownBy(() -> service.getStudentProposals(new GetStudentGoalProposalsQuery(studentId, null, 0, 101)))
                .isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    @DisplayName("getProposalDetail: returns proposal for owning student and re-verifies student authority")
    void getProposalDetail_studentSuccess() {
        UUID proposalId = UUID.randomUUID();
        GoalProposal p = new GoalProposal(
                proposalId, studentId, goalId, versionId, ProposalSource.TRAINER,
                trainerId, "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30,
                "Reason", ProposalStatus.PENDING, null, null, null, null, Instant.now(), Instant.now(),
                List.of(), List.of(), currentVersion
        );
        when(proposalPort.findById(proposalId)).thenReturn(Optional.of(p));
        doNothing().when(studentAuthorityPort).verifyStudentCanManageGoals(studentId);

        GoalProposal forStudent = service.getProposalDetail(new GetGoalProposalDetailQuery(proposalId, studentId));
        assertThat(forStudent).isNotNull();
        verify(studentAuthorityPort).verifyStudentCanManageGoals(studentId);
    }

    @Test
    @DisplayName("getProposalDetail: returns proposal for creator trainer and re-verifies trainer authority")
    void getProposalDetail_trainerSuccess() {
        UUID proposalId = UUID.randomUUID();
        GoalProposal p = new GoalProposal(
                proposalId, studentId, goalId, versionId, ProposalSource.TRAINER,
                trainerId, "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30,
                "Reason", ProposalStatus.PENDING, null, null, null, null, Instant.now(), Instant.now(),
                List.of(), List.of(), currentVersion
        );
        when(proposalPort.findById(proposalId)).thenReturn(Optional.of(p));
        doNothing().when(trainerAuthorityPort).verifyTrainerCanViewProposal(trainerId, studentId);

        GoalProposal forTrainer = service.getProposalDetail(new GetGoalProposalDetailQuery(proposalId, trainerId));
        assertThat(forTrainer).isNotNull();
        verify(trainerAuthorityPort).verifyTrainerCanViewProposal(trainerId, studentId);
    }

    @Test
    @DisplayName("getProposalDetail: null createdBy does not throw NPE and denies unauthorized user")
    void getProposalDetail_nullCreatedBy_safeAccessDenied() {
        UUID proposalId = UUID.randomUUID();
        GoalProposal p = new GoalProposal(
                proposalId, studentId, goalId, versionId, ProposalSource.TRAINER,
                null, "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30,
                "Reason", ProposalStatus.PENDING, null, null, null, null, Instant.now(), Instant.now(),
                List.of(), List.of(), currentVersion
        );
        when(proposalPort.findById(proposalId)).thenReturn(Optional.of(p));

        UUID strangerId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getProposalDetail(new GetGoalProposalDetailQuery(proposalId, strangerId)))
                .isInstanceOf(GoalProposalAccessDeniedException.class);
    }

    @Test
    @DisplayName("getProposalDetail: throws GoalProposalAccessDeniedException for unrelated user")
    void getProposalDetail_accessDenied() {
        UUID proposalId = UUID.randomUUID();
        GoalProposal p = new GoalProposal(
                proposalId, studentId, goalId, versionId, ProposalSource.TRAINER,
                trainerId, "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30,
                "Reason", ProposalStatus.PENDING, null, null, null, null, Instant.now(), Instant.now(),
                List.of(), List.of(), currentVersion
        );
        when(proposalPort.findById(proposalId)).thenReturn(Optional.of(p));

        UUID strangerId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getProposalDetail(new GetGoalProposalDetailQuery(proposalId, strangerId)))
                .isInstanceOf(GoalProposalAccessDeniedException.class);
    }

    @Test
    @DisplayName("decideProposal: REJECT requires non-blank decisionNote, updates status to REJECTED and records audit")
    void decideProposal_reject() {
        UUID proposalId = UUID.randomUUID();
        GoalProposal pending = new GoalProposal(
                proposalId, studentId, goalId, versionId, ProposalSource.TRAINER,
                trainerId, "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30,
                "Reason", ProposalStatus.PENDING, null, null, null, null, Instant.now(), Instant.now(),
                List.of(), List.of(), currentVersion
        );
        GoalProposal rejected = new GoalProposal(
                proposalId, studentId, goalId, versionId, ProposalSource.TRAINER,
                trainerId, "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30,
                "Reason", ProposalStatus.REJECTED, studentId, Instant.now(), "Not interested", null, Instant.now(), Instant.now(),
                List.of(), List.of(), currentVersion
        );

        doNothing().when(studentAuthorityPort).verifyStudentCanManageGoals(studentId);
        when(proposalPort.findById(proposalId)).thenReturn(Optional.of(pending), Optional.of(rejected));
        when(proposalPort.rejectProposal(proposalId, studentId, "Not interested")).thenReturn(true);

        DecideGoalProposalCommand cmd = new DecideGoalProposalCommand(proposalId, studentId, ProposalDecision.REJECT, "Not interested");
        GoalProposal result = service.decideProposal(cmd);

        assertThat(result.status()).isEqualTo(ProposalStatus.REJECTED);
        verify(studentAuthorityPort).verifyStudentCanManageGoals(studentId);
        verify(proposalPort).rejectProposal(proposalId, studentId, "Not interested");
        verify(auditService).recordAudit(argThat(rec ->
                rec.action().equals("GOAL_PROPOSAL_REJECTED")
                        && rec.actorUserId().equals(studentId)
        ));
    }

    @Test
    @DisplayName("decideProposal: REJECT with blank decisionNote throws ApplicationValidationException")
    void decideProposal_reject_blankNote_rejected() {
        doNothing().when(studentAuthorityPort).verifyStudentCanManageGoals(studentId);

        DecideGoalProposalCommand cmd = new DecideGoalProposalCommand(UUID.randomUUID(), studentId, ProposalDecision.REJECT, "   ");
        assertThatThrownBy(() -> service.decideProposal(cmd))
                .isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    @DisplayName("decideProposal: decisionNote exceeding 2000 characters throws ApplicationValidationException")
    void decideProposal_decisionNoteTooLong_rejected() {
        doNothing().when(studentAuthorityPort).verifyStudentCanManageGoals(studentId);

        String tooLongNote = "a".repeat(2001);
        DecideGoalProposalCommand cmd = new DecideGoalProposalCommand(UUID.randomUUID(), studentId, ProposalDecision.REJECT, tooLongNote);
        assertThatThrownBy(() -> service.decideProposal(cmd))
                .isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    @DisplayName("decideProposal: ACCEPT re-validates catalog, creates new version, updates proposal to ACCEPTED, and records audits")
    void decideProposal_accept() {
        UUID proposalId = UUID.randomUUID();
        GoalProposal pending = new GoalProposal(
                proposalId, studentId, goalId, versionId, ProposalSource.TRAINER,
                trainerId, "New Title", LocalDate.now(), LocalDate.now().plusDays(45), 45,
                "Reason", ProposalStatus.PENDING, null, null, null, null, Instant.now(), Instant.now(),
                List.of(), List.of(), currentVersion
        );
        FitnessGoalVersion newVersion = new FitnessGoalVersion(
                UUID.randomUUID(), goalId, 2, "New Title", LocalDate.now(), LocalDate.now().plusDays(45), 45,
                Instant.now(), null, null, "Reason", "Accepted proposal " + proposalId,
                studentId, proposalId, Instant.now(), Instant.now(), studentId, VersionLockReason.APPROVED,
                List.of(), List.of()
        );
        GoalProposal accepted = new GoalProposal(
                proposalId, studentId, goalId, versionId, ProposalSource.TRAINER,
                trainerId, "New Title", LocalDate.now(), LocalDate.now().plusDays(45), 45,
                "Reason", ProposalStatus.ACCEPTED, studentId, Instant.now(), "Looks good", null, Instant.now(), Instant.now(),
                List.of(), List.of(), currentVersion
        );

        doNothing().when(studentAuthorityPort).verifyStudentCanManageGoals(studentId);
        when(proposalPort.findById(proposalId)).thenReturn(Optional.of(pending), Optional.of(accepted));
        doNothing().when(validationHelper).validateProposalForAcceptance(pending);
        when(proposalPort.acceptProposal(pending, studentId, "Looks good")).thenReturn(newVersion);

        DecideGoalProposalCommand cmd = new DecideGoalProposalCommand(proposalId, studentId, ProposalDecision.ACCEPT, "Looks good");
        GoalProposal result = service.decideProposal(cmd);

        assertThat(result.status()).isEqualTo(ProposalStatus.ACCEPTED);
        verify(studentAuthorityPort).verifyStudentCanManageGoals(studentId);
        verify(validationHelper).validateProposalForAcceptance(pending);
        verify(proposalPort).acceptProposal(pending, studentId, "Looks good");

        ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService, times(2)).recordAudit(auditCaptor.capture());

        List<AuditRecord> records = auditCaptor.getAllValues();
        assertThat(records).extracting(AuditRecord::action)
                .containsExactly("GOAL_PROPOSAL_ACCEPTED", "FITNESS_GOAL_VERSION_CREATED");
    }

    @Test
    @DisplayName("decideProposal: throws GoalProposalExpiredException when proposal expiration has passed")
    void decideProposal_expired() {
        UUID proposalId = UUID.randomUUID();
        GoalProposal expiredProposal = new GoalProposal(
                proposalId, studentId, goalId, versionId, ProposalSource.TRAINER,
                trainerId, "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30,
                "Reason", ProposalStatus.PENDING, null, null, null,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().minus(2, ChronoUnit.DAYS), Instant.now().minus(2, ChronoUnit.DAYS),
                List.of(), List.of(), currentVersion
        );

        doNothing().when(studentAuthorityPort).verifyStudentCanManageGoals(studentId);
        when(proposalPort.findById(proposalId)).thenReturn(Optional.of(expiredProposal));

        DecideGoalProposalCommand cmd = new DecideGoalProposalCommand(proposalId, studentId, ProposalDecision.ACCEPT, null);
        assertThatThrownBy(() -> service.decideProposal(cmd))
                .isInstanceOf(GoalProposalExpiredException.class);
    }

    @Test
    @DisplayName("decideProposal: throws GoalProposalAccessDeniedException when decided by non-student")
    void decideProposal_accessDenied() {
        UUID proposalId = UUID.randomUUID();
        GoalProposal pending = new GoalProposal(
                proposalId, studentId, goalId, versionId, ProposalSource.TRAINER,
                trainerId, "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30,
                "Reason", ProposalStatus.PENDING, null, null, null, null, Instant.now(), Instant.now(),
                List.of(), List.of(), currentVersion
        );
        when(proposalPort.findById(proposalId)).thenReturn(Optional.of(pending));

        DecideGoalProposalCommand cmd = new DecideGoalProposalCommand(proposalId, trainerId, ProposalDecision.ACCEPT, null);

        assertThatThrownBy(() -> service.decideProposal(cmd))
                .isInstanceOf(GoalProposalAccessDeniedException.class);
    }

    @Test
    @DisplayName("decideProposal: throws GoalProposalAlreadyDecidedException when proposal is not PENDING")
    void decideProposal_alreadyDecided() {
        UUID proposalId = UUID.randomUUID();
        GoalProposal alreadyAccepted = new GoalProposal(
                proposalId, studentId, goalId, versionId, ProposalSource.TRAINER,
                trainerId, "Title", LocalDate.now(), LocalDate.now().plusDays(30), 30,
                "Reason", ProposalStatus.ACCEPTED, studentId, Instant.now(), null, null, Instant.now(), Instant.now(),
                List.of(), List.of(), currentVersion
        );
        doNothing().when(studentAuthorityPort).verifyStudentCanManageGoals(studentId);
        when(proposalPort.findById(proposalId)).thenReturn(Optional.of(alreadyAccepted));

        DecideGoalProposalCommand cmd = new DecideGoalProposalCommand(proposalId, studentId, ProposalDecision.ACCEPT, null);

        assertThatThrownBy(() -> service.decideProposal(cmd))
                .isInstanceOf(GoalProposalAlreadyDecidedException.class);
    }
}
