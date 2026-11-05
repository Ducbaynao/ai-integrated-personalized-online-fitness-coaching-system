package com.fitnesscoaching.platform.modules.progress;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementProgressQuery;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementUnitReferenceQuery;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementError;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementFailure;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.ContinuityState;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.SourceAvailability;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.TrendDirection;
import com.fitnesscoaching.platform.modules.progress.application.service.ProgressEngineService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProgressEngineUnitTest {

    private WorkoutProgressAggregationQuery workoutQuery;
    private MeasurementProgressQuery measurementQuery;
    private CoachingAuthorityQuery coachingAuthority;
    private UserAccountStatusQuery userAccountStatusQuery;
    private MeasurementUnitReferenceQuery unitReferenceQuery;
    private WorkoutCompletionEvidenceQuery workoutCompletionQuery;
    private GetStudentProfileUseCase studentProfileUseCase;
    private ProgressEngineService service;

    private final UUID studentId = UUID.randomUUID();
    private final UUID actorId = studentId;
    private final LocalDate fromDate = LocalDate.parse("2026-11-01");
    private final LocalDate toDate = LocalDate.parse("2026-11-07");
    private final ZoneId timezone = ZoneId.of("Asia/Ho_Chi_Minh");
    private final Instant asOfFixed = Instant.parse("2026-11-06T12:00:00Z");

    @BeforeEach
    void setUp() {
        workoutQuery = mock(WorkoutProgressAggregationQuery.class);
        measurementQuery = mock(MeasurementProgressQuery.class);
        coachingAuthority = mock(CoachingAuthorityQuery.class);
        userAccountStatusQuery = mock(UserAccountStatusQuery.class);
        unitReferenceQuery = mock(MeasurementUnitReferenceQuery.class);
        workoutCompletionQuery = mock(WorkoutCompletionEvidenceQuery.class);
        studentProfileUseCase = mock(GetStudentProfileUseCase.class);

        service = new ProgressEngineService(
                workoutQuery,
                measurementQuery,
                coachingAuthority,
                userAccountStatusQuery,
                unitReferenceQuery,
                workoutCompletionQuery,
                studentProfileUseCase
        );

        // Mock users active check by default
        when(userAccountStatusQuery.getAccountStatus(any())).thenReturn(Optional.of(AccountStatus.ACTIVE));
    }

    @Test
    @DisplayName("Input validation: rejects null dates or fromDate after toDate")
    void inputValidation_rejectsInvalidWindow() {
        assertThatThrownBy(() -> service.query(actorId, studentId, null, toDate, timezone, asOfFixed))
                .isInstanceOf(ProgressFailure.class)
                .satisfies(e -> assertThat(((ProgressFailure) e).error()).isEqualTo(ProgressError.INVALID_WINDOW_PARAMETERS));

        assertThatThrownBy(() -> service.query(actorId, studentId, toDate, fromDate, timezone, asOfFixed))
                .isInstanceOf(ProgressFailure.class)
                .satisfies(e -> assertThat(((ProgressFailure) e).error()).isEqualTo(ProgressError.INVALID_WINDOW_PARAMETERS));
    }

    @Test
    @DisplayName("Propagates identical effectiveAsOf to both sources when caller does not provide asOf")
    void passesIdenticalEffectiveAsOf_whenCallerAsOfIsNull() {
        stubEmptyWorkout(asOfFixed);
        stubEmptyMeasurement(asOfFixed);
        when(workoutCompletionQuery.completedForStudent(studentId)).thenReturn(List.of());
        when(studentProfileUseCase.getStudentProfile(studentId)).thenReturn(null);

        ProgressEngineQuery.ProgressSnapshot snapshot = service.query(actorId, studentId, fromDate, toDate, timezone, null);

        assertThat(snapshot.effectiveAsOf()).isNotNull();
        Instant capturedAsOf = snapshot.effectiveAsOf();

        ArgumentCaptor<Instant> workoutAsOfCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(workoutQuery).aggregate(eq(actorId), eq(studentId), eq(fromDate), eq(toDate), eq(timezone), workoutAsOfCaptor.capture());

        ArgumentCaptor<Instant> measAsOfCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(measurementQuery).query(eq(actorId), eq(studentId), ArgumentMatchers.<Set<String>>any(), eq(fromDate), eq(toDate), eq(timezone), measAsOfCaptor.capture());

        assertThat(workoutAsOfCaptor.getValue()).isEqualTo(capturedAsOf);
        assertThat(measAsOfCaptor.getValue()).isEqualTo(capturedAsOf);
    }

    @Test
    @DisplayName("Deltas calculation: correctly computes delta, percentage change, and trend directions")
    void metricDeltas_computesCorrectValuesAndDirections() {
        stubEmptyWorkout(asOfFixed);
        when(workoutCompletionQuery.completedForStudent(studentId)).thenReturn(List.of());
        when(studentProfileUseCase.getStudentProfile(studentId)).thenReturn(null);

        Instant t1 = Instant.parse("2026-11-02T08:00:00Z");
        Instant t2 = Instant.parse("2026-11-05T08:00:00Z");

        var obsWeight1 = createObservationFact("WEIGHT", "Weight", "kg", new BigDecimal("70.0"), t1);
        var obsWeight2 = createObservationFact("WEIGHT", "Weight", "kg", new BigDecimal("72.5"), t2);

        var obsFat1 = createObservationFact("BODY_FAT", "Body Fat", "%", new BigDecimal("18.0"), t1);
        var obsFat2 = createObservationFact("BODY_FAT", "Body Fat", "%", new BigDecimal("17.1"), t2);

        var obsChest1 = createObservationFact("CHEST", "Chest", "cm", new BigDecimal("95.0"), t1);
        var obsChest2 = createObservationFact("CHEST", "Chest", "cm", new BigDecimal("95.0"), t2);

        var obsSingle = createObservationFact("WAIST", "Waist", "cm", new BigDecimal("80.0"), t1);

        var measSnapshot = new MeasurementProgressQuery.MeasurementProgressSnapshot(
                studentId, fromDate, toDate, timezone, asOfFixed,
                new MeasurementProgressQuery.MeasurementSummary(7, 4, 2, false, null, null, false, "OBSERVATIONS_AVAILABLE"),
                List.of(obsWeight1, obsWeight2, obsFat1, obsFat2, obsChest1, obsChest2, obsSingle),
                "meas-wm-123"
        );
        when(measurementQuery.query(any(), any(), ArgumentMatchers.<Set<String>>any(), any(), any(), any(), any())).thenReturn(measSnapshot);

        ProgressEngineQuery.ProgressSnapshot snapshot = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);

        var deltas = snapshot.measurement().metricDeltas();
        assertThat(deltas).hasSize(4);

        // 1. Weight: 70.0 -> 72.5 (+2.5 kg, +3.57%, INCREASED)
        var weightDelta = deltas.stream().filter(d -> "WEIGHT".equals(d.metricCode())).findFirst().orElseThrow();
        assertThat(weightDelta.deltaValue()).isEqualByComparingTo("2.5");
        assertThat(weightDelta.percentageChange()).isEqualByComparingTo("3.57");
        assertThat(weightDelta.trendDirection()).isEqualTo(TrendDirection.INCREASED);

        // 2. Body Fat: 18.0 -> 17.1 (-0.9 %, -5.00%, DECREASED)
        var fatDelta = deltas.stream().filter(d -> "BODY_FAT".equals(d.metricCode())).findFirst().orElseThrow();
        assertThat(fatDelta.deltaValue()).isEqualByComparingTo("-0.9");
        assertThat(fatDelta.percentageChange()).isEqualByComparingTo("-5.00");
        assertThat(fatDelta.trendDirection()).isEqualTo(TrendDirection.DECREASED);

        // 3. Chest: 95.0 -> 95.0 (0.0 cm, 0.00%, STABLE)
        var chestDelta = deltas.stream().filter(d -> "CHEST".equals(d.metricCode())).findFirst().orElseThrow();
        assertThat(chestDelta.deltaValue()).isEqualByComparingTo("0.0");
        assertThat(chestDelta.percentageChange()).isEqualByComparingTo("0.00");
        assertThat(chestDelta.trendDirection()).isEqualTo(TrendDirection.STABLE);

        // 4. Waist: only 1 observation -> INSUFFICIENT_DATA with null deltaValue and percentageChange
        var waistDelta = deltas.stream().filter(d -> "WAIST".equals(d.metricCode())).findFirst().orElseThrow();
        assertThat(waistDelta.trendDirection()).isEqualTo(TrendDirection.INSUFFICIENT_DATA);
        assertThat(waistDelta.deltaValue()).isNull();
        assertThat(waistDelta.percentageChange()).isNull();
    }

    @Test
    @DisplayName("Deltas calculation: incompatible units or identical timestamps return INSUFFICIENT_DATA with null delta")
    void metricDeltas_incompatibleUnitsOrIdenticalTimestamps_returnInsufficientData() {
        stubEmptyWorkout(asOfFixed);
        when(workoutCompletionQuery.completedForStudent(studentId)).thenReturn(List.of());
        when(studentProfileUseCase.getStudentProfile(studentId)).thenReturn(null);

        Instant t1 = Instant.parse("2026-11-02T08:00:00Z");
        Instant t2 = Instant.parse("2026-11-05T08:00:00Z");

        // Weight with incompatible units (kg vs cm - different dimension)
        var obsWeight1 = createObservationFact("WEIGHT", "Weight", "kg", new BigDecimal("70.0"), t1);
        var obsWeight2 = createObservationFact("WEIGHT", "Weight", "cm", new BigDecimal("72.5"), t2);

        // Chest with identical timestamp (no elapsed time)
        var obsChest1 = createObservationFact("CHEST", "Chest", "cm", new BigDecimal("95.0"), t1);
        var obsChest2 = createObservationFact("CHEST", "Chest", "cm", new BigDecimal("96.0"), t1);

        var measSnapshot = new MeasurementProgressQuery.MeasurementProgressSnapshot(
                studentId, fromDate, toDate, timezone, asOfFixed,
                new MeasurementProgressQuery.MeasurementSummary(4, 2, 2, false, null, null, false, "OBSERVATIONS_AVAILABLE"),
                List.of(obsWeight1, obsWeight2, obsChest1, obsChest2),
                "meas-wm-units"
        );
        when(measurementQuery.query(any(), any(), ArgumentMatchers.<Set<String>>any(), any(), any(), any(), any())).thenReturn(measSnapshot);

        // Mock unit conversions via MeasurementUnitReferenceQuery port
        when(unitReferenceQuery.resolveByCodes(any())).thenReturn(Map.of(
                "KG", new MeasurementUnitReferenceQuery.UnitDetails((short) 1, "KG", "kg", "MASS", "KG", BigDecimal.ONE, BigDecimal.ZERO),
                "CM", new MeasurementUnitReferenceQuery.UnitDetails((short) 2, "CM", "cm", "LENGTH", "M", new BigDecimal("0.01"), BigDecimal.ZERO)
        ));

        ProgressEngineQuery.ProgressSnapshot snapshot = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);

        var weightDelta = snapshot.measurement().metricDeltas().stream()
                .filter(d -> "WEIGHT".equals(d.metricCode())).findFirst().orElseThrow();
        assertThat(weightDelta.trendDirection()).isEqualTo(TrendDirection.INSUFFICIENT_DATA);
        assertThat(weightDelta.deltaValue()).isNull();
        assertThat(weightDelta.percentageChange()).isNull();

        var chestDelta = snapshot.measurement().metricDeltas().stream()
                .filter(d -> "CHEST".equals(d.metricCode())).findFirst().orElseThrow();
        assertThat(chestDelta.trendDirection()).isEqualTo(TrendDirection.INSUFFICIENT_DATA);
        assertThat(chestDelta.deltaValue()).isNull();
        assertThat(chestDelta.percentageChange()).isNull();
    }

    @Test
    @DisplayName("Deltas calculation: non-zero offset suppresses percentageChange")
    void metricDeltas_nonZeroOffset_suppressesPercentageChange() {
        stubEmptyWorkout(asOfFixed);
        when(workoutCompletionQuery.completedForStudent(studentId)).thenReturn(List.of());
        when(studentProfileUseCase.getStudentProfile(studentId)).thenReturn(null);

        Instant t1 = Instant.parse("2026-11-02T08:00:00Z");
        Instant t2 = Instant.parse("2026-11-05T08:00:00Z");

        // Temperature with Celsius vs Fahrenheit (non-zero offset)
        var obsTemp1 = createObservationFact("TEMP", "Temperature", "degC", new BigDecimal("36.5"), t1);
        var obsTemp2 = createObservationFact("TEMP", "Temperature", "degF", new BigDecimal("98.6"), t2);

        var measSnapshot = new MeasurementProgressQuery.MeasurementProgressSnapshot(
                studentId, fromDate, toDate, timezone, asOfFixed,
                new MeasurementProgressQuery.MeasurementSummary(2, 1, 2, false, null, null, false, "OBSERVATIONS_AVAILABLE"),
                List.of(obsTemp1, obsTemp2),
                "meas-wm-temp"
        );
        when(measurementQuery.query(any(), any(), ArgumentMatchers.<Set<String>>any(), any(), any(), any(), any())).thenReturn(measSnapshot);

        when(unitReferenceQuery.resolveByCodes(any())).thenReturn(Map.of(
                "DEGC", new MeasurementUnitReferenceQuery.UnitDetails((short) 10, "DEGC", "°C", "TEMPERATURE", "K", BigDecimal.ONE, new BigDecimal("273.15")),
                "DEGF", new MeasurementUnitReferenceQuery.UnitDetails((short) 11, "DEGF", "°F", "TEMPERATURE", "K", new BigDecimal("0.555555555555"), new BigDecimal("255.372222222222"))
        ));

        ProgressEngineQuery.ProgressSnapshot snapshot = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);

        var tempDelta = snapshot.measurement().metricDeltas().stream()
                .filter(d -> "TEMP".equals(d.metricCode())).findFirst().orElseThrow();
        assertThat(tempDelta.deltaValue()).isNotNull();
        // Because of affine offset, percentageChange must be null
        assertThat(tempDelta.percentageChange()).isNull();
    }

    @Test
    @DisplayName("Continuity: identifies PROLONGED_INACTIVITY and flags RETURN_AFTER_100_DAYS_V1 when days >= 100")
    void continuity_prolongedInactivity_whenWorkout100DaysAgo() {
        stubEmptyWorkout(asOfFixed);
        stubEmptyMeasurement(asOfFixed);

        // Last workout was 110 days before asOf
        Instant lastWorkoutInstant = asOfFixed.minus(110, ChronoUnit.DAYS);
        UUID executionId = UUID.randomUUID();

        when(workoutCompletionQuery.completedForStudent(studentId)).thenReturn(List.of(
                new WorkoutCompletionEvidenceQuery.Completion(executionId, studentId, 1L, lastWorkoutInstant, lastWorkoutInstant.plus(1, ChronoUnit.HOURS))
        ));

        ProgressEngineQuery.ProgressSnapshot snapshot = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);

        var continuity = snapshot.continuity();
        assertThat(continuity.state()).isEqualTo(ContinuityState.PROLONGED_INACTIVITY);
        assertThat(continuity.daysSinceLastWorkout()).isEqualTo(110);
        assertThat(continuity.lastWorkoutExecutionId()).isEqualTo(executionId);
        assertThat(continuity.safetyFlags()).contains(ProgressEngineService.SAFETY_FLAG_RETURN_100_DAYS);
    }

    @Test
    @DisplayName("Continuity: identifies RECENT_ACTIVITY_OBSERVED when days < 100 without unapproved 'consistent' label")
    void continuity_recentActivityObserved_withoutUnapprovedConsistentLabel() {
        stubEmptyWorkout(asOfFixed);
        stubEmptyMeasurement(asOfFixed);

        // Last workout was 5 days before asOf
        Instant lastWorkoutInstant = asOfFixed.minus(5, ChronoUnit.DAYS);
        UUID executionId = UUID.randomUUID();

        when(workoutCompletionQuery.completedForStudent(studentId)).thenReturn(List.of(
                new WorkoutCompletionEvidenceQuery.Completion(executionId, studentId, 1L, lastWorkoutInstant, lastWorkoutInstant.plus(1, ChronoUnit.HOURS))
        ));

        ProgressEngineQuery.ProgressSnapshot snapshot = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);

        var continuity = snapshot.continuity();
        assertThat(continuity.state()).isEqualTo(ContinuityState.RECENT_ACTIVITY_OBSERVED);
        assertThat(continuity.daysSinceLastWorkout()).isEqualTo(5);
        assertThat(continuity.lastWorkoutExecutionId()).isEqualTo(executionId);
        assertThat(continuity.safetyFlags()).isEmpty();
        assertThat(continuity.rationale()).contains("consistency unclassified without approved specialist threshold");
    }

    @Test
    @DisplayName("Continuity: identifies CONFIRMED_NEW when beginner onboarding completed before asOf with 0 months and zero logs")
    void continuity_confirmedNew_whenOnboardingCompletedWithZeroMonthsAndZeroLogs() {
        stubEmptyWorkout(asOfFixed);
        stubEmptyMeasurement(asOfFixed);

        when(workoutCompletionQuery.completedForStudent(studentId)).thenReturn(List.of());

        StudentProfile profile = new StudentProfile(
                studentId, LocalDate.of(1995, 1, 1), null,
                TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, 3, 45,
                asOfFixed.minus(3, ChronoUnit.DAYS), asOfFixed.minus(4, ChronoUnit.DAYS), asOfFixed.minus(3, ChronoUnit.DAYS)
        );
        when(studentProfileUseCase.getStudentProfile(studentId)).thenReturn(profile);

        ProgressEngineQuery.ProgressSnapshot snapshot = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);

        var continuity = snapshot.continuity();
        assertThat(continuity.state()).isEqualTo(ContinuityState.CONFIRMED_NEW);
        assertThat(continuity.daysSinceLastWorkout()).isNull();
        assertThat(continuity.safetyFlags()).isEmpty();
    }

    @Test
    @DisplayName("Continuity: returns UNKNOWN_HISTORY when onboarding completed after asOf (historical asOf invariant)")
    void continuity_unknownHistory_whenOnboardingCompletedAfterAsOf() {
        stubEmptyWorkout(asOfFixed);
        stubEmptyMeasurement(asOfFixed);

        when(workoutCompletionQuery.completedForStudent(studentId)).thenReturn(List.of());

        // Onboarding completed 2 days AFTER asOf
        StudentProfile profile = new StudentProfile(
                studentId, LocalDate.of(1995, 1, 1), null,
                TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, 3, 45,
                asOfFixed.plus(2, ChronoUnit.DAYS), asOfFixed.plus(1, ChronoUnit.DAYS), asOfFixed.plus(2, ChronoUnit.DAYS)
        );
        when(studentProfileUseCase.getStudentProfile(studentId)).thenReturn(profile);

        ProgressEngineQuery.ProgressSnapshot snapshot = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);

        var continuity = snapshot.continuity();
        assertThat(continuity.state()).isEqualTo(ContinuityState.UNKNOWN_HISTORY);
        assertThat(continuity.rationale()).contains("as of reference time");
    }

    @Test
    @DisplayName("Continuity: returns UNKNOWN_HISTORY when beginner lacks 0 months confirmation")
    void continuity_unknownHistory_whenBeginnerLacksZeroMonthsEvidence() {
        stubEmptyWorkout(asOfFixed);
        stubEmptyMeasurement(asOfFixed);

        when(workoutCompletionQuery.completedForStudent(studentId)).thenReturn(List.of());

        // Beginner but training_experience_months is 6.0
        StudentProfile profile = new StudentProfile(
                studentId, LocalDate.of(1995, 1, 1), null,
                TrainingExperienceLevel.BEGINNER, new BigDecimal("6.0"), 3, 45,
                asOfFixed.minus(3, ChronoUnit.DAYS), asOfFixed.minus(4, ChronoUnit.DAYS), asOfFixed.minus(3, ChronoUnit.DAYS)
        );
        when(studentProfileUseCase.getStudentProfile(studentId)).thenReturn(profile);

        ProgressEngineQuery.ProgressSnapshot snapshot = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);

        var continuity = snapshot.continuity();
        assertThat(continuity.state()).isEqualTo(ContinuityState.UNKNOWN_HISTORY);
        assertThat(continuity.rationale()).contains("lacks explicit confirmed beginner evidence");
    }

    @Test
    @DisplayName("Continuity: Trainer with historyUntil before asOf receives RESTRICTED_HISTORY")
    void continuity_trainerWithHistoryUntilBeforeAsOf_returnsRestrictedHistory() {
        stubEmptyWorkout(asOfFixed);
        stubEmptyMeasurement(asOfFixed);

        UUID trainerId = UUID.randomUUID();
        when(userAccountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        Instant historyUntil = asOfFixed.minus(5, ChronoUnit.DAYS);
        when(coachingAuthority.evaluateCurrent(any()))
                .thenReturn(new AuthorityDecision(true, CoachingSharing.AuthorityReason.ALLOWED,
                        UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(), 1L, DataAccessLevel.VIEW, null, historyUntil));

        ProgressEngineQuery.ProgressSnapshot snapshot = service.query(trainerId, studentId, fromDate, toDate, timezone, asOfFixed);

        var continuity = snapshot.continuity();
        assertThat(continuity.state()).isEqualTo(ContinuityState.RESTRICTED_HISTORY);
        assertThat(continuity.rationale()).contains("Permitted workout history scope ends before asOf");
    }

    @Test
    @DisplayName("Authority: actor with Workout access but denied Body Metrics receives partial snapshot without leaking Measurement data")
    void authority_partialAccess_workoutAllowed_measurementDenied() {
        stubEmptyWorkout(asOfFixed);
        when(workoutCompletionQuery.completedForStudent(studentId)).thenReturn(List.of());
        when(studentProfileUseCase.getStudentProfile(studentId)).thenReturn(null);

        // Measurement throws access denied
        when(measurementQuery.query(any(), any(), ArgumentMatchers.<Set<String>>any(), any(), any(), any(), any()))
                .thenThrow(new MeasurementFailure(MeasurementError.MEASUREMENT_ACCESS_DENIED, "Forbidden"));

        ProgressEngineQuery.ProgressSnapshot snapshot = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);

        assertThat(snapshot.workout().availability()).isEqualTo(SourceAvailability.NO_DATA);
        assertThat(snapshot.measurement().availability()).isEqualTo(SourceAvailability.RESTRICTED);
        assertThat(snapshot.measurement().observations()).isEmpty();
        assertThat(snapshot.measurement().sourceWatermark()).isNull(); // strictly hidden
        assertThat(snapshot.compositeWatermark()).isNotNull();
    }

    @Test
    @DisplayName("Authority: actor denied on ALL sources receives PROGRESS_ACCESS_DENIED 403")
    void authority_deniedOnAllSources_throwsProgressAccessDenied() {
        when(workoutQuery.aggregate(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkoutExecutionFailure(WorkoutExecutionError.WORKOUT_EXECUTION_ACCESS_DENIED, "Access denied"));

        when(measurementQuery.query(any(), any(), ArgumentMatchers.<Set<String>>any(), any(), any(), any(), any()))
                .thenThrow(new MeasurementFailure(MeasurementError.MEASUREMENT_ACCESS_DENIED, "Access denied"));

        assertThatThrownBy(() -> service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed))
                .isInstanceOf(ProgressFailure.class)
                .satisfies(e -> assertThat(((ProgressFailure) e).error()).isEqualTo(ProgressError.PROGRESS_ACCESS_DENIED));
    }

    @Test
    @DisplayName("Technical error at source is rethrown and not swallowed into empty result")
    void technicalError_atSource_isRethrown() {
        when(workoutQuery.aggregate(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Database connection pool exhausted"));

        assertThatThrownBy(() -> service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection pool exhausted");
    }

    @Test
    @DisplayName("Composite watermark: determinism and sensitivity to source watermarks")
    void compositeWatermark_determinismAndSensitivity() {
        stubEmptyWorkout(asOfFixed);
        stubEmptyMeasurement(asOfFixed);
        when(workoutCompletionQuery.completedForStudent(studentId)).thenReturn(List.of());
        when(studentProfileUseCase.getStudentProfile(studentId)).thenReturn(null);

        var snap1 = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);
        var snap2 = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);

        assertThat(snap1.compositeWatermark()).isEqualTo(snap2.compositeWatermark());

        // Change workout watermark -> composite watermark must change
        when(workoutQuery.aggregate(any(), any(), any(), any(), any(), any()))
                .thenReturn(new WorkoutProgressAggregationQuery.WorkoutProgressSnapshot(
                        studentId, fromDate, toDate, timezone, asOfFixed,
                        null, null, List.of(), List.of(), "modified-workout-wm"
                ));

        var snap3 = service.query(actorId, studentId, fromDate, toDate, timezone, asOfFixed);
        assertThat(snap3.compositeWatermark()).isNotEqualTo(snap1.compositeWatermark());
    }

    private void stubEmptyWorkout(Instant asOf) {
        when(workoutQuery.aggregate(any(), any(), any(), any(), any(), any()))
                .thenReturn(new WorkoutProgressAggregationQuery.WorkoutProgressSnapshot(
                        studentId, fromDate, toDate, timezone, asOf,
                        new WorkoutProgressAggregationQuery.AdherenceSummary(
                                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                null, null, "NO_SCHEDULED_DUE_SESSIONS"
                        ),
                        null,
                        List.of(),
                        List.of(),
                        "workout-wm-empty"
                ));
    }

    private void stubEmptyMeasurement(Instant asOf) {
        when(measurementQuery.query(any(), any(), ArgumentMatchers.<Set<String>>any(), any(), any(), any(), any()))
                .thenReturn(new MeasurementProgressQuery.MeasurementProgressSnapshot(
                        studentId, fromDate, toDate, timezone, asOf,
                        new MeasurementProgressQuery.MeasurementSummary(0, 0, 0, false, null, null, false, "NO_MEASUREMENTS_RECORDED"),
                        List.of(),
                        "meas-wm-empty"
                ));
    }

    private MeasurementProgressQuery.MeasurementObservationFact createObservationFact(
            String code, String name, String unit, BigDecimal value, Instant measuredAt
    ) {
        return new MeasurementProgressQuery.MeasurementObservationFact(
                UUID.randomUUID(), studentId, 1, code, name, measuredAt,
                measuredAt.atZone(timezone).toLocalDate(),
                value, null, null, (short) 1, unit, unit,
                null, (short) 1, "MANUAL", "Manual", "MANUAL",
                (short) 1, "DIRECT", "Direct", studentId, null, null,
                null, null, "VALID", null, measuredAt, "HISTORICAL_SNAPSHOT_EXACT"
        );
    }
}
