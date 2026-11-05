package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutProgressAggregationQuery;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
public class WorkoutProgressAggregationService implements WorkoutProgressAggregationQuery {

    private static final BigDecimal LB_TO_KG_MULTIPLIER = new BigDecimal("0.45359237");

    private final NamedParameterJdbcTemplate jdbc;
    private final WorkoutExecutionAccessPolicy accessPolicy;
    private final CoachingAuthorityQuery coachingAuthority;

    public WorkoutProgressAggregationService(
            NamedParameterJdbcTemplate jdbc,
            WorkoutExecutionAccessPolicy accessPolicy,
            CoachingAuthorityQuery coachingAuthority
    ) {
        this.jdbc = jdbc;
        this.accessPolicy = accessPolicy;
        this.coachingAuthority = coachingAuthority;
    }

    @Override
    public WorkoutProgressSnapshot aggregate(
            UUID actorId,
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        if (actorId == null || studentId == null || fromDate == null || toDate == null
                || timezone == null || asOf == null || fromDate.isAfter(toDate)) {
            throw new WorkoutExecutionFailure(WorkoutExecutionError.VALIDATION_FAILED,
                    "Invalid workout progress aggregation parameters");
        }

        Instant windowStartInstant = fromDate.atStartOfDay(timezone).toInstant();
        Instant windowEndInstant = toDate.plusDays(1).atStartOfDay(timezone).toInstant();

        // 1. Separate authorization evaluation for Planned Workouts and Actual Workouts
        boolean isSelf = actorId.equals(studentId);
        boolean canReadPlans;
        Instant planHistoryFrom = null;
        Instant planHistoryUntil = null;
        boolean canReadActuals;
        Instant actualHistoryFrom = null;
        Instant actualHistoryUntil = null;

        if (isSelf) {
            accessPolicy.requireStudentMutation(actorId, studentId);
            canReadPlans = true;
            canReadActuals = true;
        } else {
            // Evaluate trainer permission for Planned Workouts (WORKOUT_PLAN_HISTORY or WORKOUT_PLAN)
            AuthorityDecision planDecision = coachingAuthority.evaluateCurrent(new AuthorityRequest(
                    actorId, studentId, DataScope.WORKOUT_PLAN_HISTORY, DataAccessLevel.VIEW));
            if (!planDecision.allowed()) {
                planDecision = coachingAuthority.evaluateCurrent(new AuthorityRequest(
                        actorId, studentId, DataScope.WORKOUT_PLAN, DataAccessLevel.VIEW));
            }
            if (planDecision.allowed()) {
                canReadPlans = true;
                planHistoryFrom = planDecision.historyFrom();
                planHistoryUntil = planDecision.historyUntil();
            } else {
                canReadPlans = false;
            }

            // Evaluate trainer permission for Actual Workouts (WORKOUT_HISTORY)
            AuthorityDecision actualDecision = coachingAuthority.evaluateCurrent(new AuthorityRequest(
                    actorId, studentId, DataScope.WORKOUT_HISTORY, DataAccessLevel.VIEW));
            if (actualDecision.allowed()) {
                canReadActuals = true;
                actualHistoryFrom = actualDecision.historyFrom();
                actualHistoryUntil = actualDecision.historyUntil();
            } else {
                canReadActuals = false;
            }

            if (!canReadPlans && !canReadActuals) {
                throw new WorkoutExecutionFailure(WorkoutExecutionError.WORKOUT_EXECUTION_ACCESS_DENIED,
                        "Access denied to workout progress data for student " + studentId);
            }
        }

        // 2. Fetch planned workouts within authorized plan scope and date window
        List<PlannedWorkoutRow> plannedRows = List.of();
        if (canReadPlans) {
            Instant effectivePlanStart = (planHistoryFrom != null && planHistoryFrom.isAfter(windowStartInstant))
                    ? planHistoryFrom : windowStartInstant;
            Instant effectivePlanEnd = (planHistoryUntil != null && planHistoryUntil.isBefore(windowEndInstant))
                    ? planHistoryUntil : windowEndInstant;

            if (!effectivePlanStart.isAfter(effectivePlanEnd) && !effectivePlanStart.equals(effectivePlanEnd)) {
                plannedRows = fetchPlannedWorkouts(studentId, effectivePlanStart, effectivePlanEnd);
            }
        }
        List<UUID> plannedIds = plannedRows.stream().map(PlannedWorkoutRow::id).toList();

        // 3. Fetch actual workout session logs started on or before asOf and within authorized history scope
        List<ActualSessionRow> rawActualRows = List.of();
        if (canReadActuals) {
            Instant effectiveActualStart = (actualHistoryFrom != null && actualHistoryFrom.isAfter(windowStartInstant))
                    ? actualHistoryFrom : windowStartInstant;
            Instant effectiveActualEnd = (actualHistoryUntil != null && actualHistoryUntil.isBefore(windowEndInstant))
                    ? actualHistoryUntil : windowEndInstant;

            // Actual workouts must have started on or before asOf
            Instant actualWindowUpper = effectiveActualEnd.isAfter(asOf) ? asOf : effectiveActualEnd;

            if (!effectiveActualStart.isAfter(actualWindowUpper) && !effectiveActualStart.equals(actualWindowUpper)) {
                // If isSelf, student can view actual sessions linked to planned workouts even outside window bounds,
                // but for non-self (Trainer), actual sessions are strictly bounded to [effectiveActualStart, actualWindowUpper)
                rawActualRows = fetchActualSessions(studentId, effectiveActualStart, actualWindowUpper, isSelf ? plannedIds : List.of(), asOf);
            } else if (isSelf && !plannedIds.isEmpty()) {
                rawActualRows = fetchLinkedSessionsOnly(studentId, plannedIds, asOf);
            }
        }

        // Filter actual sessions by trainer history bounds and asOf; completely avoid leaking out-of-scope executions
        List<ActualSessionRow> actualRows = new ArrayList<>();
        for (var row : rawActualRows) {
            if (isSelf) {
                actualRows.add(row);
            } else if (canReadActuals) {
                boolean inScope = (actualHistoryFrom == null || !row.performedStartAt().isBefore(actualHistoryFrom))
                        && (actualHistoryUntil == null || !row.performedStartAt().isAfter(actualHistoryUntil))
                        && !row.performedStartAt().isAfter(asOf);
                if (inScope) {
                    actualRows.add(row);
                }
            }
        }

        List<UUID> actualIds = actualRows.stream().map(ActualSessionRow::id).toList();

        // 4. Fetch set logs for all fetched actual sessions created on or before asOf
        Map<UUID, List<SetLogRow>> setsBySession = actualIds.isEmpty() ? Map.of() : fetchSetLogs(actualIds, asOf);

        // Resolve unit references for unit conversions
        Set<Short> unitIds = new HashSet<>();
        setsBySession.values().forEach(sets -> sets.forEach(s -> {
            if (s.loadUnitId() != null) unitIds.add(s.loadUnitId());
        }));
        Map<Short, UnitConversionInfo> unitConversions = unitIds.isEmpty() ? Map.of() : resolveUnitConversions(unitIds);

        // 5. Fetch adjustments for planned workouts
        List<AdjustmentRow> adjustmentRows = plannedIds.isEmpty() ? List.of() : fetchAdjustments(plannedIds);

        // 6. Map actual sessions into facts, reconstruct status as of asOf, & calculate window metrics
        List<ActualSessionFact> actualFacts = new ArrayList<>();

        BigDecimal totalWindowVolume = BigDecimal.ZERO;
        int totalWindowSets = 0;
        int totalWorkingSets = 0;
        int totalWarmupSets = 0;
        int totalBodyweightSets = 0;
        int totalSetsWithMissingLoad = 0;
        int totalSetsWithZeroLoad = 0;
        int totalSetsWithMissingReps = 0;
        int totalSetsWithUnknownUnit = 0;
        int totalSetsWithCalculableVolume = 0;
        int totalWindowReps = 0;
        int totalWindowSetDuration = 0;
        boolean hasSetDurations = false;
        int totalWindowSessionElapsed = 0;
        boolean hasSessionElapsed = false;
        List<BigDecimal> sessionRpes = new ArrayList<>();
        List<BigDecimal> setRpes = new ArrayList<>();

        // Map from session ID to its effective historic view as of asOf
        Map<UUID, EffectiveActualSession> effectiveActualMap = new LinkedHashMap<>();
        boolean windowHasHistoricalLimits = false;

        for (var actual : actualRows) {
            LocalDate actualLocalDate = actual.performedStartAt().atZone(timezone).toLocalDate();
            boolean isInsideWindow = !actual.performedStartAt().isBefore(windowStartInstant)
                    && actual.performedStartAt().isBefore(windowEndInstant);

            // Reconstruct status as of asOf: if session ended after asOf, it was still IN_PROGRESS at asOf
            boolean isOngoingAtAsOf = actual.performedEndAt() != null && actual.performedEndAt().isAfter(asOf);
            String effectiveStatus = isOngoingAtAsOf ? "IN_PROGRESS" : actual.status();
            Instant effectiveEndAt = isOngoingAtAsOf ? null : actual.performedEndAt();
            // If workout was still IN_PROGRESS at asOf or was modified after asOf, overall_rpe is not trustworthy as of asOf
            BigDecimal effectiveOverallRpe = (isOngoingAtAsOf || actual.updatedAt().isAfter(asOf))
                    ? null : actual.overallRpe();

            var sets = setsBySession.getOrDefault(actual.id(), List.of());
            BigDecimal sessionVolume = BigDecimal.ZERO;
            int sessionSetsCompleted = 0;
            int sessionWorkingSets = 0;
            int sessionWarmupSets = 0;
            int sessionBodyweightSets = 0;
            int sessionMissingLoad = 0;
            int sessionZeroLoad = 0;
            int sessionMissingReps = 0;
            int sessionUnknownUnit = 0;
            int sessionCalculableVolume = 0;
            int sessionReps = 0;
            int sessionSetDuration = 0;
            boolean sessionHasSetDuration = false;
            boolean sessionHasSetsModifiedAfterAsOf = false;

            for (var set : sets) {
                boolean completedBeforeAsOf = set.completedAt() == null || !set.completedAt().isAfter(asOf);

                boolean isCompleted = "COMPLETED".equalsIgnoreCase(set.completionStatus()) && completedBeforeAsOf;
                boolean setModifiedAfterAsOf = set.updatedAt().isAfter(asOf);
                if (setModifiedAfterAsOf) {
                    sessionHasSetsModifiedAfterAsOf = true;
                }

                if (isCompleted) {
                    sessionSetsCompleted++;
                    if ("WARMUP".equalsIgnoreCase(set.setType())) {
                        sessionWarmupSets++;
                    } else {
                        sessionWorkingSets++;

                        if (setModifiedAfterAsOf) {
                            // The set was modified after asOf: its load, reps, duration, and RPE at asOf
                            // cannot be verified from the current row. Exclude unverifiable metrics
                            // from historic calculations and report as missing/unverifiable so coverage rate
                            // accurately reflects incomplete volume coverage.
                            sessionMissingLoad++;
                            sessionMissingReps++;
                        } else {
                            // 1. Repetitions missingness
                            if (set.repetitions() == null) {
                                sessionMissingReps++;
                            }

                            // 2. Load missingness vs Bodyweight vs Zero load
                            UnitConversionInfo unitInfo = set.loadUnitId() != null
                                    ? unitConversions.get(set.loadUnitId()) : null;

                            if (set.loadValue() == null) {
                                if (set.isBodyweight()) {
                                    sessionBodyweightSets++;
                                } else {
                                    sessionMissingLoad++;
                                }
                            } else {
                                if (set.loadValue().compareTo(BigDecimal.ZERO) == 0) {
                                    sessionZeroLoad++;
                                }
                                if (unitInfo == null || unitInfo.multiplierToKg() == null) {
                                    sessionUnknownUnit++;
                                }
                            }

                            // 3. Calculable volume requires non-null repetitions, non-null load, and convertible unit
                            boolean isCalculable = set.repetitions() != null
                                    && set.loadValue() != null
                                    && unitInfo != null
                                    && unitInfo.multiplierToKg() != null;

                            if (isCalculable) {
                                sessionCalculableVolume++;
                                BigDecimal weightKg = set.loadValue().multiply(unitInfo.multiplierToKg());
                                BigDecimal setVol = weightKg.multiply(BigDecimal.valueOf(set.repetitions()));
                                sessionVolume = sessionVolume.add(setVol);
                            }
                        }
                    }

                    if (!setModifiedAfterAsOf) {
                        if (set.repetitions() != null) {
                            sessionReps += set.repetitions();
                        }
                        if (set.durationSeconds() != null) {
                            sessionSetDuration += set.durationSeconds();
                            sessionHasSetDuration = true;
                        }
                        if (isInsideWindow && set.rpe() != null) {
                            setRpes.add(set.rpe());
                        }
                    }
                }
            }

            boolean sessionHasHistoricalLimits = isOngoingAtAsOf
                    || actual.updatedAt().isAfter(asOf)
                    || sessionHasSetsModifiedAfterAsOf;

            String reconstructionStatus = sessionHasHistoricalLimits
                    ? "HISTORICAL_RECONSTRUCTION_LIMITED"
                    : "HISTORICAL_SNAPSHOT_EXACT";

            Integer sessionElapsedSeconds = null;
            if (effectiveEndAt != null) {
                sessionElapsedSeconds = (int) Duration.between(actual.performedStartAt(), effectiveEndAt).getSeconds();
            }

            Integer finalSetDuration = sessionHasSetDuration ? sessionSetDuration : null;
            BigDecimal finalSessionVolume = sessionCalculableVolume > 0
                    ? sessionVolume.setScale(2, RoundingMode.HALF_UP) : null;

            EffectiveActualSession effActual = new EffectiveActualSession(
                    actual.id(),
                    actual.studentId(),
                    actual.plannedWorkoutId(),
                    actual.coachingPeriodId(),
                    actual.performedStartAt(),
                    actualLocalDate,
                    effectiveEndAt,
                    effectiveStatus,
                    effectiveOverallRpe,
                    finalSessionVolume,
                    sessionSetsCompleted,
                    sessionWorkingSets,
                    sessionBodyweightSets,
                    sessionReps,
                    finalSetDuration,
                    sessionElapsedSeconds,
                    actual.plannedWorkoutId() == null,
                    reconstructionStatus,
                    actual.createdAt(),
                    actual.updatedAt()
            );
            effectiveActualMap.put(actual.id(), effActual);

            // Cohort uniformity: Window metrics ONLY accumulate sets/volume from sessions INSIDE the window
            if (isInsideWindow) {
                if (sessionHasHistoricalLimits) {
                    windowHasHistoricalLimits = true;
                }
                actualFacts.add(new ActualSessionFact(
                        effActual.id(),
                        effActual.plannedWorkoutId(),
                        effActual.coachingPeriodId(),
                        effActual.performedStartAt(),
                        effActual.performedLocalDate(),
                        effActual.performedEndAt(),
                        effActual.status(),
                        effActual.overallRpe(),
                        effActual.volumeKg(),
                        effActual.setsCompleted(),
                        effActual.workingSets(),
                        effActual.bodyweightSets(),
                        effActual.repsCompleted(),
                        effActual.setDurationSeconds(),
                        effActual.sessionElapsedSeconds(),
                        effActual.isUnplanned(),
                        effActual.reconstructionStatus()
                ));

                totalWindowVolume = totalWindowVolume.add(sessionVolume);
                totalWindowSets += sessionSetsCompleted;
                totalWorkingSets += sessionWorkingSets;
                totalWarmupSets += sessionWarmupSets;
                totalBodyweightSets += sessionBodyweightSets;
                totalSetsWithMissingLoad += sessionMissingLoad;
                totalSetsWithZeroLoad += sessionZeroLoad;
                totalSetsWithMissingReps += sessionMissingReps;
                totalSetsWithUnknownUnit += sessionUnknownUnit;
                totalSetsWithCalculableVolume += sessionCalculableVolume;
                totalWindowReps += sessionReps;
                if (sessionHasSetDuration) {
                    totalWindowSetDuration += sessionSetDuration;
                    hasSetDurations = true;
                }
                if (sessionElapsedSeconds != null) {
                    totalWindowSessionElapsed += sessionElapsedSeconds;
                    hasSessionElapsed = true;
                }
                if (effectiveOverallRpe != null) {
                    sessionRpes.add(effectiveOverallRpe);
                }
            }
        }

        // 7. Group authorized effective actual sessions by plannedWorkoutId
        Map<UUID, List<EffectiveActualSession>> actualsByPlannedId = new HashMap<>();
        for (var actual : effectiveActualMap.values()) {
            if (actual.plannedWorkoutId() != null) {
                actualsByPlannedId.computeIfAbsent(actual.plannedWorkoutId(), k -> new ArrayList<>()).add(actual);
            }
        }

        // 8. Analyze planned occurrences & compute completion vs adherence counts
        LocalDate asOfLocalDate = asOf.atZone(timezone).toLocalDate();

        int scheduledDueCount = 0;
        int completedDueCount = 0;
        int completedOnTimeCount = 0;
        int completedOffScheduleCount = 0;
        int missedCount = 0;
        int todayPendingCount = 0;
        int inProgressCount = 0;
        int partiallyCompletedCount = 0;
        int abortedCount = 0;
        int rescheduledCount = 0;
        int cancelledCount = 0;
        int upcomingCount = 0;
        int unverifiableDueCount = 0;

        List<PlannedOccurrenceFact> plannedFacts = new ArrayList<>();

        for (var planned : plannedRows) {
            LocalDate plannedLocalDate = planned.plannedStartAt().atZone(timezone).toLocalDate();
            LocalDate origPlannedLocalDate = planned.originalPlannedStartAt().atZone(timezone).toLocalDate();

            boolean canVerifyActual = isSelf || canVerifyActualForOccurrence(
                    planned.plannedStartAt(), canReadActuals, actualHistoryFrom, actualHistoryUntil);

            List<EffectiveActualSession> linkedActuals = canVerifyActual
                    ? actualsByPlannedId.getOrDefault(planned.id(), List.of())
                    : List.of();
            EffectiveActualSession primaryActual = pickPrimaryEffectiveActual(linkedActuals);

            String evidenceStatus;
            UUID linkedExecutionId = null;
            String linkedExecutionStatus = null;
            Instant linkedPerformedStartAt = null;
            LocalDate linkedPerformedLocalDate = null;
            Integer scheduleDeviationDays = null;

            if (!canVerifyActual) {
                evidenceStatus = "RESTRICTED_OR_UNKNOWN";
                // Do not leak existence or details of restricted execution
            } else {
                if (plannedLocalDate.isAfter(asOfLocalDate)) {
                    evidenceStatus = "UPCOMING";
                } else {
                    evidenceStatus = "VERIFIED";
                }
                if (primaryActual != null) {
                    linkedExecutionId = primaryActual.id();
                    linkedExecutionStatus = primaryActual.status();
                    linkedPerformedStartAt = primaryActual.performedStartAt();
                    linkedPerformedLocalDate = primaryActual.performedLocalDate();
                    scheduleDeviationDays = (int) ChronoUnit.DAYS.between(plannedLocalDate, linkedPerformedLocalDate);
                }
            }

            plannedFacts.add(new PlannedOccurrenceFact(
                    planned.id(),
                    planned.workoutPlanSessionId(),
                    planned.coachingPeriodId(),
                    planned.plannedStartAt(),
                    plannedLocalDate,
                    planned.originalPlannedStartAt(),
                    origPlannedLocalDate,
                    planned.status(),
                    planned.rescheduledFromId(),
                    planned.scheduleChangeId(),
                    planned.version(),
                    linkedExecutionId,
                    linkedExecutionStatus,
                    linkedPerformedStartAt,
                    linkedPerformedLocalDate,
                    scheduleDeviationDays,
                    evidenceStatus
            ));

            String status = planned.status();
            if ("CANCELLED".equalsIgnoreCase(status)) {
                cancelledCount++;
            } else if ("RESCHEDULED".equalsIgnoreCase(status)) {
                rescheduledCount++;
            } else if (plannedLocalDate.isAfter(asOfLocalDate)) {
                upcomingCount++;
            } else if (!canVerifyActual) {
                // Occurrence is due (past or today), but actual history cannot be verified:
                // NEVER infer missed; record as unverifiable due count!
                scheduledDueCount++;
                unverifiableDueCount++;
            } else {
                // Occurrence is due and actual history is verifiable
                boolean isCompleted = (primaryActual != null && "COMPLETED".equalsIgnoreCase(primaryActual.status()));
                boolean isPartial = primaryActual != null && "PARTIALLY_COMPLETED".equalsIgnoreCase(primaryActual.status());
                boolean isAborted = primaryActual != null && "ABORTED".equalsIgnoreCase(primaryActual.status());
                boolean isInProgress = (primaryActual != null && "IN_PROGRESS".equalsIgnoreCase(primaryActual.status()))
                        || "IN_PROGRESS".equalsIgnoreCase(status);

                if (plannedLocalDate.isBefore(asOfLocalDate)) {
                    // Past day
                    if (isInProgress) {
                        // Overnight workout or ongoing session from yesterday: NOT missed!
                        inProgressCount++;
                    } else if (isCompleted) {
                        scheduledDueCount++;
                        completedDueCount++;
                        if (scheduleDeviationDays != null && scheduleDeviationDays == 0) {
                            completedOnTimeCount++;
                        } else {
                            completedOffScheduleCount++;
                        }
                    } else if (isPartial) {
                        scheduledDueCount++;
                        partiallyCompletedCount++;
                    } else if (isAborted) {
                        scheduledDueCount++;
                        abortedCount++;
                    } else {
                        // Concluded past day with verified absence of execution => MISSED
                        scheduledDueCount++;
                        missedCount++;
                    }
                } else {
                    // Today (plannedLocalDate == asOfLocalDate)
                    if (isCompleted) {
                        scheduledDueCount++;
                        completedDueCount++;
                        if (scheduleDeviationDays != null && scheduleDeviationDays == 0) {
                            completedOnTimeCount++;
                        } else {
                            completedOffScheduleCount++;
                        }
                    } else if (isPartial) {
                        scheduledDueCount++;
                        partiallyCompletedCount++;
                    } else if (isAborted) {
                        scheduledDueCount++;
                        abortedCount++;
                    } else if (isInProgress) {
                        inProgressCount++;
                    } else {
                        // Not started yet today => pending today, NOT missed!
                        todayPendingCount++;
                    }
                }
            }
        }

        // Count unplanned sessions
        int unplannedCompletedCount = 0;
        for (var actual : actualFacts) {
            if (actual.isUnplanned()) {
                if ("COMPLETED".equalsIgnoreCase(actual.status())) {
                    unplannedCompletedCount++;
                } else if ("IN_PROGRESS".equalsIgnoreCase(actual.status())) {
                    inProgressCount++;
                }
            }
        }

        // Distinct calculation for Completion Rate and On-Time Adherence Rate
        BigDecimal rawCompletionRate = null;
        BigDecimal onTimeAdherenceRate = null;
        String availabilityReason = null;

        if (scheduledDueCount == 0) {
            availabilityReason = "NO_SCHEDULED_WORKOUTS_DUE";
        } else if (unverifiableDueCount > 0) {
            // Incomplete/restricted actual evidence: do not publish misleading completion rates
            rawCompletionRate = null;
            onTimeAdherenceRate = null;
            availabilityReason = (unverifiableDueCount == scheduledDueCount)
                    ? "ACTUAL_HISTORY_RESTRICTED"
                    : "ACTUAL_HISTORY_PARTIALLY_RESTRICTED";
        } else {
            rawCompletionRate = BigDecimal.valueOf(completedDueCount)
                    .divide(BigDecimal.valueOf(scheduledDueCount), 4, RoundingMode.HALF_UP);
            onTimeAdherenceRate = BigDecimal.valueOf(completedOnTimeCount)
                    .divide(BigDecimal.valueOf(scheduledDueCount), 4, RoundingMode.HALF_UP);
        }

        AdherenceSummary adherenceSummary = new AdherenceSummary(
                scheduledDueCount,
                completedDueCount,
                completedOnTimeCount,
                completedOffScheduleCount,
                missedCount,
                todayPendingCount,
                inProgressCount,
                partiallyCompletedCount,
                abortedCount,
                rescheduledCount,
                cancelledCount,
                upcomingCount,
                unplannedCompletedCount,
                unverifiableDueCount,
                rawCompletionRate,
                onTimeAdherenceRate,
                availabilityReason
        );

        BigDecimal avgSessionRpe = null;
        if (!sessionRpes.isEmpty()) {
            BigDecimal sum = sessionRpes.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            avgSessionRpe = sum.divide(BigDecimal.valueOf(sessionRpes.size()), 2, RoundingMode.HALF_UP);
        }

        BigDecimal avgSetRpe = null;
        if (!setRpes.isEmpty()) {
            BigDecimal sum = setRpes.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            avgSetRpe = sum.divide(BigDecimal.valueOf(setRpes.size()), 2, RoundingMode.HALF_UP);
        }

        BigDecimal knownVolumeKg = totalSetsWithCalculableVolume > 0
                ? totalWindowVolume.setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal loadCoverageRate = totalWorkingSets > 0
                ? BigDecimal.valueOf(totalSetsWithCalculableVolume).divide(BigDecimal.valueOf(totalWorkingSets), 4, RoundingMode.HALF_UP)
                : null;
        boolean hasIncompleteVolumeCoverage = totalSetsWithCalculableVolume < totalWorkingSets;

        VolumeAndIntensitySummary volumeSummary = new VolumeAndIntensitySummary(
                knownVolumeKg,
                totalWindowSets,
                totalWorkingSets,
                totalWarmupSets,
                totalBodyweightSets,
                totalSetsWithMissingLoad,
                totalSetsWithZeroLoad,
                totalSetsWithMissingReps,
                totalSetsWithUnknownUnit,
                totalSetsWithCalculableVolume,
                loadCoverageRate,
                hasIncompleteVolumeCoverage,
                totalWindowReps,
                hasSetDurations ? totalWindowSetDuration : null,
                hasSessionElapsed ? totalWindowSessionElapsed : null,
                avgSessionRpe,
                avgSetRpe,
                windowHasHistoricalLimits
        );

        // 9. Calculate deterministic composite watermark from authorized snapshot
        String sourceWatermark = calculateSourceWatermark(
                fromDate,
                toDate,
                timezone,
                canReadPlans,
                canReadActuals,
                planHistoryFrom,
                planHistoryUntil,
                actualHistoryFrom,
                actualHistoryUntil,
                plannedRows,
                effectiveActualMap.values().stream().toList(),
                setsBySession,
                unitConversions,
                adjustmentRows
        );

        return new WorkoutProgressSnapshot(
                studentId,
                fromDate,
                toDate,
                timezone,
                asOf,
                adherenceSummary,
                volumeSummary,
                plannedFacts,
                actualFacts,
                sourceWatermark
        );
    }

