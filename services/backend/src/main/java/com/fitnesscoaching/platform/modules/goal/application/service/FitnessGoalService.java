package com.fitnesscoaching.platform.modules.goal.application.service;

import com.fitnesscoaching.platform.common.exception.ActiveFitnessGoalAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.exception.FitnessGoalAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.FitnessGoalNotFoundException;
import com.fitnesscoaching.platform.common.exception.GoalTransitionNotFoundException;
import com.fitnesscoaching.platform.common.exception.GoalVersionNoChangesException;
import com.fitnesscoaching.platform.common.exception.GoalVersionNotFoundException;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.NewGoalJourneyRequiredException;
import com.fitnesscoaching.platform.common.exception.SameGoalJourneyTransitionException;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalTransitionResult;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalVersionPage;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTransitionCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTransitionUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalVersionCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalVersionUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetFitnessGoalDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionDetailQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionsQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionsUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionDetailQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionsQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionsUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.out.FitnessGoalPersistencePort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalCatalogPort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalStudentAuthorityPort;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.goal.domain.GoalObjective;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
import com.fitnesscoaching.platform.modules.goal.domain.GoalTarget;
import com.fitnesscoaching.platform.modules.goal.domain.GoalTransition;
import com.fitnesscoaching.platform.modules.goal.domain.ObjectivePriority;
import com.fitnesscoaching.platform.modules.goal.domain.VersionLockReason;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class FitnessGoalService implements
        CreateFitnessGoalUseCase,
        GetFitnessGoalDetailUseCase,
        GetCurrentFitnessGoalUseCase,
        ActivateFitnessGoalUseCase,
        GetGoalVersionsUseCase,
        GetGoalVersionDetailUseCase,
        CreateGoalVersionUseCase,
        CreateGoalTransitionUseCase,
        GetGoalTransitionsUseCase,
        GetGoalTransitionDetailUseCase {

    private final GoalStudentAuthorityPort goalStudentAuthorityPort;
    private final GoalCatalogPort goalCatalogPort;
    private final FitnessGoalPersistencePort fitnessGoalPersistencePort;
    private final GoalValidationHelper validationHelper;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public FitnessGoalService(
            GoalStudentAuthorityPort goalStudentAuthorityPort,
            GoalCatalogPort goalCatalogPort,
            FitnessGoalPersistencePort fitnessGoalPersistencePort,
            AuditService auditService,
            Clock clock
    ) {
        this(
                goalStudentAuthorityPort,
                goalCatalogPort,
                fitnessGoalPersistencePort,
                new GoalValidationHelper(goalCatalogPort),
                auditService,
                clock
        );
    }

    public FitnessGoalService(
            GoalStudentAuthorityPort goalStudentAuthorityPort,
            GoalCatalogPort goalCatalogPort,
            FitnessGoalPersistencePort fitnessGoalPersistencePort,
            GoalValidationHelper validationHelper,
            AuditService auditService,
            Clock clock
    ) {
        this.goalStudentAuthorityPort = goalStudentAuthorityPort;
        this.goalCatalogPort = goalCatalogPort;
        this.fitnessGoalPersistencePort = fitnessGoalPersistencePort;
        this.validationHelper = validationHelper;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    public FitnessGoal createFitnessGoal(CreateFitnessGoalCommand command) {
        if (command == null) {
            throw new ApplicationValidationException("Command cannot be null");
        }
        if (command.studentId() == null) {
            throw new ApplicationValidationException("studentId cannot be null");
        }

        // 1. Verify student identity, active account, and student profile
        goalStudentAuthorityPort.verifyStudentCanManageGoals(command.studentId());

        // 2. Validate title
        if (command.title() == null || command.title().isBlank()) {
            throw new ApplicationValidationException(
                    "Title is required",
                    List.of(new FieldErrorDto("title", "NotBlank", "Title is required"))
            );
        }
        if (command.title().trim().length() > 200) {
            throw new ApplicationValidationException(
                    "Title must not exceed 200 characters",
                    List.of(new FieldErrorDto("title", "Size", "Title must not exceed 200 characters"))
            );
        }

        // 3. Check active goal conflict if activating immediately
        if (command.activateImmediately() && fitnessGoalPersistencePort.hasActiveGoal(command.studentId())) {
            throw new ActiveFitnessGoalAlreadyExistsException(
                    "Student already has an active fitness goal. Complete, abandon, or pause the existing goal before activating a new one."
            );
        }

        // 4. Validate timeline using unified helper
        GoalValidationHelper.ResolvedTimeline timeline = validationHelper.resolveAndValidateTimeline(
                command.startDate(), command.targetDate(), command.durationDays()
        );
        LocalDate startDate = timeline.startDate();
        LocalDate targetDate = timeline.targetDate();
        Integer durationDays = timeline.durationDays();

        // 5. Validate objectives using unified helper
        List<GoalObjective> domainObjectives = validationHelper.validateAndBuildObjectives(command.objectives());

        // 6. Validate targets using unified helper
        List<GoalTarget> domainTargets = validationHelper.validateAndBuildTargets(command.targets(), startDate);

        // 7. Build aggregates
        UUID goalId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now(clock);

        GoalStatus status = command.activateImmediately() ? GoalStatus.ACTIVE : GoalStatus.DRAFT;
        Instant activatedAt = command.activateImmediately() ? now : null;

        VersionLockReason lockReason = command.activateImmediately() ? VersionLockReason.ACTIVATED : null;
        Instant lockedAt = command.activateImmediately() ? now : null;
        UUID lockedBy = command.activateImmediately() ? command.studentId() : null;

        FitnessGoalVersion version = new FitnessGoalVersion(
                versionId,
                goalId,
                1,
                command.title().trim(),
                startDate,
                targetDate,
                durationDays,
                now,
                null,
                null,
                "INITIAL_CREATION",
                null,
                command.studentId(),
                null,
                now,
                lockedAt,
                lockedBy,
                lockReason,
                domainObjectives,
                domainTargets
        );

        FitnessGoal goal = new FitnessGoal(
                goalId,
                command.studentId(),
                command.title().trim(),
                status,
                command.studentId(),
                activatedAt,
                null,
                null,
                null,
                null,
                now,
                now,
                version
        );

        // 8. Persist atomically
        FitnessGoal createdGoal = fitnessGoalPersistencePort.saveGoalAggregate(goal, version, domainObjectives, domainTargets, command.activateImmediately());

        // 9. Record immutable audit
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.studentId())
                .actorRole("STUDENT")
                .action("FITNESS_GOAL_CREATED")
                .targetType("FITNESS_GOAL")
                .targetId(goalId)
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson("{\"status\":\"" + status.name() + "\",\"title\":\"" + escapeJson(goal.title()) + "\"}")
                .build());

        if (command.activateImmediately()) {
            auditService.recordAudit(AuditRecord.builder()
                    .actorUserId(command.studentId())
                    .actorRole("STUDENT")
                    .action("FITNESS_GOAL_ACTIVATED")
                    .targetType("FITNESS_GOAL")
                    .targetId(goalId)
                    .requestId(RequestIdHolder.getAsUuid())
                    .occurredAt(now)
                    .metadataJson("{\"fromStatus\":\"DRAFT\",\"toStatus\":\"ACTIVE\"}")
                    .build());
        }

        return createdGoal;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FitnessGoal> getCurrentFitnessGoal(UUID studentId) {
        if (studentId == null) {
            throw new ApplicationValidationException("studentId cannot be null");
        }
        goalStudentAuthorityPort.verifyStudentCanManageGoals(studentId);
        return fitnessGoalPersistencePort.findCurrentActiveByStudentId(studentId);
    }

    @Override
    @Transactional(readOnly = true)
    public FitnessGoal getFitnessGoalDetail(UUID studentId, UUID goalId) {
        if (studentId == null) {
            throw new ApplicationValidationException("studentId cannot be null");
        }
        if (goalId == null) {
            throw new ApplicationValidationException("goalId cannot be null");
        }

        goalStudentAuthorityPort.verifyStudentCanManageGoals(studentId);

        FitnessGoal goal = fitnessGoalPersistencePort.findById(goalId)
                .orElseThrow(() -> new FitnessGoalNotFoundException("Fitness goal not found: " + goalId));

        if (!goal.studentId().equals(studentId)) {
            throw new FitnessGoalAccessDeniedException("Access denied to fitness goal: " + goalId);
        }

        return goal;
    }

    @Override
    public FitnessGoal activateFitnessGoal(ActivateFitnessGoalCommand command) {
        if (command == null) {
            throw new ApplicationValidationException("Command cannot be null");
        }
        if (command.studentId() == null) {
            throw new ApplicationValidationException("studentId cannot be null");
        }
        if (command.goalId() == null) {
            throw new ApplicationValidationException("goalId cannot be null");
        }

        // 1. Verify student identity, active account, and student profile
        goalStudentAuthorityPort.verifyStudentCanManageGoals(command.studentId());

        // 2. Fetch goal and check student ownership
        FitnessGoal goal = fitnessGoalPersistencePort.findById(command.goalId())
                .orElseThrow(() -> new FitnessGoalNotFoundException("Fitness goal not found: " + command.goalId()));

        if (!goal.studentId().equals(command.studentId())) {
            throw new FitnessGoalAccessDeniedException("Access denied to fitness goal: " + command.goalId());
        }

        // 3. State machine validation
        if (goal.status() != GoalStatus.DRAFT) {
            throw new InvalidLifecycleTransitionException(
                    "Cannot activate fitness goal in status: " + goal.status()
            );
        }

        // 4. Check active goal constraint across the student
        if (fitnessGoalPersistencePort.hasActiveGoal(command.studentId())) {
            throw new ActiveFitnessGoalAlreadyExistsException(
                    "Student already has an active fitness goal. Complete, abandon, or pause the existing goal before activating a new one."
            );
        }

        // 5. Execute atomic activation in persistence layer
        Instant now = Instant.now(clock);
        FitnessGoal activated = fitnessGoalPersistencePort.activateGoal(goal.id(), command.studentId(), command.reason());

        // 7. Record immutable audit
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.studentId())
                .actorRole("STUDENT")
                .action("FITNESS_GOAL_ACTIVATED")
                .targetType("FITNESS_GOAL")
                .targetId(goal.id())
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson("{\"fromStatus\":\"DRAFT\",\"toStatus\":\"ACTIVE\"}")
                .build());

        return activated;
    }

    @Override
    @Transactional(readOnly = true)
    public GoalVersionPage getGoalVersions(GetGoalVersionsQuery query) {
        if (query == null) {
            throw new ApplicationValidationException("Query cannot be null");
        }
        if (query.studentId() == null) {
            throw new ApplicationValidationException("studentId cannot be null",
                    List.of(new FieldErrorDto("studentId", "NotNull", "studentId cannot be null")));
        }
        if (query.goalId() == null) {
            throw new ApplicationValidationException("goalId cannot be null",
                    List.of(new FieldErrorDto("goalId", "NotNull", "goalId cannot be null")));
        }

        goalStudentAuthorityPort.verifyStudentCanManageGoals(query.studentId());

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

        FitnessGoal goal = fitnessGoalPersistencePort.findById(query.goalId())
                .orElseThrow(() -> new FitnessGoalNotFoundException("Fitness goal not found: " + query.goalId()));

        if (!goal.studentId().equals(query.studentId())) {
            throw new FitnessGoalAccessDeniedException("Access denied to fitness goal: " + query.goalId());
        }

        long total = fitnessGoalPersistencePort.countVersionsByGoalId(query.goalId());
        List<FitnessGoalVersion> versions = fitnessGoalPersistencePort.findVersionsByGoalId(query.goalId(), query.size(), offset);
        int totalPages = query.size() == 0 ? 0 : (int) Math.ceil((double) total / query.size());

        return new GoalVersionPage(versions, query.page(), query.size(), total, totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public FitnessGoalVersion getGoalVersionDetail(GetGoalVersionDetailQuery query) {
        if (query == null) {
            throw new ApplicationValidationException("Query cannot be null");
        }
        if (query.studentId() == null) {
            throw new ApplicationValidationException("studentId cannot be null",
                    List.of(new FieldErrorDto("studentId", "NotNull", "studentId cannot be null")));
        }
        if (query.goalId() == null) {
            throw new ApplicationValidationException("goalId cannot be null",
                    List.of(new FieldErrorDto("goalId", "NotNull", "goalId cannot be null")));
        }
        if (query.versionId() == null) {
            throw new ApplicationValidationException("versionId cannot be null",
                    List.of(new FieldErrorDto("versionId", "NotNull", "versionId cannot be null")));
        }

        goalStudentAuthorityPort.verifyStudentCanManageGoals(query.studentId());

        FitnessGoal goal = fitnessGoalPersistencePort.findById(query.goalId())
                .orElseThrow(() -> new FitnessGoalNotFoundException("Fitness goal not found: " + query.goalId()));

        if (!goal.studentId().equals(query.studentId())) {
            throw new FitnessGoalAccessDeniedException("Access denied to fitness goal: " + query.goalId());
        }

        return fitnessGoalPersistencePort.findVersionById(query.goalId(), query.versionId())
                .orElseThrow(() -> new GoalVersionNotFoundException("Goal version not found: " + query.versionId()));
    }

    @Override
    @Transactional
    public FitnessGoalVersion createGoalVersion(CreateGoalVersionCommand command) {
        if (command == null) {
            throw new ApplicationValidationException("Command cannot be null");
        }
        if (command.studentId() == null) {
            throw new ApplicationValidationException("studentId cannot be null",
                    List.of(new FieldErrorDto("studentId", "NotNull", "studentId cannot be null")));
        }
        if (command.goalId() == null) {
            throw new ApplicationValidationException("goalId cannot be null",
                    List.of(new FieldErrorDto("goalId", "NotNull", "goalId cannot be null")));
        }

        // 1. Verify student authority from PostgreSQL
        goalStudentAuthorityPort.verifyStudentCanManageGoals(command.studentId());

        // 2. Fetch goal and check student ownership
        FitnessGoal goal = fitnessGoalPersistencePort.findById(command.goalId())
                .orElseThrow(() -> new FitnessGoalNotFoundException("Fitness goal not found: " + command.goalId()));

        if (!goal.studentId().equals(command.studentId())) {
            throw new FitnessGoalAccessDeniedException("Access denied to fitness goal: " + command.goalId());
        }

        // 3. Invariant: only ACTIVE goals can have new same-journey versions created
        if (goal.status() != GoalStatus.ACTIVE) {
            throw new InvalidLifecycleTransitionException(
                    "Goal versions can only be created for an ACTIVE fitness goal. Current status: " + goal.status());
        }

        FitnessGoalVersion currentVersion = goal.currentVersion();
        if (currentVersion == null) {
            throw new IllegalStateException("Active fitness goal does not have a current version: " + goal.id());
        }

        // 4. Validate changeReason (Finding 2: 100 char limit to match DB varchar(100))
        if (command.changeReason() == null || command.changeReason().isBlank()) {
            throw new ApplicationValidationException("Change reason is required",
                    List.of(new FieldErrorDto("changeReason", "NotBlank", "Change reason is required")));
        }
        if (command.changeReason().trim().length() > 100) {
            throw new ApplicationValidationException("Change reason must not exceed 100 characters",
                    List.of(new FieldErrorDto("changeReason", "Size", "Change reason must not exceed 100 characters")));
        }
        if (command.changeSummary() != null && command.changeSummary().trim().length() > 2000) {
            throw new ApplicationValidationException("Change summary must not exceed 2000 characters",
                    List.of(new FieldErrorDto("changeSummary", "Size", "Change summary must not exceed 2000 characters")));
        }

        // 5. Title validation: if null or blank, default to current version title or goal title
        String versionTitle = (command.title() != null && !command.title().isBlank())
                ? command.title().trim()
                : (currentVersion.title() != null ? currentVersion.title() : goal.title());
        if (versionTitle.length() > 200) {
            throw new ApplicationValidationException("Title must not exceed 200 characters",
                    List.of(new FieldErrorDto("title", "Size", "Title must not exceed 200 characters")));
        }

        // 6. Validate and resolve timeline against current version snapshot
        GoalValidationHelper.ResolvedTimeline timeline = validationHelper.resolveAndValidateProposalTimeline(
                command.startDate(),
                command.targetDate(),
                command.durationDays(),
                currentVersion.startDate(),
                currentVersion.targetDate(),
                currentVersion.durationDays()
        );

        // 7. Validate objectives (catalog active, single primary, no duplicate types)
        List<GoalObjective> domainObjectives = validationHelper.validateAndBuildObjectives(command.objectives());

        // 8. Same-journey identity check (Finding 1: PRIMARY objective must match current version)
        Short newPrimaryGoalTypeId = domainObjectives.stream()
                .filter(o -> o.priority() == ObjectivePriority.PRIMARY)
                .map(GoalObjective::goalTypeId)
                .findFirst()
                .orElse(null);
        validationHelper.validateSameJourneyIdentity(currentVersion, newPrimaryGoalTypeId);

        // 9. Validate targets (catalog active, valid dimensions, valid ranges, metric uniqueness, unified target values)
        LocalDate effectiveStartDate = timeline.startDate() != null ? timeline.startDate() : currentVersion.startDate();
        List<GoalTarget> domainTargets = validationHelper.validateAndBuildTargets(command.targets(), effectiveStartDate);

        // 10. Canonical snapshot change detection (Finding 5: prevent no-op version creation)
        boolean hasChanges = validationHelper.hasVersionChanges(
                currentVersion,
                versionTitle,
                timeline,
                domainObjectives,
                domainTargets
        );
        if (!hasChanges) {
            throw new GoalVersionNoChangesException("Proposed goal version contains no changes from the current active version");
        }

        // 11. Prepare new domain version
        UUID newVersionId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        int nextVersionNumber = currentVersion.versionNumber() + 1;

        FitnessGoalVersion newVersion = new FitnessGoalVersion(
                newVersionId,
                goal.id(),
                nextVersionNumber,
                versionTitle,
                timeline.startDate(),
                timeline.targetDate(),
                timeline.durationDays(),
                now,
                null,
                null,
                command.changeReason().trim(),
                command.changeSummary() != null ? command.changeSummary().trim() : null,
                command.studentId(),
                null,
                now,
                now,
                command.studentId(),
                VersionLockReason.APPROVED,
                domainObjectives,
                domainTargets
        );

        // 12. Persist atomically with CAS closing of currentVersion
        FitnessGoalVersion created = fitnessGoalPersistencePort.createNewGoalVersion(
                goal.id(),
                command.studentId(),
                newVersion,
                currentVersion.id()
        );

        // 13. Record immutable audit
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.studentId())
                .actorRole("STUDENT")
                .action("FITNESS_GOAL_VERSION_CREATED")
                .targetType("FITNESS_GOAL_VERSION")
                .targetId(created.id())
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson("{\"goalId\":\"" + goal.id() + "\",\"versionNumber\":" + created.versionNumber() + ",\"changeReason\":\"" + escapeJson(command.changeReason()) + "\"}")
                .build());

        return created;
    }

    @Override
    public GoalTransitionResult createGoalTransition(CreateGoalTransitionCommand command) {
        // 1. Basic validation
        if (command.sourceGoalId() == null) {
            throw new ApplicationValidationException("Source goal ID is required",
                    List.of(new FieldErrorDto("sourceGoalId", "NotNull", "Source goal ID is required")));
        }
        if (command.studentId() == null) {
            throw new ApplicationValidationException("Student ID is required",
                    List.of(new FieldErrorDto("studentId", "NotNull", "Student ID is required")));
        }
        if (command.transitionReason() == null || command.transitionReason().trim().isEmpty()) {
            throw new ApplicationValidationException("Transition reason is required",
                    List.of(new FieldErrorDto("transitionReason", "NotBlank", "Transition reason is required")));
        }
        if (command.transitionReason().trim().length() > 100) {
            throw new ApplicationValidationException("Transition reason must not exceed 100 characters",
                    List.of(new FieldErrorDto("transitionReason", "Size", "Transition reason must not exceed 100 characters")));
        }
        if (command.notes() != null && command.notes().trim().length() > 2000) {
            throw new ApplicationValidationException("Notes must not exceed 2000 characters",
                    List.of(new FieldErrorDto("notes", "Size", "Notes must not exceed 2000 characters")));
        }
        if (command.title() == null || command.title().trim().isEmpty()) {
            throw new ApplicationValidationException("Goal title is required",
                    List.of(new FieldErrorDto("title", "NotBlank", "Goal title is required")));
        }
        if (command.title().trim().length() > 200) {
            throw new ApplicationValidationException("Goal title must not exceed 200 characters",
                    List.of(new FieldErrorDto("title", "Size", "Goal title must not exceed 200 characters")));
        }

        // 2. Student authority check
        goalStudentAuthorityPort.verifyStudentCanManageGoals(command.studentId());

        // 3. Load previous goal and verify ownership
        FitnessGoal previousGoal = fitnessGoalPersistencePort.findById(command.sourceGoalId())
                .orElseThrow(() -> new FitnessGoalNotFoundException("Fitness goal not found: " + command.sourceGoalId()));

        if (!previousGoal.studentId().equals(command.studentId())) {
            throw new FitnessGoalAccessDeniedException("Student does not own this fitness goal");
        }

        // 4. Status check: only ACTIVE goals can transition to a new journey
        if (previousGoal.status() != GoalStatus.ACTIVE) {
            throw new InvalidLifecycleTransitionException("Cannot transition a goal that is not ACTIVE; current status is: " + previousGoal.status());
        }

        // 5. Load current version of previous goal to get current primary goal type
        FitnessGoalVersion currentVersion = previousGoal.currentVersion();
        if (currentVersion == null) {
            currentVersion = fitnessGoalPersistencePort.findVersionsByGoalId(previousGoal.id(), 1, 0).stream().findFirst().orElse(null);
        }
        Short currentPrimaryGoalTypeId = null;
        if (currentVersion != null && currentVersion.objectives() != null) {
            currentPrimaryGoalTypeId = currentVersion.objectives().stream()
                    .filter(o -> o.priority() == ObjectivePriority.PRIMARY)
                    .map(GoalObjective::goalTypeId)
                    .findFirst()
                    .orElse(null);
        }

        // 6. Validate new objectives
        List<GoalObjective> domainObjectives = validationHelper.validateAndBuildObjectives(command.objectives());
        Short newPrimaryGoalTypeId = domainObjectives.stream()
                .filter(o -> o.priority() == ObjectivePriority.PRIMARY)
                .map(GoalObjective::goalTypeId)
                .findFirst()
                .orElse(null);

        // 7. Enforce new journey rule: PRIMARY goal type MUST differ
        if (currentPrimaryGoalTypeId != null && currentPrimaryGoalTypeId.equals(newPrimaryGoalTypeId)) {
            throw new SameGoalJourneyTransitionException("Cannot transition to a new journey with the same primary goal type; use goal versioning instead");
        }

        // 8. Validate timeline
        var timeline = validationHelper.resolveAndValidateTimeline(command.startDate(), command.targetDate(), command.durationDays());

        // 9. Validate targets
        List<GoalTarget> domainTargets = validationHelper.validateAndBuildTargets(command.targets(), timeline.startDate());

        Instant now = Instant.now(clock);
        UUID newGoalId = UUID.randomUUID();
        UUID newVersionId = UUID.randomUUID();
        String newTitle = command.title().trim();

        FitnessGoalVersion newVersion = new FitnessGoalVersion(
                newVersionId,
                newGoalId,
                1,
                newTitle,
                timeline.startDate(),
                timeline.targetDate(),
                timeline.durationDays(),
                now,
                null,
                null,
                "INITIAL_CREATION",
                command.transitionReason().trim(),
                command.studentId(),
                null,
                now,
                now,
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
                now,
                null,
                null,
                null,
                null,
                now,
                now,
                newVersion
        );

        // 10. Persist atomically
        GoalTransitionResult result = fitnessGoalPersistencePort.createDirectGoalTransition(
                previousGoal.id(),
                command.studentId(),
                command.transitionReason().trim(),
                command.notes() != null ? command.notes().trim() : null,
                newGoal,
                newVersion,
                domainObjectives,
                domainTargets
        );

        // 11. Record audit logs
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.studentId())
                .actorRole("STUDENT")
                .action("FITNESS_GOAL_REPLACED")
                .targetType("FITNESS_GOAL")
                .targetId(previousGoal.id())
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson("{\"previousGoalId\":\"" + previousGoal.id() + "\",\"newGoalId\":\"" + newGoalId + "\",\"reason\":\"" + escapeJson(command.transitionReason()) + "\"}")
                .build());

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.studentId())
                .actorRole("STUDENT")
                .action("FITNESS_GOAL_CREATED")
                .targetType("FITNESS_GOAL")
                .targetId(newGoalId)
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson("{\"goalId\":\"" + newGoalId + "\",\"studentId\":\"" + command.studentId() + "\",\"title\":\"" + escapeJson(newTitle) + "\"}")
                .build());

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.studentId())
                .actorRole("STUDENT")
                .action("FITNESS_GOAL_ACTIVATED")
                .targetType("FITNESS_GOAL")
                .targetId(newGoalId)
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson("{\"goalId\":\"" + newGoalId + "\",\"status\":\"ACTIVE\"}")
                .build());

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.studentId())
                .actorRole("STUDENT")
                .action("GOAL_TRANSITION_CREATED")
                .targetType("GOAL_TRANSITION")
                .targetId(result.transition().id())
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson("{\"transitionId\":\"" + result.transition().id() + "\",\"previousGoalId\":\"" + previousGoal.id() + "\",\"newGoalId\":\"" + newGoalId + "\",\"transitionReason\":\"" + escapeJson(command.transitionReason()) + "\"}")
                .build());

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GoalTransition> getGoalTransitions(GetGoalTransitionsQuery query) {
        if (query.goalId() == null) {
            throw new ApplicationValidationException("Goal ID is required",
                    List.of(new FieldErrorDto("goalId", "NotNull", "Goal ID is required")));
        }
        if (query.studentId() == null) {
            throw new ApplicationValidationException("Student ID is required",
                    List.of(new FieldErrorDto("studentId", "NotNull", "Student ID is required")));
        }

        FitnessGoal goal = fitnessGoalPersistencePort.findById(query.goalId())
                .orElseThrow(() -> new FitnessGoalNotFoundException("Fitness goal not found: " + query.goalId()));

        if (!goal.studentId().equals(query.studentId())) {
            throw new FitnessGoalAccessDeniedException("Access denied: You are not authorized to view transitions for this goal");
        }

        goalStudentAuthorityPort.verifyStudentCanManageGoals(query.studentId());

        return fitnessGoalPersistencePort.findTransitionsByGoalId(query.goalId());
    }

    @Override
    @Transactional(readOnly = true)
    public GoalTransition getGoalTransitionDetail(GetGoalTransitionDetailQuery query) {
        if (query.goalId() == null) {
            throw new ApplicationValidationException("Goal ID is required",
                    List.of(new FieldErrorDto("goalId", "NotNull", "Goal ID is required")));
        }
        if (query.transitionId() == null) {
            throw new ApplicationValidationException("Transition ID is required",
                    List.of(new FieldErrorDto("transitionId", "NotNull", "Transition ID is required")));
        }
        if (query.studentId() == null) {
            throw new ApplicationValidationException("Student ID is required",
                    List.of(new FieldErrorDto("studentId", "NotNull", "Student ID is required")));
        }

        FitnessGoal goal = fitnessGoalPersistencePort.findById(query.goalId())
                .orElseThrow(() -> new FitnessGoalNotFoundException("Fitness goal not found: " + query.goalId()));

        if (!goal.studentId().equals(query.studentId())) {
            throw new FitnessGoalAccessDeniedException("Access denied: You are not authorized to view this goal transition");
        }

        goalStudentAuthorityPort.verifyStudentCanManageGoals(query.studentId());

        GoalTransition transition = fitnessGoalPersistencePort.findTransitionById(query.transitionId())
                .orElseThrow(() -> new GoalTransitionNotFoundException("Goal transition not found: " + query.transitionId()));

        if (!transition.previousGoalId().equals(query.goalId()) && !transition.newGoalId().equals(query.goalId())) {
            throw new GoalTransitionNotFoundException("Goal transition does not belong to specified goal: " + query.transitionId());
        }

        return transition;
    }

    private String escapeJson(String input) {
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
