package com.fitnesscoaching.platform.modules.progress.application.service;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementProgressQuery;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementUnitReferenceQuery;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementError;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementFailure;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.progress.domain.ProgressError;
import com.fitnesscoaching.platform.modules.progress.domain.ProgressFailure;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutCompletionEvidenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutProgressAggregationQuery;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ProgressEngineService implements ProgressEngineQuery {

    public static final String COMPUTATION_VERSION = "progress-engine-v1.0";
    public static final String SAFETY_FLAG_RETURN_100_DAYS = "RETURN_AFTER_100_DAYS_V1";

    private final WorkoutProgressAggregationQuery workoutQuery;
    private final MeasurementProgressQuery measurementQuery;
    private final CoachingAuthorityQuery coachingAuthority;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final MeasurementUnitReferenceQuery unitReferenceQuery;
    private final WorkoutCompletionEvidenceQuery workoutCompletionQuery;
    private final GetStudentProfileUseCase studentProfileUseCase;

    public ProgressEngineService(
            WorkoutProgressAggregationQuery workoutQuery,
            MeasurementProgressQuery measurementQuery,
            CoachingAuthorityQuery coachingAuthority,
            UserAccountStatusQuery userAccountStatusQuery,
            MeasurementUnitReferenceQuery unitReferenceQuery,
            WorkoutCompletionEvidenceQuery workoutCompletionQuery,
            GetStudentProfileUseCase studentProfileUseCase
    ) {
        this.workoutQuery = workoutQuery;
        this.measurementQuery = measurementQuery;
        this.coachingAuthority = coachingAuthority;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.unitReferenceQuery = unitReferenceQuery;
        this.workoutCompletionQuery = workoutCompletionQuery;
        this.studentProfileUseCase = studentProfileUseCase;
    }

    @Override
    @Transactional
    public ProgressSnapshot query(
            UUID actorId,
            UUID studentId,
            Set<String> metricCodes,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        // 1. Parameter Validation
        if (actorId == null || studentId == null || fromDate == null || toDate == null || timezone == null) {
            throw new ProgressFailure(ProgressError.INVALID_WINDOW_PARAMETERS, "Required parameters cannot be null");
        }
        if (fromDate.isAfter(toDate)) {
            throw new ProgressFailure(ProgressError.INVALID_WINDOW_PARAMETERS, "fromDate must be on or before toDate");
        }

        // 2. Validate Student & Actor Account Existence and Active Status via UserAccountStatusQuery port
        validateUserActive(studentId, true);
        validateUserActive(actorId, false);

        // 3. Determine single effective asOf
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();

        // 4. Fetch Workout Section
        WorkoutProgressSection workoutSection = fetchWorkoutSection(
                actorId, studentId, fromDate, toDate, timezone, effectiveAsOf
        );

        // 5. Fetch Measurement Section
        MeasurementProgressSection measurementSection = fetchMeasurementSection(
                actorId, studentId, metricCodes, fromDate, toDate, timezone, effectiveAsOf
        );

        // 6. Fail-fast if actor is denied on BOTH sources
        if (workoutSection.availability() == SourceAvailability.RESTRICTED
                && measurementSection.availability() == SourceAvailability.RESTRICTED) {
            throw new ProgressFailure(ProgressError.PROGRESS_ACCESS_DENIED,
                    "Actor lacks permission for both workout and measurement progress data");
        }

        // 7. Evaluate Training Continuity via WorkoutCompletionEvidenceQuery port
        ContinuitySignal continuity = evaluateContinuity(
                actorId, studentId, timezone, effectiveAsOf, workoutSection
        );

        // 8. Summarize Historical Reconstruction Limits
        HistoricalLimitsSummary historicalLimits = summarizeHistoricalLimits(workoutSection, measurementSection);

        // 9. Calculate Composite Watermark
        String compositeWatermark = calculateCompositeWatermark(
                studentId, fromDate, toDate, timezone, metricCodes, effectiveAsOf,
                workoutSection, measurementSection, continuity
        );

        return new ProgressSnapshot(
                studentId,
                actorId,
                fromDate,
                toDate,
                timezone,
                effectiveAsOf,
                workoutSection,
                measurementSection,
                continuity,
                historicalLimits,
                compositeWatermark
        );
    }

    @Override
    @Transactional
    public ProgressSnapshot query(
            UUID actorId,
            UUID studentId,
            String metricCode,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        return query(actorId, studentId, metricCode != null ? Set.of(metricCode) : null, fromDate, toDate, timezone, asOf);
    }

    @Override
    @Transactional
    public ProgressSnapshot query(
            UUID actorId,
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        return query(actorId, studentId, (Set<String>) null, fromDate, toDate, timezone, asOf);
    }

    private void validateUserActive(UUID userId, boolean isStudent) {
        Optional<AccountStatus> statusOpt = userAccountStatusQuery.getAccountStatus(userId);
        if (statusOpt.isEmpty()) {
            if (isStudent) {
                throw new ProgressFailure(ProgressError.STUDENT_NOT_FOUND, "Student not found: " + userId);
            } else {
                throw new ProgressFailure(ProgressError.PROGRESS_ACCESS_DENIED, "Actor account not found: " + userId);
            }
        }
        if (statusOpt.get() != AccountStatus.ACTIVE) {
            throw new ProgressFailure(ProgressError.PROGRESS_ACCESS_DENIED,
                    (isStudent ? "Student" : "Actor") + " account is not active: " + userId);
        }
    }

    private WorkoutProgressSection fetchWorkoutSection(
            UUID actorId,
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        try {
            var snapshot = workoutQuery.aggregate(actorId, studentId, fromDate, toDate, timezone, asOf);
            return WorkoutProgressSection.available(snapshot);
        } catch (WorkoutExecutionFailure wf) {
            if (wf.error() == WorkoutExecutionError.WORKOUT_EXECUTION_ACCESS_DENIED) {
                return WorkoutProgressSection.restricted("WORKOUT_ACCESS_DENIED");
            }
            throw wf;
        }
    }

    private MeasurementProgressSection fetchMeasurementSection(
            UUID actorId,
            UUID studentId,
            Set<String> metricCodes,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        try {
            var snapshot = measurementQuery.query(actorId, studentId, metricCodes, fromDate, toDate, timezone, asOf);
            List<MetricTrendDelta> deltas = calculateMetricDeltas(snapshot.observations());
            return MeasurementProgressSection.available(snapshot, deltas);
        } catch (MeasurementFailure mf) {
            if (mf.error() == MeasurementError.MEASUREMENT_NOT_FOUND) {
                throw new ProgressFailure(ProgressError.STUDENT_NOT_FOUND, "Student not found: " + studentId, mf);
            }
            if (mf.error() == MeasurementError.MEASUREMENT_ACCESS_DENIED) {
                return MeasurementProgressSection.restricted("MEASUREMENT_ACCESS_DENIED");
            }
            throw mf;
        }
    }

    private List<MetricTrendDelta> calculateMetricDeltas(
            List<MeasurementProgressQuery.MeasurementObservationFact> observations
    ) {
        if (observations == null || observations.isEmpty()) {
            return List.of();
        }

        Map<String, List<MeasurementProgressQuery.MeasurementObservationFact>> byMetric = new LinkedHashMap<>();
        for (var obs : observations) {
            if (obs.metricCode() != null) {
                byMetric.computeIfAbsent(obs.metricCode(), k -> new ArrayList<>()).add(obs);
            }
        }

        // Pre-fetch unit conversions for all unitCodes present via MeasurementUnitReferenceQuery port
        Set<String> unitCodes = new HashSet<>();
        for (var obs : observations) {
            if (obs.unitCode() != null) {
                unitCodes.add(obs.unitCode());
            }
        }
        Map<String, MeasurementUnitReferenceQuery.UnitDetails> conversions = unitReferenceQuery.resolveByCodes(unitCodes);

        List<MetricTrendDelta> deltas = new ArrayList<>();
        for (var entry : byMetric.entrySet()) {
            String metricCode = entry.getKey();
            List<MeasurementProgressQuery.MeasurementObservationFact> list = entry.getValue();

            // Filter observations that have valid numeric value and sort chronologically
            List<MeasurementProgressQuery.MeasurementObservationFact> numericList = list.stream()
                    .filter(o -> o.numericValue() != null)
                    .sorted(Comparator.comparing(MeasurementProgressQuery.MeasurementObservationFact::measuredAt)
                            .thenComparing(MeasurementProgressQuery.MeasurementObservationFact::createdAt)
                            .thenComparing(MeasurementProgressQuery.MeasurementObservationFact::id))
                    .toList();

            var firstSample = list.get(0);
            String displayName = firstSample.metricDisplayName();
            String unitCode = firstSample.unitCode();

            if (numericList.size() < 2) {
                BigDecimal singleVal = numericList.size() == 1 ? numericList.get(0).numericValue() : null;
                Instant singleTime = numericList.size() == 1 ? numericList.get(0).measuredAt() : null;
                deltas.add(new MetricTrendDelta(
                        metricCode,
                        displayName,
                        unitCode,
                        singleVal,
                        singleTime,
                        singleVal,
                        singleTime,
                        null,
                        null,
                        TrendDirection.INSUFFICIENT_DATA,
                        numericList.size()
                ));
                continue;
            }

            var baseline = numericList.get(0);
            var latest = numericList.get(numericList.size() - 1);

            // Observations sharing the exact same measuredAt timestamp have no elapsed time -> INSUFFICIENT_DATA
            if (baseline.measuredAt().equals(latest.measuredAt())) {
                deltas.add(new MetricTrendDelta(
                        metricCode,
                        displayName,
                        baseline.unitCode(),
                        baseline.numericValue(),
                        baseline.measuredAt(),
                        latest.numericValue(),
                        latest.measuredAt(),
                        null,
                        null,
                        TrendDirection.INSUFFICIENT_DATA,
                        numericList.size()
                ));
                continue;
            }

            BigDecimal baselineVal = baseline.numericValue();
            BigDecimal latestVal = latest.numericValue();
            String baselineUnit = baseline.unitCode();
            String latestUnit = latest.unitCode();

            BigDecimal deltaVal;
            BigDecimal percentageChange;
            TrendDirection direction;

            if (Objects.equals(baselineUnit, latestUnit)) {
                deltaVal = latestVal.subtract(baselineVal);
                if (baselineVal.compareTo(BigDecimal.ZERO) == 0) {
                    percentageChange = null;
                } else {
                    percentageChange = deltaVal
                            .divide(baselineVal.abs(), 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .setScale(2, RoundingMode.HALF_UP);
                }
                int comp = deltaVal.compareTo(BigDecimal.ZERO);
                direction = comp > 0 ? TrendDirection.INCREASED : comp < 0 ? TrendDirection.DECREASED : TrendDirection.STABLE;
            } else {
                // Different units: check if convertible via catalog
                MeasurementUnitReferenceQuery.UnitDetails convBase = baselineUnit != null ? conversions.get(baselineUnit.toUpperCase()) : null;
                MeasurementUnitReferenceQuery.UnitDetails convLatest = latestUnit != null ? conversions.get(latestUnit.toUpperCase()) : null;

                if (convBase != null && convLatest != null
                        && Objects.equals(convBase.dimension(), convLatest.dimension())
                        && convBase.multiplierToBase() != null && convLatest.multiplierToBase() != null
                        && convBase.multiplierToBase().compareTo(BigDecimal.ZERO) > 0) {

                    BigDecimal valBaseInBase = baselineVal.multiply(convBase.multiplierToBase())
                            .add(convBase.offsetToBase() != null ? convBase.offsetToBase() : BigDecimal.ZERO);
                    BigDecimal valLatestInBase = latestVal.multiply(convLatest.multiplierToBase())
                            .add(convLatest.offsetToBase() != null ? convLatest.offsetToBase() : BigDecimal.ZERO);

                    BigDecimal deltaInBase = valLatestInBase.subtract(valBaseInBase);
                    deltaVal = deltaInBase.divide(convBase.multiplierToBase(), 4, RoundingMode.HALF_UP);

                    boolean hasOffset = (convBase.offsetToBase() != null && convBase.offsetToBase().compareTo(BigDecimal.ZERO) != 0)
                            || (convLatest.offsetToBase() != null && convLatest.offsetToBase().compareTo(BigDecimal.ZERO) != 0);

                    if (hasOffset) {
                        // When non-zero offset is involved, percentage change on affine scale is mathematically undefined
                        percentageChange = null;
                    } else if (baselineVal.compareTo(BigDecimal.ZERO) == 0) {
                        percentageChange = null;
                    } else {
                        percentageChange = deltaVal
                                .divide(baselineVal.abs(), 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(2, RoundingMode.HALF_UP);
                    }
                    int comp = deltaVal.compareTo(BigDecimal.ZERO);
                    direction = comp > 0 ? TrendDirection.INCREASED : comp < 0 ? TrendDirection.DECREASED : TrendDirection.STABLE;
                } else {
                    // Units incompatible or unconvertible
                    deltaVal = null;
                    percentageChange = null;
                    direction = TrendDirection.INSUFFICIENT_DATA;
                }
            }

            deltas.add(new MetricTrendDelta(
                    metricCode,
                    displayName,
                    baselineUnit,
                    baselineVal,
                    baseline.measuredAt(),
                    latestVal,
                    latest.measuredAt(),
                    deltaVal,
                    percentageChange,
                    direction,
                    numericList.size()
            ));
        }

        return deltas;
    }

    private ContinuitySignal evaluateContinuity(
            UUID actorId,
            UUID studentId,
            ZoneId timezone,
            Instant asOf,
            WorkoutProgressSection workoutSection
    ) {
        // 1. If workout section is restricted by permissions, return RESTRICTED_HISTORY
        if (workoutSection.availability() == SourceAvailability.RESTRICTED) {
            return new ContinuitySignal(
                    ContinuityState.RESTRICTED_HISTORY,
                    null,
                    null,
                    null,
                    List.of(),
                    "Workout history is restricted by actor coaching permissions"
            );
        }

        String adherenceReason = workoutSection.adherence() != null ? workoutSection.adherence().availabilityReason() : null;
        if (adherenceReason != null && adherenceReason.contains("RESTRICTED")) {
            return new ContinuitySignal(
                    ContinuityState.RESTRICTED_HISTORY,
                    null,
                    null,
                    null,
                    List.of(),
                    "Workout history is partially restricted by coaching time scope"
            );
        }

        // 2. Evaluate actor's permitted workout history scope
        boolean isSelf = actorId.equals(studentId);
        Instant historyFrom = null;
        Instant historyUntil = null;

        if (!isSelf) {
            AuthorityDecision decision = coachingAuthority.evaluateCurrent(new AuthorityRequest(
                    actorId, studentId, DataScope.WORKOUT_HISTORY, DataAccessLevel.VIEW));
            if (!decision.allowed()) {
                return new ContinuitySignal(
                        ContinuityState.RESTRICTED_HISTORY,
                        null,
                        null,
                        null,
                        List.of(),
                        "Actor lacks WORKOUT_HISTORY permission"
                );
            }
            historyFrom = decision.historyFrom();
            historyUntil = decision.historyUntil();

            // If historyUntil is before asOf, we cannot verify whether there was a newer workout after historyUntil
            if (historyUntil != null && historyUntil.isBefore(asOf)) {
                return new ContinuitySignal(
                        ContinuityState.RESTRICTED_HISTORY,
                        null,
                        null,
                        null,
                        List.of(),
                        "Permitted workout history scope ends before asOf (" + historyUntil + " < " + asOf + "); cannot verify training continuity up to asOf"
                );
            }
        }

        Instant effectiveUntil = (historyUntil != null && historyUntil.isBefore(asOf)) ? historyUntil : asOf;

        // 3. Query completed workouts via WorkoutCompletionEvidenceQuery port
        List<WorkoutCompletionEvidenceQuery.Completion> allCompletions = workoutCompletionQuery.completedForStudent(studentId);
        final Instant hFrom = historyFrom;
        List<WorkoutCompletionEvidenceQuery.Completion> permittedCompletions = (allCompletions != null ? allCompletions : List.<WorkoutCompletionEvidenceQuery.Completion>of())
                .stream()
                .filter(c -> {
                    Instant performed = c.performedEndAt() != null ? c.performedEndAt() : c.performedStartAt();
                    if (performed == null || performed.isAfter(effectiveUntil)) {
                        return false;
                    }
                    return hFrom == null || !performed.isBefore(hFrom);
                })
                .sorted((a, b) -> {
                    Instant tA = a.performedEndAt() != null ? a.performedEndAt() : a.performedStartAt();
                    Instant tB = b.performedEndAt() != null ? b.performedEndAt() : b.performedStartAt();
                    int c = tB.compareTo(tA);
                    return c != 0 ? c : b.executionId().compareTo(a.executionId());
                })
                .toList();

        if (!permittedCompletions.isEmpty()) {
            var latestCompletion = permittedCompletions.get(0);
            UUID lastExecutionId = latestCompletion.executionId();
            Instant lastPerformedAt = latestCompletion.performedEndAt() != null
                    ? latestCompletion.performedEndAt()
                    : latestCompletion.performedStartAt();
            LocalDate lastWorkoutDate = lastPerformedAt.atZone(timezone).toLocalDate();
            LocalDate asOfLocalDate = asOf.atZone(timezone).toLocalDate();

            long days = ChronoUnit.DAYS.between(lastWorkoutDate, asOfLocalDate);
            int daysSince = (int) Math.max(0, days);

            if (daysSince >= 100) {
                return new ContinuitySignal(
                        ContinuityState.PROLONGED_INACTIVITY,
                        daysSince,
                        lastWorkoutDate,
                        lastExecutionId,
                        List.of(SAFETY_FLAG_RETURN_100_DAYS),
                        "Prolonged inactivity detected (>= 100 days between last recorded workout in system and asOf); return-to-training adaptation required"
                );
            } else {
                // Record observed workout facts without unapproved specialist 'consistent' classification
                return new ContinuitySignal(
                        ContinuityState.RECENT_ACTIVITY_OBSERVED,
                        daysSince,
                        lastWorkoutDate,
                        lastExecutionId,
                        List.of(),
                        "Recent completed workout recorded in system on " + lastWorkoutDate + " (" + daysSince + " days before asOf); consistency unclassified without approved specialist threshold"
                );
            }
        }

        // 4. No completed workout found in permitted history
        // Check if historyFrom is after asOf - 100 days (insufficient history window to confirm 100-day inactivity)
        if (historyFrom != null && historyFrom.isAfter(asOf.minus(100, ChronoUnit.DAYS))) {
            return new ContinuitySignal(
                    ContinuityState.RESTRICTED_HISTORY,
                    null,
                    null,
                    null,
                    List.of(),
                    "Permitted workout history coverage does not span 100 days prior to asOf; cannot evaluate prolonged inactivity"
            );
        }

        // Check student onboarding profile via GetStudentProfileUseCase port
        try {
            StudentProfile profile = studentProfileUseCase.getStudentProfile(studentId);
            if (profile != null) {
                boolean isZeroMonths = profile.trainingExperienceMonths() != null
                        && profile.trainingExperienceMonths().compareTo(BigDecimal.ZERO) == 0;
                boolean isBeginner = profile.trainingExperienceLevel() == TrainingExperienceLevel.BEGINNER;
                // Historical asOf invariant: onboarding must have been completed on or before asOf
                boolean onboardingDoneBeforeAsOf = profile.onboardingCompletedAt() != null
                        && !profile.onboardingCompletedAt().isAfter(asOf);

                if (isBeginner && isZeroMonths && onboardingDoneBeforeAsOf) {
                    return new ContinuitySignal(
                            ContinuityState.CONFIRMED_NEW,
                            null,
                            null,
                            null,
                            List.of(),
                            "Confirmed beginner onboarding (level=BEGINNER, experience=0 months, completed before asOf) with zero recorded workouts"
                    );
                }
            }
        } catch (RuntimeException ignored) {
            // Student profile not found or inactive, treat as unconfirmed past training
        }

        return new ContinuitySignal(
                ContinuityState.UNKNOWN_HISTORY,
                null,
                null,
                null,
                List.of(),
                "Zero workout logs recorded in system but student lacks explicit confirmed beginner evidence as of reference time; unrecorded past training cannot be ruled out"
        );
    }

    private HistoricalLimitsSummary summarizeHistoricalLimits(
            WorkoutProgressSection workoutSection,
            MeasurementProgressSection measurementSection
    ) {
        boolean workoutLimits = false;
        if (workoutSection.actualSessions() != null) {
            workoutLimits = workoutSection.actualSessions().stream()
                    .anyMatch(s -> "HISTORICAL_RECONSTRUCTION_LIMITED".equals(s.historicalReconstructionStatus()));
        }

        boolean measurementLimits = false;
        if (measurementSection.summary() != null) {
            measurementLimits = measurementSection.summary().hasHistoricalReconstructionLimits();
        }

        boolean hasLimits = workoutLimits || measurementLimits;
        List<String> reasons = new ArrayList<>();
        if (workoutLimits) {
            reasons.add("Workout data modified or superseded after asOf");
        }
        if (measurementLimits) {
            reasons.add("Measurement data backfilled, superseded, or erased after asOf");
        }

        String explanation = hasLimits ? String.join("; ", reasons) : "Historical snapshot is exact";

        return new HistoricalLimitsSummary(
                hasLimits,
                workoutLimits,
                measurementLimits,
                explanation
        );
    }

    private String calculateCompositeWatermark(
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Set<String> metricCodes,
            Instant asOf,
            WorkoutProgressSection workoutSection,
            MeasurementProgressSection measurementSection,
            ContinuitySignal continuity
    ) {
        String workoutDigest;
        if (workoutSection.availability() == SourceAvailability.RESTRICTED) {
            workoutDigest = "RESTRICTED";
        } else if (workoutSection.sourceWatermark() != null) {
            workoutDigest = workoutSection.sourceWatermark();
        } else {
            workoutDigest = "NONE";
        }

        String measurementDigest;
        if (measurementSection.availability() == SourceAvailability.RESTRICTED) {
            measurementDigest = "RESTRICTED";
        } else if (measurementSection.sourceWatermark() != null) {
            measurementDigest = measurementSection.sourceWatermark();
        } else {
            measurementDigest = "NONE";
        }

        String metricsTag = "ALL";
        if (metricCodes != null && !metricCodes.isEmpty()) {
            List<String> sorted = new ArrayList<>(metricCodes);
            Collections.sort(sorted);
            metricsTag = String.join(",", sorted);
        }

        String continuityTag = continuity.state() + ":"
                + (continuity.daysSinceLastWorkout() != null ? continuity.daysSinceLastWorkout() : "null") + ":"
                + (continuity.lastWorkoutDate() != null ? continuity.lastWorkoutDate() : "null");

        // Canonical deterministic representation preserving full nanosecond asOf precision
        String raw = "ENGINE:" + COMPUTATION_VERSION + "|" +
                "STUDENT:" + studentId + "|" +
                "WINDOW:" + fromDate + ":" + toDate + ":" + timezone.getId() + "|" +
                "METRICS:" + metricsTag + "|" +
                "ASOF:" + asOf.toString() + "|" +
                "WORKOUT:" + workoutDigest + "|" +
                "MEAS:" + measurementDigest + "|" +
                "CONTINUITY:" + continuityTag;

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }
}