    private static boolean canVerifyActualForOccurrence(
            Instant plannedStartAt,
            boolean canReadActuals,
            Instant actualHistoryFrom,
            Instant actualHistoryUntil
    ) {
        if (!canReadActuals) return false;
        if (actualHistoryFrom != null && plannedStartAt.isBefore(actualHistoryFrom)) return false;
        if (actualHistoryUntil != null && plannedStartAt.isAfter(actualHistoryUntil)) return false;
        return true;
    }

    private static EffectiveActualSession pickPrimaryEffectiveActual(List<EffectiveActualSession> list) {
        if (list == null || list.isEmpty()) return null;
        return list.stream()
                .min((a, b) -> {
                    boolean aComp = "COMPLETED".equalsIgnoreCase(a.status());
                    boolean bComp = "COMPLETED".equalsIgnoreCase(b.status());
                    if (aComp && !bComp) return -1;
                    if (!aComp && bComp) return 1;
                    return b.performedStartAt().compareTo(a.performedStartAt());
                })
                .orElse(null);
    }

    private List<PlannedWorkoutRow> fetchPlannedWorkouts(UUID studentId, Instant from, Instant to) {
        String sql = """
                SELECT id, student_id, workout_plan_session_id, coaching_period_id,
                       planned_start_at, planned_end_at, original_planned_start_at,
                       status, rescheduled_from_id, schedule_change_id, version,
                       created_at, updated_at
                FROM fitness.planned_workouts
                WHERE student_id = :studentId
                  AND planned_start_at >= :fromInstant
                  AND planned_start_at < :toInstant
                ORDER BY planned_start_at, id
                """;
        var params = new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("fromInstant", Timestamp.from(from))
                .addValue("toInstant", Timestamp.from(to));

        return jdbc.query(sql, params, (rs, rowNum) -> new PlannedWorkoutRow(
                rs.getObject("id", UUID.class),
                rs.getObject("student_id", UUID.class),
                rs.getObject("workout_plan_session_id", UUID.class),
                rs.getObject("coaching_period_id", UUID.class),
                rs.getTimestamp("planned_start_at").toInstant(),
                rs.getTimestamp("planned_end_at") != null ? rs.getTimestamp("planned_end_at").toInstant() : null,
                rs.getTimestamp("original_planned_start_at").toInstant(),
                rs.getString("status"),
                rs.getObject("rescheduled_from_id", UUID.class),
                rs.getObject("schedule_change_id", UUID.class),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ));
    }

