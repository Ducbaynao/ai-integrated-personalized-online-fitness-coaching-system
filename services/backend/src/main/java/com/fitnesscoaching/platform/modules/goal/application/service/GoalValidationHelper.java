package com.fitnesscoaching.platform.modules.goal.application.service;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.exception.NewGoalJourneyRequiredException;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalObjectiveCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTargetCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalCatalogPort;
import com.fitnesscoaching.platform.modules.goal.domain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class GoalValidationHelper {

    private final GoalCatalogPort goalCatalogPort;

    public GoalValidationHelper(GoalCatalogPort goalCatalogPort) {
        this.goalCatalogPort = goalCatalogPort;
    }

    public record ResolvedTimeline(
            LocalDate startDate,
            LocalDate targetDate,
            Integer durationDays
    ) {}

    public ResolvedTimeline resolveAndValidateTimeline(
            LocalDate startDate,
            LocalDate targetDate,
            Integer durationDays
    ) {
        if (startDate == null) {
            throw new ApplicationValidationException(
                    "Start date is required",
                    List.of(new FieldErrorDto("startDate", "NotNull", "Start date is required"))
            );
        }

        if (targetDate != null && !targetDate.isAfter(startDate)) {
            throw new ApplicationValidationException(
                    "Target date must be strictly after start date",
                    List.of(new FieldErrorDto("targetDate", "InvalidRange", "Target date must be strictly after start date"))
            );
        }

        if (durationDays != null && durationDays <= 0) {
            throw new ApplicationValidationException(
                    "Duration days must be positive",
                    List.of(new FieldErrorDto("durationDays", "Positive", "Duration days must be positive"))
            );
        }

        if (targetDate != null && durationDays != null) {
            long days = ChronoUnit.DAYS.between(startDate, targetDate);
            if (days != durationDays) {
                throw new ApplicationValidationException(
                        "Duration days does not match target date",
                        List.of(new FieldErrorDto("durationDays", "Mismatch",
                                "Duration days does not match target date (expected " + days + " days)"))
                );
            }
        } else if (targetDate != null) {
            durationDays = (int) ChronoUnit.DAYS.between(startDate, targetDate);
        } else if (durationDays != null) {
            targetDate = startDate.plusDays(durationDays);
        }

        return new ResolvedTimeline(startDate, targetDate, durationDays);
    }

    public ResolvedTimeline resolveAndValidateProposalTimeline(
            LocalDate proposedStartDate,
            LocalDate proposedTargetDate,
            Integer proposedDurationDays,
            LocalDate baseStartDate,
            LocalDate baseTargetDate,
            Integer baseDurationDays
    ) {
        if (proposedDurationDays != null && proposedDurationDays <= 0) {
            throw new ApplicationValidationException(
                    "Proposed duration days must be positive",
                    List.of(new FieldErrorDto("proposedDurationDays", "Positive", "Proposed duration days must be positive"))
            );
        }

        LocalDate effectiveStart;
        LocalDate effectiveTarget;
        Integer effectiveDuration;

        boolean hasStart = proposedStartDate != null;
        boolean hasTarget = proposedTargetDate != null;
        boolean hasDuration = proposedDurationDays != null;

        if (hasStart && hasTarget) {
            effectiveStart = proposedStartDate;
            effectiveTarget = proposedTargetDate;
            if (hasDuration) {
                effectiveDuration = proposedDurationDays;
            } else {
                effectiveDuration = (int) ChronoUnit.DAYS.between(effectiveStart, effectiveTarget);
            }
        } else if (hasStart && hasDuration) {
            effectiveStart = proposedStartDate;
            effectiveDuration = proposedDurationDays;
            effectiveTarget = effectiveStart.plusDays(effectiveDuration);
        } else if (hasTarget && hasDuration) {
            effectiveTarget = proposedTargetDate;
            effectiveDuration = proposedDurationDays;
            effectiveStart = effectiveTarget.minusDays(effectiveDuration);
        } else if (hasStart) {
            effectiveStart = proposedStartDate;
            effectiveDuration = baseDurationDays != null ? baseDurationDays : (int) ChronoUnit.DAYS.between(baseStartDate, baseTargetDate);
            effectiveTarget = effectiveStart.plusDays(effectiveDuration);
        } else if (hasTarget) {
            effectiveStart = baseStartDate;
            effectiveTarget = proposedTargetDate;
            effectiveDuration = (int) ChronoUnit.DAYS.between(effectiveStart, effectiveTarget);
        } else if (hasDuration) {
            effectiveStart = baseStartDate;
            effectiveDuration = proposedDurationDays;
            effectiveTarget = effectiveStart.plusDays(effectiveDuration);
        } else {
            effectiveStart = baseStartDate;
            effectiveTarget = baseTargetDate;
            effectiveDuration = baseDurationDays != null ? baseDurationDays : (int) ChronoUnit.DAYS.between(baseStartDate, baseTargetDate);
        }

        if (effectiveStart == null) {
            throw new ApplicationValidationException(
                    "Resolved timeline start date cannot be null",
                    List.of(new FieldErrorDto("proposedStartDate", "NotNull", "Timeline start date cannot be null"))
            );
        }

        if (effectiveTarget == null || !effectiveTarget.isAfter(effectiveStart)) {
            throw new ApplicationValidationException(
                    "Proposed target date must be strictly after start date",
                    List.of(new FieldErrorDto("proposedTargetDate", "InvalidRange", "Proposed target date must be strictly after start date"))
            );
        }

        long actualDays = ChronoUnit.DAYS.between(effectiveStart, effectiveTarget);
        if (effectiveDuration == null || effectiveDuration <= 0 || actualDays != effectiveDuration) {
            throw new ApplicationValidationException(
                    "Proposed target date and duration days are inconsistent",
                    List.of(new FieldErrorDto("proposedDurationDays", "Mismatch",
                            "Proposed target date and duration days must be consistent (expected " + actualDays + " days)"))
            );
        }

        return new ResolvedTimeline(effectiveStart, effectiveTarget, effectiveDuration);
    }

    public List<GoalObjective> validateAndBuildObjectives(List<CreateGoalObjectiveCommand> objectives) {
        if (objectives == null || objectives.isEmpty()) {
            throw new ApplicationValidationException(
                    "Fitness goal must have at least one objective",
                    List.of(new FieldErrorDto("objectives", "NotEmpty", "Fitness goal must have at least one objective"))
            );
        }

        long primaryCount = objectives.stream()
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

        for (int i = 0; i < objectives.size(); i++) {
            CreateGoalObjectiveCommand objCmd = objectives.get(i);
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

        return domainObjectives;
    }

    public void validateTargetValues(
            String fieldPrefix,
            BigDecimal startValue,
            BigDecimal targetValue,
            BigDecimal targetMinValue,
            BigDecimal targetMaxValue,
            Integer targetRepetitions,
            LocalDate targetDate,
            LocalDate startDate
    ) {
        boolean hasTargetValue = targetValue != null || targetMinValue != null || targetMaxValue != null;
        if (!hasTargetValue) {
            throw new ApplicationValidationException(
                    "At least one target value must be specified",
                    List.of(new FieldErrorDto(fieldPrefix + ".targetValue", "NotNull", "At least one target value must be specified"))
            );
        }

        if (startValue != null && startValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationValidationException(
                    "Start value must be positive",
                    List.of(new FieldErrorDto(fieldPrefix + ".startValue", "Positive", "Start value must be positive"))
            );
        }
        if (targetValue != null && targetValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationValidationException(
                    "Target value must be positive",
                    List.of(new FieldErrorDto(fieldPrefix + ".targetValue", "Positive", "Target value must be positive"))
            );
        }
        if (targetMinValue != null && targetMinValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationValidationException(
                    "Target min value must be positive",
                    List.of(new FieldErrorDto(fieldPrefix + ".targetMinValue", "Positive", "Target min value must be positive"))
            );
        }
        if (targetMaxValue != null && targetMaxValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationValidationException(
                    "Target max value must be positive",
                    List.of(new FieldErrorDto(fieldPrefix + ".targetMaxValue", "Positive", "Target max value must be positive"))
            );
        }
        if (targetMinValue != null && targetMaxValue != null && targetMaxValue.compareTo(targetMinValue) < 0) {
            throw new ApplicationValidationException(
                    "Target max value cannot be less than min value",
                    List.of(new FieldErrorDto(fieldPrefix + ".targetMaxValue", "InvalidRange", "Target max value cannot be less than min value"))
            );
        }

        if (targetRepetitions != null && targetRepetitions <= 0) {
            throw new ApplicationValidationException(
                    "Target repetitions must be positive",
                    List.of(new FieldErrorDto(fieldPrefix + ".targetRepetitions", "Positive", "Target repetitions must be positive"))
            );
        }

        if (targetDate != null && startDate != null && targetDate.isBefore(startDate)) {
            throw new ApplicationValidationException(
                    "Target date cannot be before goal start date",
                    List.of(new FieldErrorDto(fieldPrefix + ".targetDate", "InvalidRange", "Target date cannot be before start date"))
            );
        }
    }

    public List<GoalTarget> validateAndBuildTargets(List<CreateGoalTargetCommand> targets, LocalDate startDate) {
        List<GoalTarget> domainTargets = new ArrayList<>();
        if (targets == null || targets.isEmpty()) {
            return domainTargets;
        }

        Set<Integer> seenMetricIds = new HashSet<>();

        for (int i = 0; i < targets.size(); i++) {
            CreateGoalTargetCommand tgtCmd = targets.get(i);
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

            validateTargetValues(
                    fieldPrefix,
                    tgtCmd.startValue(),
                    tgtCmd.targetValue(),
                    tgtCmd.targetMinValue(),
                    tgtCmd.targetMaxValue(),
                    tgtCmd.targetRepetitions(),
                    tgtCmd.targetDate(),
                    startDate
            );

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
                    null
            ));
        }

        return domainTargets;
    }

    public void validateSameJourneyIdentity(FitnessGoalVersion baseVersion, Short newPrimaryGoalTypeId) {
        if (baseVersion == null || newPrimaryGoalTypeId == null) {
            return;
        }
        Short currentPrimaryGoalTypeId = baseVersion.objectives().stream()
                .filter(o -> o.priority() == ObjectivePriority.PRIMARY)
                .map(GoalObjective::goalTypeId)
                .findFirst()
                .orElse(null);
        if (currentPrimaryGoalTypeId != null && !currentPrimaryGoalTypeId.equals(newPrimaryGoalTypeId)) {
            throw new NewGoalJourneyRequiredException(
                    "Primary goal type cannot be changed within the same journey; a new goal and goal transition is required"
            );
        }
    }

    public void validateProposalForAcceptance(GoalProposal proposal) {
        if (proposal == null) {
            throw new ApplicationValidationException("Proposal cannot be null", List.of());
        }

        // 1. Re-validate timeline snapshot
        LocalDate startDate = proposal.proposedStartDate();
        LocalDate targetDate = proposal.proposedTargetDate();
        Integer durationDays = proposal.proposedDurationDays();

        if (startDate == null || targetDate == null || durationDays == null) {
            throw new ApplicationValidationException(
                    "Proposal timeline is incomplete",
                    List.of(new FieldErrorDto("timeline", "Incomplete", "Proposal timeline must contain resolved startDate, targetDate, and durationDays"))
            );
        }
        if (durationDays <= 0) {
            throw new ApplicationValidationException(
                    "Proposal duration days must be positive",
                    List.of(new FieldErrorDto("proposedDurationDays", "Positive", "Proposed duration days must be positive"))
            );
        }
        if (!targetDate.isAfter(startDate)) {
            throw new ApplicationValidationException(
                    "Proposed target date must be strictly after start date",
                    List.of(new FieldErrorDto("proposedTargetDate", "InvalidRange", "Proposed target date must be strictly after start date"))
            );
        }
        long expectedDays = ChronoUnit.DAYS.between(startDate, targetDate);
        if (durationDays != expectedDays) {
            throw new ApplicationValidationException(
                    "Proposed target date and duration days are inconsistent",
                    List.of(new FieldErrorDto("proposedDurationDays", "Mismatch", "Duration does not match start and target date"))
            );
        }

        // 2. Re-validate objectives
        if (proposal.objectives() == null || proposal.objectives().isEmpty()) {
            throw new ApplicationValidationException(
                    "Fitness goal must have at least one objective",
                    List.of(new FieldErrorDto("objectives", "NotEmpty", "Fitness goal must have at least one objective"))
            );
        }

        long primaryCount = proposal.objectives().stream()
                .filter(o -> o.priority() == ObjectivePriority.PRIMARY)
                .count();
        if (primaryCount != 1) {
            throw new ApplicationValidationException(
                    "Fitness goal must have exactly one PRIMARY objective",
                    List.of(new FieldErrorDto("objectives", "InvalidPrimaryCount", "Fitness goal must have exactly one PRIMARY objective"))
            );
        }

        Set<Short> seenGoalTypeIds = new HashSet<>();
        Short proposalPrimaryGoalTypeId = null;

        for (int i = 0; i < proposal.objectives().size(); i++) {
            GoalProposalObjective obj = proposal.objectives().get(i);
            String fieldPrefix = "objectives[" + i + "]";

            if (obj.priority() == ObjectivePriority.PRIMARY) {
                proposalPrimaryGoalTypeId = obj.goalTypeId();
            }

            var goalTypeOpt = goalCatalogPort.findGoalTypeById(obj.goalTypeId());
            if (goalTypeOpt.isEmpty()) {
                throw new ApplicationValidationException(
                        "Goal type is invalid or does not exist",
                        List.of(new FieldErrorDto(fieldPrefix + ".goalTypeId", "NotFound", "Goal type does not exist"))
                );
            }
            var goalType = goalTypeOpt.get();
            if (!goalType.isActive()) {
                throw new ApplicationValidationException(
                        "Goal type is inactive",
                        List.of(new FieldErrorDto(fieldPrefix + ".goalTypeId", "Inactive", "Goal type is inactive"))
                );
            }
            if (!seenGoalTypeIds.add(obj.goalTypeId())) {
                throw new ApplicationValidationException(
                        "Duplicate goal type in objectives",
                        List.of(new FieldErrorDto(fieldPrefix + ".goalTypeId", "Duplicate", "Duplicate goal type specified"))
                );
            }
        }

        // 3. Re-validate targets
        if (proposal.targets() != null && !proposal.targets().isEmpty()) {
            Set<Integer> seenMetricIds = new HashSet<>();
            for (int i = 0; i < proposal.targets().size(); i++) {
                GoalProposalTarget tgt = proposal.targets().get(i);
                String fieldPrefix = "targets[" + i + "]";

                var metricOpt = goalCatalogPort.findMetricDefinitionById(tgt.metricDefinitionId());
                if (metricOpt.isEmpty()) {
                    throw new ApplicationValidationException(
                            "Metric definition is invalid or does not exist",
                            List.of(new FieldErrorDto(fieldPrefix + ".metricDefinitionId", "NotFound", "Metric definition does not exist"))
                    );
                }
                var metric = metricOpt.get();
                if (!metric.isActive()) {
                    throw new ApplicationValidationException(
                            "Metric definition is inactive",
                            List.of(new FieldErrorDto(fieldPrefix + ".metricDefinitionId", "Inactive", "Metric definition is inactive"))
                    );
                }
                if (!seenMetricIds.add(tgt.metricDefinitionId())) {
                    throw new ApplicationValidationException(
                            "Duplicate target metric definition specified for this goal version",
                            List.of(new FieldErrorDto(fieldPrefix + ".metricDefinitionId", "Duplicate", "Duplicate target metric"))
                    );
                }

                var unitOpt = goalCatalogPort.findMeasurementUnitById(tgt.unitId());
                if (unitOpt.isEmpty()) {
                    throw new ApplicationValidationException(
                            "Measurement unit is invalid or does not exist",
                            List.of(new FieldErrorDto(fieldPrefix + ".unitId", "NotFound", "Measurement unit does not exist"))
                    );
                }
                var unit = unitOpt.get();

                if (metric.defaultUnitDimension() != null && !metric.defaultUnitDimension().equalsIgnoreCase(unit.dimension())) {
                    throw new ApplicationValidationException(
                            "Measurement unit dimension is incompatible with metric dimension",
                            List.of(new FieldErrorDto(fieldPrefix + ".unitId", "DimensionMismatch", "Unit dimension does not match metric dimension"))
                    );
                }

                validateTargetValues(
                        fieldPrefix,
                        tgt.startValue(),
                        tgt.targetValue(),
                        tgt.targetMinValue(),
                        tgt.targetMaxValue(),
                        tgt.targetRepetitions(),
                        tgt.targetDate(),
                        startDate
                );
            }
        }
    }

    public boolean hasProposalVersionChanges(FitnessGoalVersion baseVersion, GoalProposal proposal) {
        if (baseVersion == null) {
            return true;
        }

        String resolvedTitle = (proposal.proposedTitle() != null && !proposal.proposedTitle().isBlank())
                ? proposal.proposedTitle().trim()
                : baseVersion.title();

        ResolvedTimeline resolvedTimeline = resolveAndValidateProposalTimeline(
                proposal.proposedStartDate(),
                proposal.proposedTargetDate(),
                proposal.proposedDurationDays(),
                baseVersion.startDate(),
                baseVersion.targetDate(),
                baseVersion.durationDays()
        );

        List<GoalObjective> domainObjectives = proposal.objectives() != null
                ? proposal.objectives().stream()
                        .map(o -> new GoalObjective(
                                o.id(), null, o.goalTypeId(), o.goalTypeCode(), o.goalTypeName(),
                                o.priority(), o.sortOrder(), o.notes()))
                        .toList()
                : List.of();

        List<GoalTarget> domainTargets = proposal.targets() != null
                ? proposal.targets().stream()
                        .map(t -> new GoalTarget(
                                t.id(), null, t.metricDefinitionId(), t.metricCode(), t.metricDisplayName(),
                                t.exerciseVariationId(), t.startValue(), t.targetValue(), t.targetMinValue(),
                                t.targetMaxValue(), t.unitId(), t.unitCode(), t.unitSymbol(),
                                t.targetRepetitions(), t.targetDate(), t.notes(), null))
                        .toList()
                : List.of();

        return hasVersionChanges(baseVersion, resolvedTitle, resolvedTimeline, domainObjectives, domainTargets);
    }

    public boolean hasVersionChanges(
            FitnessGoalVersion currentVersion,
            String newTitle,
            ResolvedTimeline newTimeline,
            List<GoalObjective> newObjectives,
            List<GoalTarget> newTargets
    ) {
        if (currentVersion == null) {
            return true;
        }

        // 1. Title
        String normNewTitle = normalizeString(newTitle);
        String normCurrTitle = normalizeString(currentVersion.title());
        if (!Objects.equals(normNewTitle, normCurrTitle)) {
            return true;
        }

        // 2. Timeline
        if (!Objects.equals(newTimeline.startDate(), currentVersion.startDate())
                || !Objects.equals(newTimeline.targetDate(), currentVersion.targetDate())
                || !Objects.equals(newTimeline.durationDays(), currentVersion.durationDays())) {
            return true;
        }

        // 3. Objectives
        List<GoalObjective> currObjs = currentVersion.objectives() != null
                ? currentVersion.objectives().stream().sorted(Comparator.comparing(GoalObjective::goalTypeId)).toList()
                : List.of();
        List<GoalObjective> newObjs = newObjectives != null
                ? newObjectives.stream().sorted(Comparator.comparing(GoalObjective::goalTypeId)).toList()
                : List.of();
        if (currObjs.size() != newObjs.size()) {
            return true;
        }
        for (int i = 0; i < currObjs.size(); i++) {
            GoalObjective c = currObjs.get(i);
            GoalObjective n = newObjs.get(i);
            if (!Objects.equals(c.goalTypeId(), n.goalTypeId())
                    || c.priority() != n.priority()
                    || !Objects.equals(c.sortOrder(), n.sortOrder())
                    || !Objects.equals(normalizeString(c.notes()), normalizeString(n.notes()))) {
                return true;
            }
        }

        // 4. Targets
        List<GoalTarget> currTgts = currentVersion.targets() != null
                ? currentVersion.targets().stream().sorted(Comparator.comparing(GoalTarget::metricDefinitionId)).toList()
                : List.of();
        List<GoalTarget> newTgts = newTargets != null
                ? newTargets.stream().sorted(Comparator.comparing(GoalTarget::metricDefinitionId)).toList()
                : List.of();
        if (currTgts.size() != newTgts.size()) {
            return true;
        }
        for (int i = 0; i < currTgts.size(); i++) {
            GoalTarget c = currTgts.get(i);
            GoalTarget n = newTgts.get(i);
            if (!Objects.equals(c.metricDefinitionId(), n.metricDefinitionId())
                    || !Objects.equals(c.exerciseVariationId(), n.exerciseVariationId())
                    || !areBigDecimalsEqual(c.startValue(), n.startValue())
                    || !areBigDecimalsEqual(c.targetValue(), n.targetValue())
                    || !areBigDecimalsEqual(c.targetMinValue(), n.targetMinValue())
                    || !areBigDecimalsEqual(c.targetMaxValue(), n.targetMaxValue())
                    || !Objects.equals(c.unitId(), n.unitId())
                    || !Objects.equals(c.targetRepetitions(), n.targetRepetitions())
                    || !Objects.equals(c.targetDate(), n.targetDate())
                    || !Objects.equals(normalizeString(c.notes()), normalizeString(n.notes()))) {
                return true;
            }
        }

        return false;
    }

    private static boolean areBigDecimalsEqual(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }

    private static String normalizeString(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
