package com.fitnesscoaching.platform.modules.goal.application.service;

import com.fitnesscoaching.platform.common.exception.*;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalProposalPage;
import com.fitnesscoaching.platform.modules.goal.application.port.in.*;
import com.fitnesscoaching.platform.modules.goal.application.port.out.FitnessGoalPersistencePort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalProposalPort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalStudentAuthorityPort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalTrainerAuthorityPort;
import com.fitnesscoaching.platform.modules.goal.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class FitnessGoalProposalService implements
        CreateGoalProposalUseCase,
        GetStudentGoalProposalsUseCase,
        GetGoalProposalDetailUseCase,
        DecideGoalProposalUseCase {

    private final GoalProposalPort proposalPort;
    private final GoalStudentAuthorityPort studentAuthorityPort;
    private final GoalTrainerAuthorityPort trainerAuthorityPort;
    private final FitnessGoalPersistencePort goalPort;
    private final GoalValidationHelper validationHelper;
    private final AuditService auditService;

    public FitnessGoalProposalService(
            GoalProposalPort proposalPort,
            GoalStudentAuthorityPort studentAuthorityPort,
            GoalTrainerAuthorityPort trainerAuthorityPort,
            FitnessGoalPersistencePort goalPort,
            GoalValidationHelper validationHelper,
            AuditService auditService
    ) {
        this.proposalPort = proposalPort;
        this.studentAuthorityPort = studentAuthorityPort;
        this.trainerAuthorityPort = trainerAuthorityPort;
        this.goalPort = goalPort;
        this.validationHelper = validationHelper;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public GoalProposal createProposal(CreateGoalProposalCommand command) {
        if (command.trainerId() == null) {
            throw new ApplicationValidationException("Trainer ID is required",
                    List.of(new FieldErrorDto("trainerId", "NotNull", "Trainer ID is required")));
        }
        if (command.goalId() == null) {
            throw new ApplicationValidationException("Goal ID is required",
                    List.of(new FieldErrorDto("goalId", "NotNull", "Goal ID is required")));
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ApplicationValidationException("Proposal reason is required",
                    List.of(new FieldErrorDto("reason", "NotBlank", "Proposal reason is required")));
        }
        if (command.expiresAt() != null && !command.expiresAt().isAfter(Instant.now())) {
            throw new ApplicationValidationException("Proposal expiration must be in the future",
                    List.of(new FieldErrorDto("expiresAt", "Future", "Proposal expiration must be in the future")));
        }

        FitnessGoal goal = goalPort.findById(command.goalId())
                .orElseThrow(() -> new FitnessGoalNotFoundException("Fitness goal not found: " + command.goalId()));

        if (goal.status() != GoalStatus.ACTIVE) {
            throw new InvalidLifecycleTransitionException(
                    "Goal proposals can only be created for an ACTIVE fitness goal. Current status: " + goal.status());
        }

        if (goal.currentVersion() == null) {
            throw new IllegalStateException("Active fitness goal does not have a current version: " + goal.id());
        }

        // Verify trainer authority across all 7 layers
        trainerAuthorityPort.verifyTrainerCanProposeGoal(command.trainerId(), goal.studentId());

        // Validate proposal timeline
        GoalValidationHelper.ResolvedTimeline timeline = validationHelper.resolveAndValidateProposalTimeline(
                command.proposedStartDate(),
                command.proposedTargetDate(),
                command.proposedDurationDays(),
                goal.currentVersion().startDate(),
                goal.currentVersion().targetDate(),
                goal.currentVersion().durationDays()
        );

        // Validate objectives
        List<GoalObjective> domainObjectives = validationHelper.validateAndBuildObjectives(command.objectives());

        // Validate targets
        LocalDate effectiveStartDate = timeline.startDate() != null
                ? timeline.startDate()
                : goal.currentVersion().startDate();
        List<GoalTarget> domainTargets = validationHelper.validateAndBuildTargets(command.targets(), effectiveStartDate);

        List<GoalProposalObjective> proposalObjectives = domainObjectives.stream()
                .map(o -> new GoalProposalObjective(
                        UUID.randomUUID(),
                        null,
                        o.goalTypeId(),
                        o.goalTypeCode(),
                        o.goalTypeName(),
                        o.priority(),
                        o.sortOrder(),
                        o.notes()
                ))
                .toList();

        List<GoalProposalTarget> proposalTargets = domainTargets.stream()
                .map(t -> new GoalProposalTarget(
                        UUID.randomUUID(),
                        null,
                        t.metricDefinitionId(),
                        t.metricCode(),
                        t.metricDisplayName(),
                        t.exerciseVariationId(),
                        t.startValue(),
                        t.targetValue(),
                        t.targetMinValue(),
                        t.targetMaxValue(),
                        t.unitId(),
                        t.unitCode(),
                        t.unitSymbol(),
                        t.targetRepetitions(),
                        t.targetDate(),
                        t.notes()
                ))
                .toList();

        UUID proposalId = UUID.randomUUID();
        GoalProposal proposal = new GoalProposal(
                proposalId,
                goal.studentId(),
                goal.id(),
                goal.currentVersion().id(),
                ProposalSource.TRAINER,
                command.trainerId(),
                command.proposedTitle() != null ? command.proposedTitle().trim() : null,
                timeline.startDate(),
                timeline.targetDate(),
                timeline.durationDays(),
                command.reason().trim(),
                ProposalStatus.PENDING,
                null,
                null,
                null,
                command.expiresAt(),
                null,
                null,
                proposalObjectives,
                proposalTargets,
                goal.currentVersion()
        );

        GoalProposal saved = proposalPort.saveProposal(proposal);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.trainerId())
                .actorRole("TRAINER")
                .action("GOAL_PROPOSAL_CREATED")
                .targetType("GOAL_PROPOSAL")
                .targetId(saved.id())
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(Instant.now())
                .metadataJson("{\"goalId\":\"" + goal.id() + "\",\"studentId\":\"" + goal.studentId() + "\",\"proposalId\":\"" + saved.id() + "\",\"baseGoalVersionId\":\"" + goal.currentVersion().id() + "\"}")
                .build());

        return saved;
    }

    @Override
    public GoalProposalPage getStudentProposals(GetStudentGoalProposalsQuery query) {
        if (query.studentId() == null) {
            throw new ApplicationValidationException("Student ID is required",
                    List.of(new FieldErrorDto("studentId", "NotNull", "Student ID is required")));
        }

        // Verify student capability and active status via GoalStudentAuthorityPort
        studentAuthorityPort.verifyStudentCanManageGoals(query.studentId());

        if (query.page() < 0) {
            throw new ApplicationValidationException("Page index must not be less than zero",
                    List.of(new FieldErrorDto("page", "Min", "Page index must not be less than zero")));
        }
        if (query.size() < 1 || query.size() > 100) {
            throw new ApplicationValidationException("Page size must be between 1 and 100",
                    List.of(new FieldErrorDto("size", "Range", "Page size must be between 1 and 100")));
        }

        long offset = (long) query.page() * query.size();
        if (offset > Integer.MAX_VALUE) {
            throw new ApplicationValidationException("Pagination offset exceeds maximum allowed limit",
                    List.of(new FieldErrorDto("page", "Max", "Pagination offset exceeds maximum allowed limit")));
        }

        long total = proposalPort.countByStudentId(query.studentId(), query.statusFilter());
        List<GoalProposal> items = proposalPort.findByStudentId(query.studentId(), query.statusFilter(), query.size(), offset);
        int totalPages = query.size() == 0 ? 0 : (int) Math.ceil((double) total / query.size());

        return new GoalProposalPage(items, query.page(), query.size(), total, totalPages);
    }

    @Override
    public GoalProposal getProposalDetail(GetGoalProposalDetailQuery query) {
        if (query.proposalId() == null) {
            throw new ApplicationValidationException("Proposal ID is required",
                    List.of(new FieldErrorDto("proposalId", "NotNull", "Proposal ID is required")));
        }

        GoalProposal proposal = proposalPort.findById(query.proposalId())
                .orElseThrow(() -> new GoalProposalNotFoundException("Goal proposal not found: " + query.proposalId()));

        boolean isStudent = proposal.studentId().equals(query.requesterUserId());
        boolean isCreatorTrainer = proposal.createdBy() != null && proposal.createdBy().equals(query.requesterUserId());

        if (!isStudent && !isCreatorTrainer) {
            throw new GoalProposalAccessDeniedException("Access denied: You are not authorized to view this goal proposal");
        }

        if (isStudent) {
            studentAuthorityPort.verifyStudentCanManageGoals(query.requesterUserId());
        } else {
            trainerAuthorityPort.verifyTrainerCanViewProposal(query.requesterUserId(), proposal.studentId());
        }

        return proposal;
    }

    @Override
    @Transactional
    public GoalProposal decideProposal(DecideGoalProposalCommand command) {
        if (command.proposalId() == null) {
            throw new ApplicationValidationException("Proposal ID is required",
                    List.of(new FieldErrorDto("proposalId", "NotNull", "Proposal ID is required")));
        }
        if (command.studentId() == null) {
            throw new ApplicationValidationException("Student ID is required",
                    List.of(new FieldErrorDto("studentId", "NotNull", "Student ID is required")));
        }
        if (command.decision() == null) {
            throw new InvalidGoalProposalDecisionException("Decision must be either ACCEPT or REJECT");
        }

        // Verify student capability and active status via GoalStudentAuthorityPort
        studentAuthorityPort.verifyStudentCanManageGoals(command.studentId());

        if (command.decision() == ProposalDecision.REJECT) {
            if (command.decisionNote() == null || command.decisionNote().trim().isEmpty()) {
                throw new ApplicationValidationException("Decision note is required when rejecting a proposal",
                        List.of(new FieldErrorDto("decisionNote", "NotBlank", "Decision note is required when rejecting a proposal")));
            }
        }
        if (command.decisionNote() != null && command.decisionNote().length() > 2000) {
            throw new ApplicationValidationException("Decision note must not exceed 2000 characters",
                    List.of(new FieldErrorDto("decisionNote", "Size", "Decision note must not exceed 2000 characters")));
        }

        GoalProposal proposal = proposalPort.findById(command.proposalId())
                .orElseThrow(() -> new GoalProposalNotFoundException("Goal proposal not found: " + command.proposalId()));

        if (!proposal.studentId().equals(command.studentId())) {
            throw new GoalProposalAccessDeniedException("Only the owning student can decide this goal proposal");
        }

        if (proposal.status() != ProposalStatus.PENDING) {
            throw new GoalProposalAlreadyDecidedException("Goal proposal is already decided with status: " + proposal.status());
        }

        if (proposal.expiresAt() != null && proposal.expiresAt().isBefore(Instant.now())) {
            throw new GoalProposalExpiredException("Goal proposal has expired at " + proposal.expiresAt());
        }

        String note = command.decisionNote() != null ? command.decisionNote().trim() : null;

        if (command.decision() == ProposalDecision.REJECT) {
            boolean rejected = proposalPort.rejectProposal(command.proposalId(), command.studentId(), note);
            if (!rejected) {
                throw new GoalProposalAlreadyDecidedException("Goal proposal has already been decided");
            }

            auditService.recordAudit(AuditRecord.builder()
                    .actorUserId(command.studentId())
                    .actorRole("STUDENT")
                    .action("GOAL_PROPOSAL_REJECTED")
                    .targetType("GOAL_PROPOSAL")
                    .targetId(command.proposalId())
                    .requestId(RequestIdHolder.getAsUuid())
                    .occurredAt(Instant.now())
                    .metadataJson("{\"proposalId\":\"" + command.proposalId() + "\",\"goalId\":\"" + proposal.fitnessGoalId() + "\",\"decisionNote\":\"" + escapeJson(note) + "\"}")
                    .build());

            return proposalPort.findById(command.proposalId())
                    .orElseThrow(() -> new IllegalStateException("Failed to load rejected proposal: " + command.proposalId()));
        }

        if (command.decision() == ProposalDecision.ACCEPT) {
            FitnessGoalVersion baseVersion = proposal.baseVersion();
            if (baseVersion == null && proposal.baseGoalVersionId() != null) {
                baseVersion = goalPort.findVersionById(proposal.fitnessGoalId(), proposal.baseGoalVersionId()).orElse(null);
            }
            if (baseVersion == null) {
                throw new StaleGoalProposalException("Base goal version not found or no longer available: " + proposal.baseGoalVersionId());
            }
            if (baseVersion.effectiveUntil() != null) {
                throw new StaleGoalProposalException("Base goal version is no longer active; cannot accept stale proposal");
            }

            GoalProposal proposalToValidate = new GoalProposal(
                    proposal.id(), proposal.studentId(), proposal.fitnessGoalId(), proposal.baseGoalVersionId(),
                    proposal.source(), proposal.createdBy(), proposal.proposedTitle(),
                    proposal.proposedStartDate(), proposal.proposedTargetDate(), proposal.proposedDurationDays(),
                    proposal.reason(), proposal.status(), proposal.decidedBy(), proposal.decidedAt(),
                    proposal.decisionNote(), proposal.expiresAt(), proposal.createdAt(), proposal.updatedAt(),
                    proposal.objectives(), proposal.targets(), baseVersion
            );

            // Re-validate proposal snapshot (timeline, active catalog types/metrics, targets, units, same-journey identity) before acceptance
            validationHelper.validateProposalForAcceptance(proposalToValidate);

            // Canonical snapshot change detection (prevent accepting no-op proposal)
            boolean hasChanges = validationHelper.hasProposalVersionChanges(baseVersion, proposalToValidate);
            if (!hasChanges) {
                throw new GoalVersionNoChangesException("Proposed goal version contains no changes from the current active version");
            }

            Short proposalPrimaryGoalTypeId = proposalToValidate.objectives().stream()
                    .filter(o -> o.priority() == ObjectivePriority.PRIMARY)
                    .map(GoalProposalObjective::goalTypeId)
                    .findFirst()
                    .orElse(null);

            Short basePrimaryGoalTypeId = baseVersion.objectives().stream()
                    .filter(o -> o.priority() == ObjectivePriority.PRIMARY)
                    .map(GoalObjective::goalTypeId)
                    .findFirst()
                    .orElse(null);

            boolean isNewJourney = basePrimaryGoalTypeId != null
                    && proposalPrimaryGoalTypeId != null
                    && !basePrimaryGoalTypeId.equals(proposalPrimaryGoalTypeId);

            if (isNewJourney) {
                String newTitle = (proposal.proposedTitle() != null && !proposal.proposedTitle().isBlank())
                        ? proposal.proposedTitle().trim()
                        : baseVersion.title();

                List<GoalObjective> domainObjectives = proposal.objectives() != null
                        ? proposal.objectives().stream()
                                .map(o -> new GoalObjective(
                                        UUID.randomUUID(), null, o.goalTypeId(), o.goalTypeCode(), o.goalTypeName(),
                                        o.priority(), o.sortOrder(), o.notes()))
                                .toList()
                        : List.of();

                List<GoalTarget> domainTargets = proposal.targets() != null
                        ? proposal.targets().stream()
                                .map(t -> new GoalTarget(
                                        UUID.randomUUID(), null, t.metricDefinitionId(), t.metricCode(), t.metricDisplayName(),
                                        t.exerciseVariationId(), t.startValue(), t.targetValue(), t.targetMinValue(),
                                        t.targetMaxValue(), t.unitId(), t.unitCode(), t.unitSymbol(),
                                        t.targetRepetitions(), t.targetDate(), t.notes(), null))
                                .toList()
                        : List.of();

                UUID newGoalId = UUID.randomUUID();
                UUID newVersionId = UUID.randomUUID();

                FitnessGoalVersion newVersion = new FitnessGoalVersion(
                        newVersionId,
                        newGoalId,
                        1,
                        newTitle,
                        proposal.proposedStartDate(),
                        proposal.proposedTargetDate(),
                        proposal.proposedDurationDays(),
                        Instant.now(),
                        null,
                        null,
                        "INITIAL_CREATION",
                        proposal.reason(),
                        command.studentId(),
                        proposal.id(),
                        Instant.now(),
                        Instant.now(),
                        command.studentId(),
                        VersionLockReason.ACTIVATED,
                        domainObjectives,
                        domainTargets
                );

                FitnessGoal newGoal = new FitnessGoal(
                        newGoalId,
                        command.studentId(),
                        newTitle,
                        GoalStatus.ACTIVE,
                        command.studentId(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        null,
                        Instant.now(),
                        Instant.now(),
                        newVersion
                );

                com.fitnesscoaching.platform.modules.goal.application.model.GoalTransitionResult transitionResult =
                        proposalPort.acceptProposalAsNewJourney(
                                proposalToValidate,
                                command.studentId(),
                                note,
                                newGoal,
                                newVersion,
                                domainObjectives,
                                domainTargets
                        );

                auditService.recordAudit(AuditRecord.builder()
                        .actorUserId(command.studentId())
                        .actorRole("STUDENT")
                        .action("FITNESS_GOAL_REPLACED")
                        .targetType("FITNESS_GOAL")
                        .targetId(proposal.fitnessGoalId())
                        .requestId(RequestIdHolder.getAsUuid())
                        .occurredAt(Instant.now())
                        .metadataJson("{\"previousGoalId\":\"" + proposal.fitnessGoalId() + "\",\"newGoalId\":\"" + newGoalId + "\",\"proposalId\":\"" + command.proposalId() + "\"}")
                        .build());

                auditService.recordAudit(AuditRecord.builder()
                        .actorUserId(command.studentId())
                        .actorRole("STUDENT")
                        .action("FITNESS_GOAL_CREATED")
                        .targetType("FITNESS_GOAL")
                        .targetId(newGoalId)
                        .requestId(RequestIdHolder.getAsUuid())
                        .occurredAt(Instant.now())
                        .metadataJson("{\"goalId\":\"" + newGoalId + "\",\"studentId\":\"" + command.studentId() + "\",\"proposalId\":\"" + command.proposalId() + "\"}")
                        .build());

                auditService.recordAudit(AuditRecord.builder()
                        .actorUserId(command.studentId())
                        .actorRole("STUDENT")
                        .action("FITNESS_GOAL_ACTIVATED")
                        .targetType("FITNESS_GOAL")
                        .targetId(newGoalId)
                        .requestId(RequestIdHolder.getAsUuid())
                        .occurredAt(Instant.now())
                        .metadataJson("{\"goalId\":\"" + newGoalId + "\",\"status\":\"ACTIVE\"}")
                        .build());

                auditService.recordAudit(AuditRecord.builder()
                        .actorUserId(command.studentId())
                        .actorRole("STUDENT")
                        .action("GOAL_TRANSITION_CREATED")
                        .targetType("GOAL_TRANSITION")
                        .targetId(transitionResult.transition().id())
                        .requestId(RequestIdHolder.getAsUuid())
                        .occurredAt(Instant.now())
                        .metadataJson("{\"transitionId\":\"" + transitionResult.transition().id() + "\",\"previousGoalId\":\"" + proposal.fitnessGoalId() + "\",\"newGoalId\":\"" + newGoalId + "\",\"proposalId\":\"" + command.proposalId() + "\"}")
                        .build());

                auditService.recordAudit(AuditRecord.builder()
                        .actorUserId(command.studentId())
                        .actorRole("STUDENT")
                        .action("GOAL_PROPOSAL_ACCEPTED")
                        .targetType("GOAL_PROPOSAL")
                        .targetId(command.proposalId())
                        .requestId(RequestIdHolder.getAsUuid())
                        .occurredAt(Instant.now())
                        .metadataJson("{\"proposalId\":\"" + command.proposalId() + "\",\"goalId\":\"" + proposal.fitnessGoalId() + "\",\"newGoalId\":\"" + newGoalId + "\",\"decisionNote\":\"" + escapeJson(note) + "\"}")
                        .build());

                return proposalPort.findById(command.proposalId())
                        .orElseThrow(() -> new IllegalStateException("Failed to load accepted proposal: " + command.proposalId()));
            }

            FitnessGoalVersion newVersion = proposalPort.acceptProposal(proposalToValidate, command.studentId(), note);

            auditService.recordAudit(AuditRecord.builder()
                    .actorUserId(command.studentId())
                    .actorRole("STUDENT")
                    .action("GOAL_PROPOSAL_ACCEPTED")
                    .targetType("GOAL_PROPOSAL")
                    .targetId(command.proposalId())
                    .requestId(RequestIdHolder.getAsUuid())
                    .occurredAt(Instant.now())
                    .metadataJson("{\"proposalId\":\"" + command.proposalId() + "\",\"goalId\":\"" + proposal.fitnessGoalId() + "\",\"decisionNote\":\"" + escapeJson(note) + "\",\"newVersionId\":\"" + newVersion.id() + "\"}")
                    .build());

            auditService.recordAudit(AuditRecord.builder()
                    .actorUserId(command.studentId())
                    .actorRole("STUDENT")
                    .action("FITNESS_GOAL_VERSION_CREATED")
                    .targetType("FITNESS_GOAL_VERSION")
                    .targetId(newVersion.id())
                    .requestId(RequestIdHolder.getAsUuid())
                    .occurredAt(Instant.now())
                    .metadataJson("{\"goalId\":\"" + proposal.fitnessGoalId() + "\",\"versionNumber\":" + newVersion.versionNumber() + ",\"sourceProposalId\":\"" + command.proposalId() + "\"}")
                    .build());

            return proposalPort.findById(command.proposalId())
                    .orElseThrow(() -> new IllegalStateException("Failed to load accepted proposal: " + command.proposalId()));
        }

        throw new InvalidGoalProposalDecisionException("Unsupported decision: " + command.decision());
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