    private List<ActualSessionRow> fetchActualSessions(
            UUID studentId,
            Instant from,
            Instant to,
            List<UUID> plannedIds,
            Instant asOf
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, student_id, planned_workout_id, workout_plan_version_id, coaching_period_id,
                       performed_start_at, performed_end_at, status, overall_rpe,
                       perceived_recovery, calories_burned_estimate, notes,
                       created_at, updated_at
                FROM fitness.workout_session_logs
                WHERE student_id = :studentId
                  AND performed_start_at <= :asOfInstant
                  AND ((performed_start_at >= :fromInstant AND performed_start_at < :toInstant)
                """);

        var params = new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("asOfInstant", Timestamp.from(asOf))
                .addValue("fromInstant", Timestamp.from(from))
                .addValue("toInstant", Timestamp.from(to));

        if (!plannedIds.isEmpty()) {
            sql.append(" OR planned_workout_id IN (:plannedIds)");
            params.addValue("plannedIds", plannedIds);
        }
        sql.append(") ORDER BY performed_start_at, id");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new ActualSessionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("student_id", UUID.class),
                rs.getObject("planned_workout_id", UUID.class),
                rs.getObject("workout_plan_version_id", UUID.class),
                rs.getObject("coaching_period_id", UUID.class),
                rs.getTimestamp("performed_start_at").toInstant(),
                rs.getTimestamp("performed_end_at") != null ? rs.getTimestamp("performed_end_at").toInstant() : null,
                rs.getString("status"),
                rs.getBigDecimal("overall_rpe"),
                rs.getBigDecimal("perceived_recovery"),
                rs.getBigDecimal("calories_burned_estimate"),
                rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ));
    }

    private List<ActualSessionRow> fetchLinkedSessionsOnly(UUID studentId, List<UUID> plannedIds, Instant asOf) {
        String sql = """
                SELECT id, student_id, planned_workout_id, workout_plan_version_id, coaching_period_id,
                       performed_start_at, performed_end_at, status, overall_rpe,
                       perceived_recovery, calories_burned_estimate, notes,
                       created_at, updated_at
                FROM fitness.workout_session_logs
                WHERE student_id = :studentId
                  AND performed_start_at <= :asOfInstant
                  AND planned_workout_id IN (:plannedIds)
                ORDER BY performed_start_at, id
                """;
        var params = new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("asOfInstant", Timestamp.from(asOf))
                .addValue("plannedIds", plannedIds);

        return jdbc.query(sql, params, (rs, rowNum) -> new ActualSessionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("student_id", UUID.class),
                rs.getObject("planned_workout_id", UUID.class),
                rs.getObject("workout_plan_version_id", UUID.class),
                rs.getObject("coaching_period_id", UUID.class),
                rs.getTimestamp("performed_start_at").toInstant(),
                rs.getTimestamp("performed_end_at") != null ? rs.getTimestamp("performed_end_at").toInstant() : null,
                rs.getString("status"),
                rs.getBigDecimal("overall_rpe"),
                rs.getBigDecimal("perceived_recovery"),
                rs.getBigDecimal("calories_burned_estimate"),
                rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ));
    }

    private Map<UUID, List<SetLogRow>> fetchSetLogs(List<UUID> sessionIds, Instant asOf) {
        String sql = """
                SELECT el.workout_session_log_id,
                       el.exercise_variation_id,
                       sl.id, sl.exercise_log_id, sl.set_number, sl.set_type,
                       sl.completion_status, sl.repetitions, sl.load_value, sl.load_unit_id,
                       sl.rpe, sl.duration_seconds, sl.completed_at, sl.created_at, sl.updated_at,
                       EXISTS (
                           SELECT 1 FROM fitness.exercise_equipment ee
                           JOIN fitness.equipment eq ON eq.id = ee.equipment_id
                           WHERE ee.exercise_variation_id = el.exercise_variation_id
                             AND eq.code = 'BODYWEIGHT'
                       ) AS is_bodyweight
                FROM fitness.exercise_logs el
                JOIN fitness.set_logs sl ON sl.exercise_log_id = el.id
                WHERE el.workout_session_log_id IN (:sessionIds)
                  AND sl.created_at <= :asOfInstant
                ORDER BY el.workout_session_log_id, el.sequence_number, sl.set_number
                """;
        var params = new MapSqlParameterSource()
                .addValue("sessionIds", sessionIds)
                .addValue("asOfInstant", Timestamp.from(asOf));
        Map<UUID, List<SetLogRow>> result = new LinkedHashMap<>();

        jdbc.query(sql, params, rs -> {
            UUID sessionId = rs.getObject("workout_session_log_id", UUID.class);
            Short loadUnitId = rs.getObject("load_unit_id") != null ? rs.getShort("load_unit_id") : null;
            Instant completedAt = rs.getTimestamp("completed_at") != null
                    ? rs.getTimestamp("completed_at").toInstant() : null;
            SetLogRow set = new SetLogRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("exercise_log_id", UUID.class),
                    rs.getInt("set_number"),
                    rs.getString("set_type"),
                    rs.getString("completion_status"),
                    rs.getObject("repetitions") != null ? rs.getInt("repetitions") : null,
                    rs.getBigDecimal("load_value"),
                    loadUnitId,
                    rs.getBigDecimal("rpe"),
                    rs.getObject("duration_seconds") != null ? rs.getInt("duration_seconds") : null,
                    completedAt,
                    rs.getBoolean("is_bodyweight"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );
            result.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(set);
        });

        return result;
    }

    private Map<Short, UnitConversionInfo> resolveUnitConversions(Set<Short> unitIds) {
        String sql = """
                SELECT id, code, symbol, dimension, base_unit_code, multiplier_to_base
                FROM fitness.measurement_units
                WHERE id IN (:unitIds)
                """;
        var params = new MapSqlParameterSource("unitIds", unitIds);
        Map<Short, UnitConversionInfo> result = new HashMap<>();

        jdbc.query(sql, params, rs -> {
            short id = rs.getShort("id");
            String code = rs.getString("code");
            String symbol = rs.getString("symbol");
            String dimension = rs.getString("dimension");
            String baseUnitCode = rs.getString("base_unit_code");
            BigDecimal multiplierToBase = rs.getBigDecimal("multiplier_to_base");

            BigDecimal multiplierToKg = null;
            if ("KG".equalsIgnoreCase(code) || "KILOGRAM".equalsIgnoreCase(code) || "kg".equalsIgnoreCase(symbol)) {
                multiplierToKg = BigDecimal.ONE;
            } else if ("LB".equalsIgnoreCase(code) || "POUND".equalsIgnoreCase(code) || "lb".equalsIgnoreCase(symbol)) {
                multiplierToKg = multiplierToBase != null ? multiplierToBase : LB_TO_KG_MULTIPLIER;
            } else if (multiplierToBase != null && "MASS".equalsIgnoreCase(dimension) && "KG".equalsIgnoreCase(baseUnitCode)) {
                multiplierToKg = multiplierToBase;
            }

            result.put(id, new UnitConversionInfo(id, code, symbol, dimension, multiplierToBase, multiplierToKg));
        });

        return result;
    }

    private List<AdjustmentRow> fetchAdjustments(List<UUID> plannedIds) {
        String sql = """
                SELECT id, planned_workout_id, adjustment_type, created_at
                FROM fitness.workout_session_adjustments
                WHERE planned_workout_id IN (:plannedIds)
                ORDER BY planned_workout_id, created_at, id
                """;
        var params = new MapSqlParameterSource("plannedIds", plannedIds);
        return jdbc.query(sql, params, (rs, rowNum) -> new AdjustmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("planned_workout_id", UUID.class),
                rs.getString("adjustment_type"),
                rs.getTimestamp("created_at").toInstant()
        ));
    }

    private String calculateSourceWatermark(
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            boolean canReadPlans,
            boolean canReadActuals,
            Instant planHistoryFrom,
            Instant planHistoryUntil,
            Instant actualHistoryFrom,
            Instant actualHistoryUntil,
            List<PlannedWorkoutRow> plannedWorkouts,
            List<EffectiveActualSession> actualSessions,
            Map<UUID, List<SetLogRow>> setsBySession,
            Map<Short, UnitConversionInfo> unitConversions,
            List<AdjustmentRow> adjustments
    ) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append("WINDOW:").append(fromDate).append(":").append(toDate).append(":").append(timezone.getId()).append("|");
            sb.append("AUTH:").append(canReadPlans).append(":").append(canReadActuals).append(":")
              .append(planHistoryFrom != null ? planHistoryFrom.toEpochMilli() : "unbounded").append(":")
              .append(planHistoryUntil != null ? planHistoryUntil.toEpochMilli() : "unbounded").append(":")
              .append(actualHistoryFrom != null ? actualHistoryFrom.toEpochMilli() : "unbounded").append(":")
              .append(actualHistoryUntil != null ? actualHistoryUntil.toEpochMilli() : "unbounded").append("|");

            // 1. Unit conversion catalog dependencies (sorted by unit ID for determinism)
            var sortedUnits = new ArrayList<>(unitConversions.values());
            sortedUnits.sort(Comparator.comparingInt(u -> (int) u.id()));
            sb.append("UNITS:").append(sortedUnits.size()).append(";");
            for (var u : sortedUnits) {
                sb.append(u.id()).append(",")
                  .append(u.code()).append(",")
                  .append(u.symbol() != null ? u.symbol() : "null").append(",")
                  .append(u.dimension() != null ? u.dimension() : "null").append(",")
                  .append(u.multiplierToBase() != null ? u.multiplierToBase().stripTrailingZeros().toPlainString() : "null").append(",")
                  .append(u.multiplierToKg() != null ? u.multiplierToKg().stripTrailingZeros().toPlainString() : "null").append(";");
            }
            sb.append("|");

            // 2. Planned Workouts in window (sorted by ID for deterministic order)
            var sortedPlanned = new ArrayList<>(plannedWorkouts);
            sortedPlanned.sort(Comparator.comparing(PlannedWorkoutRow::id));
            sb.append("PLANNED:").append(sortedPlanned.size()).append(";");
            for (var p : sortedPlanned) {
                sb.append(p.id()).append(",")
                  .append(p.version()).append(",")
                  .append(p.status()).append(",")
                  .append(p.plannedStartAt().toEpochMilli()).append(",")
                  .append(p.updatedAt().toEpochMilli()).append(";");
            }
            sb.append("|");

            // 3. Actual Workout Session Logs (sorted by ID)
            var sortedActual = new ArrayList<>(actualSessions);
            sortedActual.sort(Comparator.comparing(EffectiveActualSession::id));
            sb.append("ACTUAL:").append(sortedActual.size()).append(";");
            for (var a : sortedActual) {
                var sets = setsBySession.getOrDefault(a.id(), List.of());
                String setDigest = computeSetsDigest(sets, unitConversions);
                sb.append(a.id()).append(",")
                  .append(a.performedStartAt().toEpochMilli()).append(",")
                  .append(a.performedEndAt() != null ? a.performedEndAt().toEpochMilli() : "ongoing").append(",")
                  .append(a.status()).append(",")
                  .append(a.updatedAt().toEpochMilli()).append(",")
                  .append(setDigest).append(";");
            }
            sb.append("|");

            // 4. Adjustments (sorted by ID)
            var sortedAdj = new ArrayList<>(adjustments);
            sortedAdj.sort(Comparator.comparing(AdjustmentRow::id));
            sb.append("ADJ:").append(sortedAdj.size()).append(";");
            for (var adj : sortedAdj) {
                sb.append(adj.id()).append(",")
                  .append(adj.adjustmentType()).append(",")
                  .append(adj.createdAt().toEpochMilli()).append(";");
            }

            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String computeSetsDigest(List<SetLogRow> sets, Map<Short, UnitConversionInfo> unitConversions) {
        if (sets.isEmpty()) return "empty";
        var sorted = new ArrayList<>(sets);
        sorted.sort(Comparator.comparingInt(SetLogRow::setNumber).thenComparing(SetLogRow::id));
        StringBuilder sb = new StringBuilder();
        for (var s : sorted) {
            String unitCode = "none";
            String unitMultiplier = "none";
            if (s.loadUnitId() != null) {
                var u = unitConversions.get(s.loadUnitId());
                unitCode = u != null ? u.code() : String.valueOf(s.loadUnitId());
                unitMultiplier = (u != null && u.multiplierToKg() != null)
                        ? u.multiplierToKg().stripTrailingZeros().toPlainString() : "unresolved";
            }
            sb.append(s.id()).append(",")
              .append(s.setNumber()).append(",")
              .append(s.setType()).append(",")
              .append(s.completionStatus()).append(",")
              .append(s.repetitions() != null ? s.repetitions() : "null").append(",")
              .append(s.loadValue() != null ? s.loadValue().stripTrailingZeros().toPlainString() : "null").append(",")
              .append(unitCode).append(":").append(unitMultiplier).append(",")
              .append(s.isBodyweight() ? "bw:true" : "bw:false").append(",")
              .append(s.rpe() != null ? s.rpe().stripTrailingZeros().toPlainString() : "null").append(",")
              .append(s.durationSeconds() != null ? s.durationSeconds() : "null").append(",")
              .append(s.completedAt() != null ? s.completedAt().toEpochMilli() : "none").append(",")
              .append(s.updatedAt().toEpochMilli()).append(";");
        }
        return sb.toString();
    }

    // Internal helper records
    private record PlannedWorkoutRow(
            UUID id, UUID studentId, UUID workoutPlanSessionId, UUID coachingPeriodId,
            Instant plannedStartAt, Instant plannedEndAt, Instant originalPlannedStartAt,
            String status, UUID rescheduledFromId, UUID scheduleChangeId, long version,
            Instant createdAt, Instant updatedAt
    ) {}

    private record ActualSessionRow(
            UUID id, UUID studentId, UUID plannedWorkoutId, UUID workoutPlanVersionId, UUID coachingPeriodId,
            Instant performedStartAt, Instant performedEndAt, String status, BigDecimal overallRpe,
            BigDecimal perceivedRecovery, BigDecimal caloriesBurnedEstimate, String notes,
            Instant createdAt, Instant updatedAt
    ) {}

    private record EffectiveActualSession(
            UUID id, UUID studentId, UUID plannedWorkoutId, UUID coachingPeriodId,
            Instant performedStartAt, LocalDate performedLocalDate, Instant performedEndAt,
            String status, BigDecimal overallRpe, BigDecimal volumeKg,
            int setsCompleted, int workingSets, int bodyweightSets, int repsCompleted,
            Integer setDurationSeconds, Integer sessionElapsedSeconds, boolean isUnplanned,
            String reconstructionStatus, Instant createdAt, Instant updatedAt
    ) {}

    private record SetLogRow(
            UUID id, UUID exerciseLogId, int setNumber, String setType,
            String completionStatus, Integer repetitions, BigDecimal loadValue,
            Short loadUnitId, BigDecimal rpe, Integer durationSeconds,
            Instant completedAt, boolean isBodyweight, Instant createdAt, Instant updatedAt
    ) {}

    private record UnitConversionInfo(
            short id, String code, String symbol, String dimension, BigDecimal multiplierToBase, BigDecimal multiplierToKg
    ) {}

    private record AdjustmentRow(
            UUID id, UUID plannedWorkoutId, String adjustmentType, Instant createdAt
    ) {}
}
