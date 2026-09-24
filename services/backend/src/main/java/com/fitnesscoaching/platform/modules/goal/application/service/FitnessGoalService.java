package com.fitnesscoaching.platform.modules.goal.application.service;

import com.fitnesscoaching.platform.common.exception.ActiveFitnessGoalAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.exception.FitnessGoalAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.FitnessGoalNotFoundException;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalObjectiveCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTargetCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetFitnessGoalDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.out.FitnessGoalPersistencePort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalCatalogPort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalStudentAuthorityPort;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.goal.domain.GoalObjective;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
import com.fitnesscoaching.platform.modules.goal.domain.GoalTarget;
import com.fitnesscoaching.platform.modules.goal.domain.ObjectivePriority;
import com.fitnesscoaching.platform.modules.goal.domain.VersionLockReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class FitnessGoalService implements
        CreateFitnessGoalUseCase,
        GetFitnessGoalDetailUseCase,
        GetCurrentFitnessGoalUseCase,
        ActivateFitnessGoalUseCase {

    private final GoalStudentAuthorityPort goalStudentAuthorityPort;
    private final GoalCatalogPort goalCatalogPort;
    private final FitnessGoalPersistencePort fitnessGoalPersistencePort;
    private final AuditService auditService;
    private final Clock clock;

    public FitnessGoalService(
            GoalStudentAuthorityPort goalStudentAuthorityPort,
            GoalCatalogPort goalCatalogPort,
            FitnessGoalPersistencePort fitnessGoalPersistencePort,
            AuditService auditService,
            Clock clock
    ) {
        this.goalStudentAuthorityPort = goalStudentAuthorityPort;
        this.goalCatalogPort = goalCatalogPort;
        this.fitnessGoalPersistencePort = fitnessGoalPersistencePort;
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

        // 3. Validate timeline
        if (command.startDate() == null) {
            throw new ApplicationValidationException(
                    "Start date is required",
                    List.of(new FieldErrorDto("startDate", "NotNull", "Start date is required"))
            );
        }

        LocalDate startDate = command.startDate();
        LocalDate targetDate = command.targetDate();
        Integer durationDays = command.durationDays();

        if (targetDate != null) {
            if (!targetDate.isAfter(startDate)) {
                throw new ApplicationValidationException(
                        "Target date must be strictly after start date",
                        List.of(new FieldErrorDto("targetDate", "InvalidRange", "Target date must be strictly after start date"))
                );
            }
        }

        if (durationDays != null) {
            if (durationDays <= 0) {
                throw new ApplicationValidationException(
                        "Duration days must be positive",
                        List.of(new FieldErrorDto("durationDays", "Positive", "Duration days must be positive"))
                );
            }
        }

        if (targetDate != null && durationDays != null) {
            long calculatedDays = ChronoUnit.DAYS.between(startDate, targetDate);
            if (calculatedDays != durationDays.longValue()) {
                throw new ApplicationValidationException(
                        "Duration days does not match target date",
                        List.of(new FieldErrorDto("durationDays", "Mismatch",
                                "Duration days (" + durationDays + ") does not match target date duration (" + calculatedDays + " days)"))
                );
            }
        } else if (targetDate == null && durationDays != null) {
            targetDate = startDate.plusDays(durationDays);
        } else if (durationDays == null && targetDate != null) {
            durationDays = (int) ChronoUnit.DAYS.between(startDate, targetDate);
        }

        // 4. Validate objectives
        if (command.objectives() == null || command.objectives().isEmpty()) {
            throw new ApplicationValidationException(
                    "At least one objective is required",
                    List.of(new FieldErrorDto("objectives", "NotEmpty", "At least one objective is required"))
            );
        }

        long primaryCount = command.objectives().stream()
                .filter(o -> o.priority() == ObjectivePriority.PRIMARY)
                .count();
        if (primaryCount != 1) {
            throw new ApplicationValidationException(
                    "Fitness goal must have exactly one PRIMARY objective",
                    List.of(new FieldErrorDto("objectives", "InvalidPrimaryCount", "Fitness goal must have exactly one PRIMARY objective"))
            );
        }

        Set<Short> seenGoalTypeIds = new HashSet<>();
        List<GoalObjective> domainObjectives = new ArrayList<>();
        int defaultSort = 0;

        for (int i = 0; i < command.objectives().size(); i++) {
            CreateGoalObjectiveCommand objCmd = command.objectives().get(i);
            String fieldPrefix = "objectives[" + i + "]";

            GoalCatalogPort.GoalTypeCatalogView goalType = null;
            if (objCmd.goalTypeId() != null) {
                goalType = goalCatalogPort.findGoalTypeById(objCmd.goalTypeId()).orElse(null);
            } else if (objCmd.goalTypeCode() != null && !objCmd.goalTypeCode().isBlank()) {
                goalType = goalCatalogPort.findGoalTypeByCode(objCmd.goalTypeCode().trim().toUpperCase()).orElse(null);
            }

            if (goalType == null) {
                throw new ApplicationValidationException(
                        "Goal type is invalid or does not exist",
                        List.of(new FieldErrorDto(fieldPrefix + ".goalTypeId", "NotFound", "Goal type does not exist"))
                );
            }
            if (!goalType.isActive()) {
                throw new ApplicationValidationException(
                        "Goal type is inactive",
                        List.of(new FieldErrorDto(fieldPrefix + ".goalTypeId", "Inactive", "Goal type is inactive"))
                );
            }
            if (!seenGoalTypeIds.add(goalType.id())) {
                throw new ApplicationValidationException(
                        "Duplicate goal type in objectives",
                        List.of(new FieldErrorDto(fieldPrefix + ".goalTypeId", "Duplicate", "Duplicate goal type specified"))
                );
            }

            int sortOrder = objCmd.sortOrder() != null ? objCmd.sortOrder() : defaultSort++;
            domainObjectives.add(new GoalObjective(
                    UUID.randomUUID(),
                    null,
                    goalType.id(),
                    goalType.code(),
                    goalType.name(),
                    objCmd.priority() != null ? objCmd.priority() : ObjectivePriority.SECONDARY,
                    sortOrder,
                    objCmd.notes()
            ));
        }

        // 5. Validate targets
        List<GoalTarget> domainTargets = new ArrayList<>();
        if (command.targets() != null && !command.targets().isEmpty()) {
            Set<Integer> seenMetricIds = new HashSet<>();

            for (int i = 0; i < command.targets().size(); i++) {
                CreateGoalTargetCommand tgtCmd = command.targets().get(i);
                String fieldPrefix = "targets[" + i + "]";

                GoalCatalogPort.MetricDefinitionCatalogView metric = null;
                if (tgtCmd.metricDefinitionId() != null) {
                    metric = goalCatalogPort.findMetricDefinitionById(tgtCmd.metricDefinitionId()).orElse(null);
                } else if (tgtCmd.metricCode() != null && !tgtCmd.metricCode().isBlank()) {
                    metric = goalCatalogPort.findMetricDefinitionByCode(tgtCmd.metricCode().trim().toUpperCase()).orElse(null);
                }

                if (metric == null) {
                    throw new ApplicationValidationException(
                            "Metric definition is invalid or does not exist",
                            List.of(new FieldErrorDto(fieldPrefix + ".metricDefinitionId", "NotFound", "Metric definition does not exist"))
                    );
                }
                if (!metric.isActive()) {
                    throw new ApplicationValidationException(
                            "Metric definition is inactive",
                            List.of(new FieldErrorDto(fieldPrefix + ".metricDefinitionId", "Inactive", "Metric definition is inactive"))
                    );
                }
                if (!seenMetricIds.add(metric.id())) {
                    throw new ApplicationValidationException(
                            "Duplicate target metric definition specified for this goal version",
                            List.of(new FieldErrorDto(fieldPrefix + ".metricDefinitionId", "Duplicate", "Duplicate target metric"))
                    );
                }

                GoalCatalogPort.MeasurementUnitCatalogView unit = null;
                if (tgtCmd.unitId() != null) {
                    unit = goalCatalogPort.findMeasurementUnitById(tgtCmd.unitId()).orElse(null);
                } else if (tgtCmd.unitCode() != null && !tgtCmd.unitCode().isBlank()) {
                    unit = goalCatalogPort.findMeasurementUnitByCode(tgtCmd.unitCode().trim().toUpperCase()).orElse(null);
                }

                if (unit == null) {
                    throw new ApplicationValidationException(
                            "Measurement unit is invalid or does not exist",
                            List.of(new FieldErrorDto(fieldPrefix + ".unitId", "NotFound", "Measurement unit does not exist"))
                    );
                }

                if (metric.defaultUnitDimension() != null && !metric.defaultUnitDimension().equalsIgnoreCase(unit.dimension())) {
                    throw new ApplicationValidationException(
                            "Measurement unit dimension is incompatible with metric dimension",
                            List.of(new FieldErrorDto(fieldPrefix + ".unitId", "DimensionMismatch", "Unit dimension does not match metric dimension"))
                    );
                }

                // Numeric values validation
                boolean hasTargetValue = tgtCmd.targetValue() != null
                        || tgtCmd.targetMinValue() != null
                        || tgtCmd.targetMaxValue() != null;
                if (!hasTargetValue) {
                    throw new ApplicationValidationException(
                            "At least one target value must be specified",
                            List.of(new FieldErrorDto(fieldPrefix + ".targetValue", "NotNull", "At least one target value must be specified"))
                    );
                }

                if (tgtCmd.targetMinValue() != null && tgtCmd.targetMaxValue() != null
                        && tgtCmd.targetMaxValue().compareTo(tgtCmd.targetMinValue()) < 0) {
                    throw new ApplicationValidationException(
                            "Target max value must be greater than or equal to min value",
                            List.of(new FieldErrorDto(fieldPrefix + ".targetMaxValue", "InvalidRange", "Target max value cannot be less than min value"))
                    );
                }

                if (tgtCmd.targetRepetitions() != null && tgtCmd.targetRepetitions() <= 0) {
                    throw new ApplicationValidationException(
                            "Target repetitions must be positive",
                            List.of(new FieldErrorDto(fieldPrefix + ".targetRepetitions", "Positive", "Target repetitions must be positive"))
                    );
                }

                if (tgtCmd.targetDate() != null && tgtCmd.targetDate().isBefore(startDate)) {
                    throw new ApplicationValidationException(
                            "Target date cannot be before goal start date",
                            List.of(new FieldErrorDto(fieldPrefix + ".targetDate", "InvalidRange", "Target date cannot be before start date"))
                    );
                }

                domainTargets.add(new GoalTarget(
                        UUID.randomUUID(),
                        null,
                        metric.id(),
                        metric.code(),
                        metric.displayName(),
                        tgtCmd.exerciseVariationId(),
                        tgtCmd.startValue(),
                        tgtCmd.targetValue(),
                        tgtCmd.targetMinValue(),
                        tgtCmd.targetMaxValue(),
                        unit.id(),
                        unit.code(),
                        unit.symbol(),
                        tgtCmd.targetRepetitions(),
                        tgtCmd.targetDate(),
                        tgtCmd.notes(),
                        Instant.now(clock)
                ));
            }
        }

        // 6. Check existing active goal if activateImmediately is true
        if (command.activateImmediately()) {
            if (fitnessGoalPersistencePort.hasActiveGoal(command.studentId())) {
                throw new ActiveFitnessGoalAlreadyExistsException(
                        "Student already has an active fitness goal.");
            }
        }

        Instant now = Instant.now(clock);
        UUID goalId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        FitnessGoal goal = new FitnessGoal(
                goalId,
                command.studentId(),
                command.title().trim(),
                command.activateImmediately() ? GoalStatus.ACTIVE : GoalStatus.DRAFT,
                command.studentId(),
                command.activateImmediately() ? now : null,
                null,
                null,
                null,
                null,
                now,
                now,
                null
        );

        FitnessGoalVersion version = new FitnessGoalVersion(
                versionId,
                goalId,
                1,
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
                command.activateImmediately() ? now : null,
                command.activateImmediately() ? command.studentId() : null,
                command.activateImmediately() ? VersionLockReason.ACTIVATED : null,
                domainObjectives,
                domainTargets
        );

        FitnessGoal saved = fitnessGoalPersistencePort.saveGoalAggregate(
                goal,
                version,
                domainObjectives,
                domainTargets,
                command.activateImmediately()
        );

        // 7. Record immutable audit
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.studentId())
                .actorRole("STUDENT")
                .action("FITNESS_GOAL_CREATED")
                .targetType("FITNESS_GOAL")
                .targetId(saved.id())
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson("{\"title\":\"" + escapeJson(saved.title()) + "\",\"status\":\"" + saved.status().name() + "\"}")
                .build());

        if (command.activateImmediately()) {
            auditService.recordAudit(AuditRecord.builder()
                    .actorUserId(command.studentId())
                    .actorRole("STUDENT")
                    .action("FITNESS_GOAL_ACTIVATED")
                    .targetType("FITNESS_GOAL")
                    .targetId(saved.id())
                    .requestId(RequestIdHolder.getAsUuid())
                    .occurredAt(now)
                    .metadataJson("{\"reason\":\"Initial goal creation and activation\"}")
                    .build());
        }

        return saved;
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
    @Transactional(readOnly = true)
    public Optional<FitnessGoal> getCurrentFitnessGoal(UUID studentId) {
        if (studentId == null) {
            throw new ApplicationValidationException("studentId cannot be null");
        }

        goalStudentAuthorityPort.verifyStudentCanManageGoals(studentId);

        return fitnessGoalPersistencePort.findCurrentActiveByStudentId(studentId);
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

        goalStudentAuthorityPort.verifyStudentCanManageGoals(command.studentId());

        FitnessGoal goal = fitnessGoalPersistencePort.findById(command.goalId())
                .orElseThrow(() -> new FitnessGoalNotFoundException("Fitness goal not found: " + command.goalId()));

        if (!goal.studentId().equals(command.studentId())) {
            throw new FitnessGoalAccessDeniedException("Access denied to fitness goal: " + command.goalId());
        }

        if (goal.status() != GoalStatus.DRAFT) {
            throw new InvalidLifecycleTransitionException(
                    "Cannot activate fitness goal in status: " + goal.status());
        }

        if (fitnessGoalPersistencePort.hasActiveGoal(command.studentId())) {
            throw new ActiveFitnessGoalAlreadyExistsException(
                    "Student already has an active fitness goal.");
        }

        FitnessGoal activated = fitnessGoalPersistencePort.activateGoal(
                command.goalId(),
                command.studentId(),
                command.reason()
        );

        Instant now = Instant.now(clock);
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.studentId())
                .actorRole("STUDENT")
                .action("FITNESS_GOAL_ACTIVATED")
                .targetType("FITNESS_GOAL")
                .targetId(activated.id())
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson("{\"reason\":\"" + escapeJson(command.reason() != null ? command.reason() : "Goal activated") + "\"}")
                .build());

        return activated;
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
