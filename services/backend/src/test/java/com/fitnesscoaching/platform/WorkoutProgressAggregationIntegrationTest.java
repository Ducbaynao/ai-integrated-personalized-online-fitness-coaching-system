package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Outcome;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutProgressAggregationQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutProgressAggregationQuery.*;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class WorkoutProgressAggregationIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("workout_progress_test_db")
            .withUsername("fitness_app")
            .withPassword("testpass123");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkoutProgressAggregationQuery query;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.fitnesscoaching.platform.modules.workout.application.service.WorkoutExecutionAccessPolicy accessPolicy;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private CoachingLifecycleUseCase lifecycle;

    @Autowired
    private CoachingSharingUseCase sharing;

    private final ZoneId zoneVn = ZoneId.of("Asia/Ho_Chi_Minh");
    private final LocalDate fromDate = LocalDate.of(2026, 11, 1);
    private final LocalDate toDate = LocalDate.of(2026, 11, 7);

    @Test
    @DisplayName("1. On-time and off-schedule workouts: completion=100% and on-time adherence=50%")
    void onTimeAndOffScheduleWorkouts_aggregatesCorrectCountsAndDeviations() {
        UUID student = createStudent("student_1");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID exerciseVariation = createVariation(student, "Squat");

        // Monday Nov 2: Planned workout 1
        Instant plannedMon = Instant.parse("2026-11-02T02:00:00Z"); // 09:00 VN
        UUID pw1 = createPlannedWorkout(student, plannedMon, "SCHEDULED");

        // Wednesday Nov 4: Planned workout 2
        Instant plannedWed = Instant.parse("2026-11-04T02:00:00Z"); // 09:00 VN
        UUID pw2 = createPlannedWorkout(student, plannedWed, "SCHEDULED");

        // Student completes pw1 on Tuesday Nov 3 (off-schedule by +1 day)
        Instant actualTue = Instant.parse("2026-11-03T03:00:00Z"); // 10:00 VN
        UUID exec1 = createActualWorkout(student, pw1, actualTue, actualTue.plusSeconds(3600), "COMPLETED", new BigDecimal("8.00"));
        addExerciseAndSet(exec1, exerciseVariation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("60.00"), kgUnit, new BigDecimal("8.00"), 45);

        // Student completes pw2 on Wednesday Nov 4 (on-time, deviation = 0)
        Instant actualWed = Instant.parse("2026-11-04T02:30:00Z"); // 09:30 VN
        UUID exec2 = createActualWorkout(student, pw2, actualWed, actualWed.plusSeconds(3600), "COMPLETED", new BigDecimal("7.50"));
        addExerciseAndSet(exec2, exerciseVariation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("60.00"), kgUnit, new BigDecimal("7.50"), 45);

        // Query as of Thursday Nov 5 (both workouts are concluded)
        Instant asOf = Instant.parse("2026-11-05T01:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        assertThat(snapshot).isNotNull();
        AdherenceSummary adh = snapshot.adherence();
        assertThat(adh.scheduledDueCount()).isEqualTo(2);
        assertThat(adh.completedDueCount()).isEqualTo(2);
        assertThat(adh.completedOnTimeCount()).isEqualTo(1);
        assertThat(adh.completedOffScheduleCount()).isEqualTo(1);
        assertThat(adh.missedCount()).isEqualTo(0);
        assertThat(adh.todayPendingCount()).isEqualTo(0);

        // Explicit distinct assertions: completion=100%, on-time adherence=50%
        assertThat(adh.rawCompletionRate()).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(adh.onTimeAdherenceRate()).isEqualByComparingTo(new BigDecimal("0.5000"));
        assertThat(adh.availabilityReason()).isNull();

        // Check deviation days on individual facts
        PlannedOccurrenceFact fact1 = snapshot.plannedOccurrences().stream().filter(f -> f.occurrenceId().equals(pw1)).findFirst().orElseThrow();
        assertThat(fact1.scheduleDeviationDays()).isEqualTo(1);
        assertThat(fact1.linkedExecutionStatus()).isEqualTo("COMPLETED");

        PlannedOccurrenceFact fact2 = snapshot.plannedOccurrences().stream().filter(f -> f.occurrenceId().equals(pw2)).findFirst().orElseThrow();
        assertThat(fact2.scheduleDeviationDays()).isEqualTo(0);
        assertThat(fact2.linkedExecutionStatus()).isEqualTo("COMPLETED");

        // Check volume & RPE facts
        VolumeAndIntensitySummary vol = snapshot.volumeAndIntensity();
        assertThat(vol.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat(vol.totalSetsCompleted()).isEqualTo(2);
        assertThat(vol.workingSetsCompleted()).isEqualTo(2);
        assertThat(vol.loadCoverageRate()).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(vol.hasIncompleteVolumeCoverage()).isFalse();
        assertThat(vol.totalSetDurationSeconds()).isEqualTo(90); // 45 + 45
        assertThat(vol.totalSessionElapsedSeconds()).isEqualTo(7200); // 3600 + 3600
        assertThat(vol.averageSessionRpe()).isEqualByComparingTo(new BigDecimal("7.75")); // (8.00 + 7.50) / 2
    }

    @Test
    @DisplayName("2. Timezone boundary correctly assigns workouts to local window (UTC+7 vs UTC)")
    void timezoneBoundary_correctlyAssignsWorkoutsToLocalWindow() {
        UUID student = createStudent("student_tz");

        Instant boundaryTime = Instant.parse("2026-10-31T20:00:00Z");
        createPlannedWorkout(student, boundaryTime, "SCHEDULED");

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");

        // When queried with Asia/Ho_Chi_Minh: Nov 1
        WorkoutProgressSnapshot vnSnapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);
        assertThat(vnSnapshot.plannedOccurrences()).hasSize(1);
        assertThat(vnSnapshot.plannedOccurrences().getFirst().plannedLocalDate()).isEqualTo(LocalDate.of(2026, 11, 1));

        // When queried with UTC: Oct 31 (outside)
        WorkoutProgressSnapshot utcSnapshot = query.aggregate(student, student, fromDate, toDate, ZoneId.of("UTC"), asOf);
        assertThat(utcSnapshot.plannedOccurrences()).isEmpty();
    }

    @Test
    @DisplayName("3. Lifecycle variations (upcoming, in-progress, partial, aborted, cancelled, rescheduled) handled without double-counting")
    void lifecycleVariations_upcomingInProgressPartialAbortedCancelledRescheduled_handledCorrectly() {
        UUID student = createStudent("student_lifecycle");

        // Nov 1 (Sunday): Missed planned workout
        Instant tSun = Instant.parse("2026-11-01T02:00:00Z");
        createPlannedWorkout(student, tSun, "SCHEDULED");

        // Nov 2 (Monday): Rescheduled predecessor
        Instant tMon = Instant.parse("2026-11-02T02:00:00Z");
        UUID pwOrig = createPlannedWorkout(student, tMon, "RESCHEDULED");

        // Replacement scheduled for Friday Nov 6 (Upcoming)
        Instant tFri = Instant.parse("2026-11-06T02:00:00Z");
        createRescheduledReplacement(student, tFri, pwOrig);

        // Nov 3 (Tuesday): Cancelled workout
        Instant tTue = Instant.parse("2026-11-03T02:00:00Z");
        createPlannedWorkout(student, tTue, "CANCELLED");

        // Nov 4 (Wednesday - TODAY relative to asOf):
        // Workout A: In-progress
        Instant tWed1 = Instant.parse("2026-11-04T02:00:00Z");
        UUID pwWed1 = createPlannedWorkout(student, tWed1, "SCHEDULED");
        createActualWorkout(student, pwWed1, tWed1, null, "IN_PROGRESS", null);

        // Workout B: Pending today (not started yet, NOT missed!)
        Instant tWed2 = Instant.parse("2026-11-04T04:00:00Z");
        createPlannedWorkout(student, tWed2, "SCHEDULED");

        // Query as of Wednesday Nov 4 at 15:00 VN (08:00 UTC)
        Instant asOfWed = Instant.parse("2026-11-04T08:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOfWed);

        AdherenceSummary adh = snapshot.adherence();
        assertThat(adh.scheduledDueCount()).isEqualTo(1); // Nov 1
        assertThat(adh.missedCount()).isEqualTo(1);
        assertThat(adh.completedDueCount()).isEqualTo(0);
        assertThat(adh.rescheduledCount()).isEqualTo(1);
        assertThat(adh.cancelledCount()).isEqualTo(1);
        assertThat(adh.inProgressCount()).isEqualTo(1);
        assertThat(adh.todayPendingCount()).isEqualTo(1);
        assertThat(adh.upcomingCount()).isEqualTo(1);
        assertThat(adh.rawCompletionRate()).isEqualByComparingTo(new BigDecimal("0.0000"));
        assertThat(adh.onTimeAdherenceRate()).isEqualByComparingTo(new BigDecimal("0.0000"));
    }

    @Test
    @DisplayName("4. Window with zero due workouts returns null adherence with explicit availability reason")
    void emptyWindow_returnsNullAdherenceWithAvailabilityReason() {
        UUID student = createStudent("student_empty");

        Instant tFri = Instant.parse("2026-11-06T02:00:00Z");
        createPlannedWorkout(student, tFri, "SCHEDULED");

        Instant asOfMon = Instant.parse("2026-11-02T02:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOfMon);

        AdherenceSummary adh = snapshot.adherence();
        assertThat(adh.scheduledDueCount()).isEqualTo(0);
        assertThat(adh.rawCompletionRate()).isNull();
        assertThat(adh.onTimeAdherenceRate()).isNull();
        assertThat(adh.availabilityReason()).isEqualTo("NO_SCHEDULED_WORKOUTS_DUE");
        assertThat(adh.upcomingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("5. Unplanned actual workout session is separated from planned cohort and records physical volume")
    void unplannedActualSession_isSeparatedFromPlannedCohort() {
        UUID student = createStudent("student_unplanned");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID variation = createVariation(student, "Deadlift");

        Instant actualTime = Instant.parse("2026-11-03T05:00:00Z");
        UUID unpExec = createActualWorkout(student, null, actualTime, actualTime.plusSeconds(3600), "COMPLETED", new BigDecimal("9.00"));
        addExerciseAndSet(unpExec, variation, 1, "WORKING", "COMPLETED", 5, new BigDecimal("100.00"), kgUnit, new BigDecimal("9.00"), 60);

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        AdherenceSummary adh = snapshot.adherence();
        assertThat(adh.unplannedCompletedCount()).isEqualTo(1);
        assertThat(adh.scheduledDueCount()).isEqualTo(0);
        assertThat(adh.rawCompletionRate()).isNull();

        assertThat(snapshot.actualSessions()).hasSize(1);
        assertThat(snapshot.actualSessions().getFirst().isUnplanned()).isTrue();

        VolumeAndIntensitySummary vol = snapshot.volumeAndIntensity();
        assertThat(vol.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(vol.workingSetsCompleted()).isEqualTo(1);
        assertThat(vol.averageSessionRpe()).isEqualByComparingTo(new BigDecimal("9.00"));
    }

    @Test
    @DisplayName("6. Modifying an active set log changes source watermark even without modifying session or plan timestamps")
    void setLogModification_changesSourceWatermark() {
        UUID student = createStudent("student_wm_edit");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID variation = createVariation(student, "Overhead Press");

        Instant tMon = Instant.parse("2026-11-02T02:00:00Z");
        UUID pw = createPlannedWorkout(student, tMon, "SCHEDULED");
        UUID exec = createActualWorkout(student, pw, tMon, null, "IN_PROGRESS", null);
        UUID setLogId = addExerciseAndSet(exec, variation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("40.00"), kgUnit, new BigDecimal("7.00"), 30);

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");
        WorkoutProgressSnapshot snap1 = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);
        String wm1 = snap1.sourceWatermark();

        jdbc.update("UPDATE fitness.set_logs SET repetitions = 12 WHERE id = ?", setLogId);

        WorkoutProgressSnapshot snap2 = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);
        String wm2 = snap2.sourceWatermark();

        assertThat(wm1).isNotEqualTo(wm2);
    }

    @Test
    @DisplayName("7. Moving across window boundary changes source watermark (inside window vs outside window)")
    void recordMovingAcrossWindowBoundary_changesSourceWatermark() {
        UUID student = createStudent("student_wm_move");

        Instant insideTime = Instant.parse("2026-11-03T02:00:00Z");
        createPlannedWorkout(student, insideTime, "SCHEDULED");

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");

        WorkoutProgressSnapshot snapInside = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);
        String wmInside = snapInside.sourceWatermark();
        assertThat(snapInside.plannedOccurrences()).hasSize(1);

        LocalDate nextWeekFrom = LocalDate.of(2026, 11, 8);
        LocalDate nextWeekTo = LocalDate.of(2026, 11, 14);
        WorkoutProgressSnapshot snapOutside = query.aggregate(student, student, nextWeekFrom, nextWeekTo, zoneVn, asOf);
        String wmOutside = snapOutside.sourceWatermark();
        assertThat(snapOutside.plannedOccurrences()).isEmpty();

        assertThat(wmInside).isNotEqualTo(wmOutside);
    }

    @Test
    @DisplayName("8. Authorization boundary rejects unauthorized actor")
    void authorizationBoundary_rejectsUnauthorizedActor() {
        UUID student = createStudent("student_auth_test");
        UUID unauthorizedActor = UUID.randomUUID();

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");

        assertThatThrownBy(() -> query.aggregate(unauthorizedActor, student, fromDate, toDate, zoneVn, asOf))
                .isInstanceOf(WorkoutExecutionFailure.class)
                .extracting(e -> ((WorkoutExecutionFailure) e).error())
                .isEqualTo(WorkoutExecutionError.WORKOUT_EXECUTION_ACCESS_DENIED);
    }

    @Test
    @DisplayName("9. Missing RPE is preserved as unknown null, never coerced to zero")
    void missingRpe_isUnknownNull_notZero() {
        UUID student = createStudent("student_no_rpe");
        UUID variation = createVariation(student, "Bodyweight Squat");

        Instant tMon = Instant.parse("2026-11-02T02:00:00Z");
        UUID pw = createPlannedWorkout(student, tMon, "SCHEDULED");
        UUID exec = createActualWorkout(student, pw, tMon, tMon.plusSeconds(1800), "COMPLETED", null);
        addExerciseAndSet(exec, variation, 1, "WORKING", "COMPLETED", 15, null, null, null, null);

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        VolumeAndIntensitySummary vol = snapshot.volumeAndIntensity();
        assertThat(vol.averageSessionRpe()).isNull();
        assertThat(vol.averageSetRpe()).isNull();
    }

    @Test
    @DisplayName("10. Unit conversion converts pounds to kg, separates bodyweight and unknown units, and calculates load coverage")
    void unitConversion_convertsPoundsToKg_andDistinguishesBodyweightAndUnknownUnits() {
        UUID student = createStudent("student_units");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        Short lbUnit = createUnit("LB", "lb", "MASS", "KG", 0.45359237);
        Short ptsUnit = createUnit("POINTS", "pts", "CUSTOM", "POINTS", 1.0);
        UUID variation = createVariation(student, "Bench Press");
        UUID bwVariation = createVariation(student, "Pull-up");
        markVariationAsBodyweight(bwVariation);

        Instant t = Instant.parse("2026-11-03T02:00:00Z");
        UUID exec = createActualWorkout(student, null, t, t.plusSeconds(3600), "COMPLETED", new BigDecimal("8.0"));

        // Set 1: 10 reps @ 100 lb -> 45.3592 kg * 10 = 453.59 kg
        addExerciseAndSet(exec, variation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("100.00"), lbUnit, null, 30);
        // Set 2: 10 reps @ 50 kg -> 500.00 kg
        addExerciseAndSet(exec, variation, 2, "WORKING", "COMPLETED", 10, new BigDecimal("50.00"), kgUnit, null, 30);
        // Set 3: 10 reps bodyweight (null load with verified bodyweight exercise)
        addExerciseAndSet(exec, bwVariation, 3, "WORKING", "COMPLETED", 10, null, null, null, 30);
        // Set 4: 10 reps @ 20 points (unknown/non-convertible unit)
        addExerciseAndSet(exec, variation, 4, "WORKING", "COMPLETED", 10, new BigDecimal("20.00"), ptsUnit, null, 30);

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        VolumeAndIntensitySummary vol = snapshot.volumeAndIntensity();
        assertThat(vol.workingSetsCompleted()).isEqualTo(4);
        assertThat(vol.bodyweightSetsCompleted()).isEqualTo(1);
        assertThat(vol.setsWithUnknownUnitCount()).isEqualTo(1);
        assertThat(vol.setsWithCalculableVolumeCount()).isEqualTo(2);
        // 2 sets with calculable volume out of 4 total working sets => 50% coverage
        assertThat(vol.loadCoverageRate()).isEqualByComparingTo(new BigDecimal("0.5000"));
        assertThat(vol.hasIncompleteVolumeCoverage()).isTrue();
        // Known volume: 453.59 + 500.00 = 953.59 kg
        assertThat(vol.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("953.59"));
    }

    @Test
    @DisplayName("11. asOf filters out future actuals and reconstructs in-progress state for sessions completing after asOf")
    void asOfFiltersOutFutureActuals_andReconstructsInProgressState() {
        UUID student = createStudent("student_asof");

        // Session 1: Started before asOf (10:00), but ended AFTER asOf (12:00), where asOf is 11:00
        Instant t1Start = Instant.parse("2026-11-03T03:00:00Z"); // 10:00 VN
        Instant t1End = Instant.parse("2026-11-03T05:00:00Z");   // 12:00 VN
        UUID exec1 = createActualWorkout(student, null, t1Start, t1End, "COMPLETED", new BigDecimal("7.0"));

        // Session 2: Started AFTER asOf (14:00)
        Instant t2Start = Instant.parse("2026-11-03T07:00:00Z"); // 14:00 VN
        createActualWorkout(student, null, t2Start, t2Start.plusSeconds(3600), "COMPLETED", new BigDecimal("8.0"));

        // asOf is 11:00 VN (04:00 UTC)
        Instant asOf = Instant.parse("2026-11-03T04:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        // Future session 2 MUST be completely excluded!
        assertThat(snapshot.actualSessions()).hasSize(1);
        ActualSessionFact fact1 = snapshot.actualSessions().getFirst();
        assertThat(fact1.executionId()).isEqualTo(exec1);

        // Session 1 was still IN_PROGRESS at 11:00 (ended at 12:00)
        assertThat(fact1.status()).isEqualTo("IN_PROGRESS");
        assertThat(fact1.performedEndAt()).isNull();
    }

    @Test
    @DisplayName("12. Overnight workout in-progress across midnight is not marked MISSED")
    void overnightWorkout_inProgress_isNotMarkedMissed() {
        UUID student = createStudent("student_overnight");

        // Planned workout for Monday Nov 2 at 23:00 VN (16:00 UTC)
        Instant plannedStart = Instant.parse("2026-11-02T16:00:00Z");
        UUID pw = createPlannedWorkout(student, plannedStart, "SCHEDULED");

        // Actual workout started Monday Nov 2 at 23:30 VN (16:30 UTC) and still IN_PROGRESS
        Instant actualStart = Instant.parse("2026-11-02T16:30:00Z");
        createActualWorkout(student, pw, actualStart, null, "IN_PROGRESS", null);

        // asOf is Tuesday Nov 3 at 00:30 VN (17:30 UTC on Nov 2)
        Instant asOf = Instant.parse("2026-11-02T17:30:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        AdherenceSummary adh = snapshot.adherence();
        // Monday date has passed, but session is actively IN_PROGRESS => NOT missed!
        assertThat(adh.missedCount()).isEqualTo(0);
        assertThat(adh.inProgressCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("13. Trainer with limited time scope clips query window and does not expose out-of-scope workouts")
    void trainerLimitedTimeScope_clipsDataAndDoesNotExposeOutOfScopeWorkouts() {
        UUID student = createStudent("student_scope");
        UUID trainer = createTrainer("trainer_scope");
        Outcome outcome = accepted(student, trainer);

        // Student grants trainer permissions with history window starting Nov 3 (Nov 3 to Nov 7)
        Instant historyFrom = Instant.parse("2026-11-03T00:00:00Z");
        sharing.grantOrReplace(student, outcome.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, historyFrom, null, null, null, UUID.randomUUID());
        sharing.grantOrReplace(student, outcome.relationship().id(), DataScope.WORKOUT_HISTORY,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, historyFrom, null, null, null, UUID.randomUUID());

        // Planned workout 1 on Nov 1 (OUT of trainer's scope)
        createPlannedWorkout(student, Instant.parse("2026-11-01T02:00:00Z"), "SCHEDULED");
        // Planned workout 2 on Nov 4 (IN trainer's scope)
        UUID pwInScope = createPlannedWorkout(student, Instant.parse("2026-11-04T02:00:00Z"), "SCHEDULED");

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");
        // Trainer queries window Nov 1 to Nov 7
        WorkoutProgressSnapshot snapshot = query.aggregate(trainer, student, fromDate, toDate, zoneVn, asOf);

        // Nov 1 planned workout MUST NOT be exposed!
        assertThat(snapshot.plannedOccurrences()).hasSize(1);
        assertThat(snapshot.plannedOccurrences().getFirst().occurrenceId()).isEqualTo(pwInScope);
    }

    @Test
    @DisplayName("14. Trainer without WORKOUT_PLAN permission cannot see planned workouts")
    void trainerWithoutWorkoutPlanPermission_cannotSeePlannedWorkouts() {
        UUID student = createStudent("student_no_plan_perm");
        UUID trainer = createTrainer("trainer_no_plan_perm");
        Outcome outcome = accepted(student, trainer);

        // Grant ONLY WORKOUT_HISTORY, NOT WORKOUT_PLAN
        sharing.grantOrReplace(student, outcome.relationship().id(), DataScope.WORKOUT_HISTORY,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, null, UUID.randomUUID());

        // Planned workout on Nov 2
        createPlannedWorkout(student, Instant.parse("2026-11-02T02:00:00Z"), "SCHEDULED");
        // Actual workout on Nov 3
        Instant actTime = Instant.parse("2026-11-03T02:00:00Z");
        createActualWorkout(student, null, actTime, actTime.plusSeconds(3600), "COMPLETED", new BigDecimal("7.0"));

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(trainer, student, fromDate, toDate, zoneVn, asOf);

        // Planned workouts must be completely hidden!
        assertThat(snapshot.plannedOccurrences()).isEmpty();
        assertThat(snapshot.adherence().scheduledDueCount()).isEqualTo(0);
        // Actual workout is visible
        assertThat(snapshot.actualSessions()).hasSize(1);
    }

    @Test
    @DisplayName("15. Linked execution outside trainer history scope is concealed from planned occurrence facts")
    void linkedExecutionOutsideTrainerHistoryScope_isNotExposed() {
        UUID student = createStudent("student_linked_scope");
        UUID trainer = createTrainer("trainer_linked_scope");
        Outcome outcome = accepted(student, trainer);

        // Trainer granted WORKOUT_PLAN unbounded, but WORKOUT_HISTORY starting Nov 3
        Instant historyFrom = Instant.parse("2026-11-03T00:00:00Z");
        sharing.grantOrReplace(student, outcome.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, null, UUID.randomUUID());
        sharing.grantOrReplace(student, outcome.relationship().id(), DataScope.WORKOUT_HISTORY,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, historyFrom, null, null, null, UUID.randomUUID());

        // Planned workout is on Nov 2 (BEFORE trainer history scope Nov 3 => cannot verify actual)
        UUID pw = createPlannedWorkout(student, Instant.parse("2026-11-02T02:00:00Z"), "SCHEDULED");

        // Linked execution occurred on Nov 1 (OUT of trainer history scope!)
        Instant actualStart = Instant.parse("2026-11-01T02:00:00Z");
        createActualWorkout(student, pw, actualStart, actualStart.plusSeconds(3600), "COMPLETED", new BigDecimal("8.0"));

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(trainer, student, fromDate, toDate, zoneVn, asOf);

        assertThat(snapshot.plannedOccurrences()).hasSize(1);
        PlannedOccurrenceFact fact = snapshot.plannedOccurrences().getFirst();
        // Since linked actual occurred on Nov 1 (outside trainer's history window), it MUST be concealed!
        assertThat(fact.linkedExecutionId()).isNull();
        assertThat(fact.linkedExecutionStatus()).isNull();
        assertThat(fact.linkedPerformedStartAt()).isNull();
        assertThat(fact.evidenceStatus()).isEqualTo("RESTRICTED_OR_UNKNOWN");
        assertThat(snapshot.actualSessions()).isEmpty();

        // Crucial invariant: restricted actual history MUST NOT be inferred as MISSED!
        AdherenceSummary adh = snapshot.adherence();
        assertThat(adh.missedCount()).isEqualTo(0);
        assertThat(adh.unverifiableDueCount()).isEqualTo(1);
        assertThat(adh.rawCompletionRate()).isNull();
        assertThat(adh.onTimeAdherenceRate()).isNull();
        assertThat(adh.availabilityReason()).isEqualTo("ACTUAL_HISTORY_RESTRICTED");
    }

    @Test
    @DisplayName("16. Snapshot consistency: REPEATABLE READ isolation ensures concurrent mutations committed between reads do not tear the snapshot")
    void snapshotConsistency_repeatableReadIsolationProtectsAgainstConcurrentMutations() throws Exception {
        UUID student = createStudent("student_isolation");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID variation = createVariation(student, "Squat");

        // Initial state: planned workout 1 on Nov 2
        Instant t1 = Instant.parse("2026-11-02T02:00:00Z");
        UUID pw1 = createPlannedWorkout(student, t1, "SCHEDULED");

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");

        java.util.concurrent.CountDownLatch readerStartedLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch writerCommittedLatch = new java.util.concurrent.CountDownLatch(1);

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            readerStartedLatch.countDown();
            boolean completed = writerCommittedLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                throw new IllegalStateException("Writer latch timed out");
            }
            return result;
        }).when(accessPolicy).requireStudentMutation(eq(student), eq(student));

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // Background writer task: waits for reader to execute first query, then in a separate transaction inserts actual workout & commits
            java.util.concurrent.Future<?> writerFuture = executor.submit(() -> {
                try {
                    readerStartedLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    org.springframework.transaction.support.TransactionTemplate txTemplate =
                            new org.springframework.transaction.support.TransactionTemplate(transactionManager);
                    txTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    txTemplate.execute(status -> {
                        // Insert actual workout for pw1 on Nov 2
                        Instant actualTime = Instant.parse("2026-11-02T03:00:00Z");
                        UUID exec = createActualWorkout(student, pw1, actualTime, actualTime.plusSeconds(3600), "COMPLETED", new BigDecimal("8.00"));
                        addExerciseAndSet(exec, variation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("100.00"), kgUnit, null, 30);
                        return null;
                    });
                    writerCommittedLatch.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Reader aggregation call
            WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);
            writerFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);

            // Because reader runs under REPEATABLE_READ snapshot started before writer committed:
            // 1. The concurrently committed actual workout MUST NOT appear in the snapshot!
            assertThat(snapshot.plannedOccurrences()).hasSize(1);
            assertThat(snapshot.plannedOccurrences().getFirst().linkedExecutionId()).isNull();
            assertThat(snapshot.actualSessions()).isEmpty();
            assertThat(snapshot.volumeAndIntensity().workingSetsCompleted()).isEqualTo(0);
            assertThat(snapshot.volumeAndIntensity().knownVolumeKg()).isNull();

            // 2. Querying again in a new transaction snapshot sees the committed data
            WorkoutProgressSnapshot snapAfter = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);
            assertThat(snapAfter.actualSessions()).hasSize(1);
            assertThat(snapAfter.volumeAndIntensity().workingSetsCompleted()).isEqualTo(1);
            assertThat(snapAfter.volumeAndIntensity().knownVolumeKg()).isEqualByComparingTo(new BigDecimal("1000.00"));
            assertThat(snapAfter.sourceWatermark()).isNotEqualTo(snapshot.sourceWatermark());
        } finally {
            reset(accessPolicy);
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("16b. Snapshot consistency: caller in outer transaction still executes with REQUIRES_NEW and REPEATABLE_READ")
    void snapshotConsistency_callerOuterTransaction_stillExecutesWithRequiresNewRepeatableRead() {
        UUID student = createStudent("student_outer_tx");
        Instant t1 = Instant.parse("2026-11-02T02:00:00Z");
        createPlannedWorkout(student, t1, "SCHEDULED");
        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");

        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED);

        java.util.concurrent.atomic.AtomicReference<String> outerIsolation = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Long> outerBackendPid = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> innerIsolation = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Long> innerBackendPid = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Integer> innerSpringIsolation = new java.util.concurrent.atomic.AtomicReference<>();

        try {
            // Caller opens outer transaction (default READ COMMITTED in PostgreSQL)
            WorkoutProgressSnapshot snapshot = txTemplate.execute(status -> {
                outerIsolation.set(jdbc.queryForObject("SHOW transaction_isolation", String.class));
                outerBackendPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Long.class));

                doAnswer(invocation -> {
                    innerIsolation.set(jdbc.queryForObject("SHOW transaction_isolation", String.class));
                    innerBackendPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Long.class));
                    innerSpringIsolation.set(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                    return invocation.callRealMethod();
                }).when(accessPolicy).requireStudentMutation(eq(student), eq(student));

                // Aggregate query executes with REQUIRES_NEW and REPEATABLE_READ
                return query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);
            });

            assertThat(snapshot).isNotNull();
            assertThat(snapshot.plannedOccurrences()).hasSize(1);
            assertThat(snapshot.sourceWatermark()).isNotBlank();

            // Verify outer caller was in READ COMMITTED
            assertThat(outerIsolation.get()).isEqualTo("read committed");

            // Verify inner aggregation executed in REPEATABLE READ on an independent PostgreSQL connection
            assertThat(innerIsolation.get()).isEqualTo("repeatable read");
            assertThat(innerSpringIsolation.get()).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(innerBackendPid.get()).isNotNull();
            assertThat(outerBackendPid.get()).isNotNull();
            assertThat(innerBackendPid.get()).isNotEqualTo(outerBackendPid.get());
        } finally {
            reset(accessPolicy);
        }
    }

    @Test
    @DisplayName("17. Trainer with Plan-only permission: past due planned workouts are UNVERIFIABLE, NEVER marked MISSED")
    void trainerPlanOnly_pastWorkoutsAreUnverifiable_notMissed() {
        UUID student = createStudent("student_plan_only");
        UUID trainer = createTrainer("trainer_plan_only");
        Outcome outcome = accepted(student, trainer);

        // Grant WORKOUT_PLAN, but NO WORKOUT_HISTORY
        sharing.grantOrReplace(student, outcome.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, null, UUID.randomUUID());

        // Past planned workout on Nov 2
        Instant tPlanned = Instant.parse("2026-11-02T02:00:00Z");
        createPlannedWorkout(student, tPlanned, "SCHEDULED");

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(trainer, student, fromDate, toDate, zoneVn, asOf);

        AdherenceSummary adh = snapshot.adherence();
        // Since trainer has no actual history access, workout MUST NOT be marked MISSED!
        assertThat(adh.missedCount()).isEqualTo(0);
        assertThat(adh.unverifiableDueCount()).isEqualTo(1);
        assertThat(adh.rawCompletionRate()).isNull();
        assertThat(adh.onTimeAdherenceRate()).isNull();
        assertThat(adh.availabilityReason()).isEqualTo("ACTUAL_HISTORY_RESTRICTED");

        assertThat(snapshot.plannedOccurrences()).hasSize(1);
        PlannedOccurrenceFact fact = snapshot.plannedOccurrences().getFirst();
        assertThat(fact.evidenceStatus()).isEqualTo("RESTRICTED_OR_UNKNOWN");
        assertThat(fact.linkedExecutionId()).isNull();
    }

    @Test
    @DisplayName("18. Trainer with misaligned time scopes: partially restricted actuals mark rate null with partial reason")
    void trainerMisalignedTimeScopes_partiallyRestrictedReportsPartialReason() {
        UUID student = createStudent("student_misaligned");
        UUID trainer = createTrainer("trainer_misaligned");
        Outcome outcome = accepted(student, trainer);
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID variation = createVariation(student, "Row");

        // Plan scope: unbounded (all Nov 1..Nov 7)
        // Actual history scope: starts Nov 4
        Instant historyFrom = Instant.parse("2026-11-04T00:00:00Z");
        sharing.grantOrReplace(student, outcome.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, null, UUID.randomUUID());
        sharing.grantOrReplace(student, outcome.relationship().id(), DataScope.WORKOUT_HISTORY,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, historyFrom, null, null, null, UUID.randomUUID());

        // Planned workout 1 on Nov 2 (outside actual history scope)
        Instant tPlan1 = Instant.parse("2026-11-02T02:00:00Z");
        UUID pw1 = createPlannedWorkout(student, tPlan1, "SCHEDULED");

        // Planned workout 2 on Nov 5 (inside actual history scope)
        Instant tPlan2 = Instant.parse("2026-11-05T02:00:00Z");
        UUID pw2 = createPlannedWorkout(student, tPlan2, "SCHEDULED");

        // Student completes pw2 on Nov 5 (inside scope)
        UUID exec2 = createActualWorkout(student, pw2, tPlan2, tPlan2.plusSeconds(3600), "COMPLETED", new BigDecimal("7.5"));
        addExerciseAndSet(exec2, variation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("60.00"), kgUnit, null, 30);

        Instant asOf = Instant.parse("2026-11-06T00:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(trainer, student, fromDate, toDate, zoneVn, asOf);

        AdherenceSummary adh = snapshot.adherence();
        assertThat(adh.scheduledDueCount()).isEqualTo(2);
        assertThat(adh.completedDueCount()).isEqualTo(1);
        assertThat(adh.unverifiableDueCount()).isEqualTo(1);
        assertThat(adh.missedCount()).isEqualTo(0);
        // Rates MUST be null due to incomplete actual evidence!
        assertThat(adh.rawCompletionRate()).isNull();
        assertThat(adh.onTimeAdherenceRate()).isNull();
        assertThat(adh.availabilityReason()).isEqualTo("ACTUAL_HISTORY_PARTIALLY_RESTRICTED");

        // Verify planned facts evidence status
        assertThat(snapshot.plannedOccurrences()).hasSize(2);
        PlannedOccurrenceFact f1 = snapshot.plannedOccurrences().stream()
                .filter(f -> f.occurrenceId().equals(pw1)).findFirst().orElseThrow();
        assertThat(f1.evidenceStatus()).isEqualTo("RESTRICTED_OR_UNKNOWN");
        assertThat(f1.linkedExecutionId()).isNull();

        PlannedOccurrenceFact f2 = snapshot.plannedOccurrences().stream()
                .filter(f -> f.occurrenceId().equals(pw2)).findFirst().orElseThrow();
        assertThat(f2.evidenceStatus()).isEqualTo("VERIFIED");
        assertThat(f2.linkedExecutionId()).isEqualTo(exec2);
    }

    @Test
    @DisplayName("19. Cohort uniformity: linked actual outside window does not inflate actual metrics and coverage <= 1.0")
    void linkedActualOutsideWindow_doesNotInflateWindowActualMetrics_coverageNeverExceedsOne() {
        UUID student = createStudent("student_cohort_uniformity");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID variation = createVariation(student, "Overhead Press");

        // Planned workout is on Sunday Nov 7 (IN window Nov 1..Nov 7)
        Instant tPlan = Instant.parse("2026-11-07T02:00:00Z");
        UUID pw = createPlannedWorkout(student, tPlan, "SCHEDULED");

        // Student performs it on Monday Nov 8 (OUTSIDE window Nov 1..Nov 7)
        Instant tActual = Instant.parse("2026-11-08T02:00:00Z");
        UUID exec = createActualWorkout(student, pw, tActual, tActual.plusSeconds(3600), "COMPLETED", new BigDecimal("8.0"));
        // 5 working sets, 100 kg, 10 reps each = 5000 kg volume
        for (int i = 1; i <= 5; i++) {
            addExerciseAndSet(exec, variation, i, "WORKING", "COMPLETED", 10, new BigDecimal("100.00"), kgUnit, null, 30);
        }

        // Query window Nov 1..Nov 7 as of Nov 9
        Instant asOf = Instant.parse("2026-11-09T00:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        // 1. Planned occurrence fact is linked and marked COMPLETED (+1 day deviation)
        assertThat(snapshot.plannedOccurrences()).hasSize(1);
        PlannedOccurrenceFact fact = snapshot.plannedOccurrences().getFirst();
        assertThat(fact.linkedExecutionId()).isEqualTo(exec);
        assertThat(fact.linkedExecutionStatus()).isEqualTo("COMPLETED");
        assertThat(fact.scheduleDeviationDays()).isEqualTo(1);
        assertThat(snapshot.adherence().completedDueCount()).isEqualTo(1);
        assertThat(snapshot.adherence().completedOffScheduleCount()).isEqualTo(1);

        // 2. BUT the actual window metrics for Nov 1..Nov 7 MUST NOT include Nov 8 sets/volume!
        assertThat(snapshot.actualSessions()).isEmpty();
        VolumeAndIntensitySummary vol = snapshot.volumeAndIntensity();
        assertThat(vol.workingSetsCompleted()).isEqualTo(0);
        assertThat(vol.knownVolumeKg()).isNull();
        assertThat(vol.loadCoverageRate()).isNull();
        assertThat(vol.hasIncompleteVolumeCoverage()).isFalse();
    }

    @Test
    @DisplayName("20. Unknown, bodyweight and zero: distinguishes valid zero from missing load and checks reps coverage")
    void unknownBodyweightAndZero_distinguishesValidZeroAndChecksRepsCoverage() {
        UUID student = createStudent("student_load_semantics");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);

        UUID standardVariation = createVariation(student, "Barbell Bench");
        UUID bwVariation = createVariation(student, "Bodyweight Pull-Up");
        markVariationAsBodyweight(bwVariation);

        Instant t = Instant.parse("2026-11-03T02:00:00Z");
        UUID exec = createActualWorkout(student, null, t, t.plusSeconds(3600), "COMPLETED", new BigDecimal("8.0"));

        // Set 1: Confirmed bodyweight exercise with null load -> bodyweightSetsCompleted (NOT missing load!)
        addExerciseAndSet(exec, bwVariation, 1, "WORKING", "COMPLETED", 10, null, null, null, 30);

        // Set 2: Standard exercise with null load -> unknown / missing load (setsWithMissingLoad)
        addExerciseAndSet(exec, standardVariation, 2, "WORKING", "COMPLETED", 10, null, null, null, 30);

        // Set 3: Valid zero load (0 kg, 10 reps) -> calculable volume = 0.00 kg (setsWithZeroLoad)
        addExerciseAndSet(exec, standardVariation, 3, "WORKING", "COMPLETED", 10, BigDecimal.ZERO, kgUnit, null, 30);

        // Set 4: Missing reps (50 kg, null reps) -> NOT calculable (setsWithMissingReps)
        addExerciseAndSet(exec, standardVariation, 4, "WORKING", "COMPLETED", null, new BigDecimal("50.00"), kgUnit, null, 30);

        // Set 5: Standard calculable set (50 kg, 10 reps) -> calculable volume = 500.00 kg
        addExerciseAndSet(exec, standardVariation, 5, "WORKING", "COMPLETED", 10, new BigDecimal("50.00"), kgUnit, null, 30);

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        VolumeAndIntensitySummary vol = snapshot.volumeAndIntensity();
        assertThat(vol.workingSetsCompleted()).isEqualTo(5);
        assertThat(vol.bodyweightSetsCompleted()).isEqualTo(1);
        assertThat(vol.setsWithMissingLoadCount()).isEqualTo(1);
        assertThat(vol.setsWithZeroLoadCount()).isEqualTo(1);
        assertThat(vol.setsWithMissingRepsCount()).isEqualTo(1);
        assertThat(vol.setsWithCalculableVolumeCount()).isEqualTo(2); // Set 3 and Set 5
        // 2 calculable out of 5 working sets = 40.00%
        assertThat(vol.loadCoverageRate()).isEqualByComparingTo(new BigDecimal("0.4000"));
        assertThat(vol.hasIncompleteVolumeCoverage()).isTrue();
        assertThat(vol.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("21. Semantics asOf filtering: excludes set logs created after asOf and marks historical reconstruction status")
    void asOfSemantics_excludesSetLogsCreatedAfterAsOf_andReportsReconstructionStatus() {
        UUID student = createStudent("student_asof_sets");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID variation = createVariation(student, "Squat");

        // Session started at 10:00 VN (03:00 UTC)
        Instant start = Instant.parse("2026-11-03T03:00:00Z");
        // Session completed at 12:00 VN (05:00 UTC) with RPE 8.0
        Instant end = Instant.parse("2026-11-03T05:00:00Z");
        UUID exec = createActualWorkout(student, null, start, end, "COMPLETED", new BigDecimal("8.0"));

        // Set 1 logged at 10:30 VN (03:30 UTC) - before asOf
        Instant set1Time = Instant.parse("2026-11-03T03:30:00Z");
        addExerciseAndSetWithTimestamp(exec, variation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("80.00"), kgUnit, null, 40, set1Time);

        // Set 2 logged at 11:30 VN (04:30 UTC) - AFTER asOf
        Instant set2Time = Instant.parse("2026-11-03T04:30:00Z");
        addExerciseAndSetWithTimestamp(exec, variation, 2, "WORKING", "COMPLETED", 10, new BigDecimal("80.00"), kgUnit, null, 40, set2Time);

        // Query asOf = 11:00 VN (04:00 UTC)
        Instant asOf = Instant.parse("2026-11-03T04:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        assertThat(snapshot.actualSessions()).hasSize(1);
        ActualSessionFact fact = snapshot.actualSessions().getFirst();

        // At 11:00, session was still IN_PROGRESS (ended at 12:00), so RPE is not yet rated
        assertThat(fact.status()).isEqualTo("IN_PROGRESS");
        assertThat(fact.performedEndAt()).isNull();
        assertThat(fact.overallRpe()).isNull();
        assertThat(fact.historicalReconstructionStatus()).isEqualTo("HISTORICAL_RECONSTRUCTION_LIMITED");

        // Crucial: Set 2 created AFTER asOf MUST NOT be included! Only Set 1 is included!
        assertThat(fact.setsCompleted()).isEqualTo(1);
        assertThat(fact.workingSetsCompleted()).isEqualTo(1);
        assertThat(fact.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(snapshot.volumeAndIntensity().hasHistoricalReconstructionLimits()).isTrue();
    }

    @Test
    @DisplayName("22. Watermark conversion dependency: changing multiplier_to_base in measurement_units changes watermark")
    void watermarkConversionDependency_changingMultiplierChangesWatermark() {
        UUID student = createStudent("student_wm_unit_change");
        Short lbUnit = createUnit("LB_DYNAMIC", "lbd", "MASS", "KG", 0.45359237);
        UUID variation = createVariation(student, "Dumbbell Curl");

        Instant t = Instant.parse("2026-11-03T02:00:00Z");
        UUID exec = createActualWorkout(student, null, t, t.plusSeconds(1800), "COMPLETED", new BigDecimal("7.0"));
        addExerciseAndSet(exec, variation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("50.00"), lbUnit, null, 30);

        Instant asOf = Instant.parse("2026-11-05T00:00:00Z");
        WorkoutProgressSnapshot snap1 = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);
        String wm1 = snap1.sourceWatermark();

        // Update multiplier_to_base in fitness.measurement_units
        jdbc.update("UPDATE fitness.measurement_units SET multiplier_to_base = 0.45000000 WHERE id = ?", lbUnit);

        WorkoutProgressSnapshot snap2 = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);
        String wm2 = snap2.sourceWatermark();

        // Watermark and volume MUST change because conversion multiplier changed!
        assertThat(wm1).isNotEqualTo(wm2);
        assertThat(snap1.volumeAndIntensity().knownVolumeKg()).isNotEqualTo(snap2.volumeAndIntensity().knownVolumeKg());
    }

    @Test
    @DisplayName("24. Paired test: out-of-scope execution has ZERO side-channel leakage across 4 lifecycle states")
    void pairedTest_outOfScopeExecution_hasZeroSideChannelLeakage() {
        UUID student = createStudent("student_paired_leakage");
        UUID trainer = createTrainer("trainer_paired_leakage");
        Outcome outcome = accepted(student, trainer);
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID variation = createVariation(student, "Deadlift");

        // Trainer granted WORKOUT_PLAN unbounded (Nov 1..Nov 7),
        // but WORKOUT_HISTORY starting Nov 4 (actualHistoryFrom = Nov 4)
        Instant historyFrom = Instant.parse("2026-11-04T00:00:00Z");
        sharing.grantOrReplace(student, outcome.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, null, UUID.randomUUID());
        sharing.grantOrReplace(student, outcome.relationship().id(), DataScope.WORKOUT_HISTORY,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, historyFrom, null, null, null, UUID.randomUUID());

        // Planned workout 1 on Nov 2 (BEFORE actual history scope Nov 4 => unverified)
        Instant tPlanNov2 = Instant.parse("2026-11-02T02:00:00Z");
        UUID pwNov2 = createPlannedWorkout(student, tPlanNov2, "SCHEDULED");

        // Planned workout 2 on Nov 5 (INSIDE actual history scope Nov 4..Nov 7)
        Instant tPlanNov5 = Instant.parse("2026-11-05T02:00:00Z");
        UUID pwNov5 = createPlannedWorkout(student, tPlanNov5, "SCHEDULED");

        Instant asOf = Instant.parse("2026-11-06T00:00:00Z");

        // STATE 1: Baseline - NO execution outside trainer's scope
        WorkoutProgressSnapshot snap1 = query.aggregate(trainer, student, fromDate, toDate, zoneVn, asOf);
        assertThat(snap1.plannedOccurrences()).hasSize(2);
        PlannedOccurrenceFact fNov2_1 = snap1.plannedOccurrences().stream()
                .filter(p -> p.occurrenceId().equals(pwNov2)).findFirst().orElseThrow();
        assertThat(fNov2_1.evidenceStatus()).isEqualTo("RESTRICTED_OR_UNKNOWN");
        assertThat(fNov2_1.linkedExecutionId()).isNull();
        assertThat(snap1.adherence().unverifiableDueCount()).isEqualTo(1);
        assertThat(snap1.adherence().missedCount()).isEqualTo(1); // pwNov5 is inside scope and missed
        assertThat(snap1.actualSessions()).isEmpty();
        String wm1 = snap1.sourceWatermark();

        // STATE 2: Student creates linked execution on Nov 1 (OUTSIDE trainer scope) in IN_PROGRESS status
        Instant actualNov1 = Instant.parse("2026-11-01T02:00:00Z");
        UUID execNov1 = createActualWorkout(student, pwNov2, actualNov1, null, "IN_PROGRESS", null);
        addExerciseAndSet(execNov1, variation, 1, "WORKING", "COMPLETED", 5, new BigDecimal("140.00"), kgUnit, null, 45);

        WorkoutProgressSnapshot snap2 = query.aggregate(trainer, student, fromDate, toDate, zoneVn, asOf);
        // ZERO leakage: watermark, adherence counts, facts, and actual sessions MUST BE 100% IDENTICAL to State 1!
        assertThat(snap2.sourceWatermark()).isEqualTo(wm1);
        assertThat(snap2.adherence()).isEqualTo(snap1.adherence());
        assertThat(snap2.plannedOccurrences()).isEqualTo(snap1.plannedOccurrences());
        assertThat(snap2.actualSessions()).isEmpty();

        // STATE 3: Student completes execution on Nov 1 (still OUTSIDE trainer scope)
        jdbc.update("""
                UPDATE fitness.workout_session_logs
                SET status = 'COMPLETED', performed_end_at = ?, overall_rpe = 8.0, version = version + 1
                WHERE id = ?
                """, Timestamp.from(actualNov1.plusSeconds(3600)), execNov1);
        addExerciseAndSet(execNov1, variation, 2, "WORKING", "COMPLETED", 3, new BigDecimal("150.00"), kgUnit, null, 40);

        WorkoutProgressSnapshot snap3 = query.aggregate(trainer, student, fromDate, toDate, zoneVn, asOf);
        // ZERO leakage: response remains 100% IDENTICAL to State 1!
        assertThat(snap3.sourceWatermark()).isEqualTo(wm1);
        assertThat(snap3.adherence()).isEqualTo(snap1.adherence());
        assertThat(snap3.plannedOccurrences()).isEqualTo(snap1.plannedOccurrences());
        assertThat(snap3.actualSessions()).isEmpty();

        // STATE 4: Student creates another execution on Nov 1 (also OUTSIDE trainer scope)
        Instant actualNov1b = Instant.parse("2026-11-01T10:00:00Z");
        UUID execNov1b = createActualWorkout(student, null, actualNov1b, actualNov1b.plusSeconds(1800), "COMPLETED", new BigDecimal("6.5"));
        addExerciseAndSet(execNov1b, variation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("60.00"), kgUnit, null, 30);

        WorkoutProgressSnapshot snap4 = query.aggregate(trainer, student, fromDate, toDate, zoneVn, asOf);
        // ZERO leakage: response remains 100% IDENTICAL to State 1!
        assertThat(snap4.sourceWatermark()).isEqualTo(wm1);
        assertThat(snap4.adherence()).isEqualTo(snap1.adherence());
        assertThat(snap4.plannedOccurrences()).isEqualTo(snap1.plannedOccurrences());
        assertThat(snap4.actualSessions()).isEmpty();
    }

    @Test
    @DisplayName("25. Semantics asOf: set created before asOf but completed after asOf is NOT counted as completed at asOf")
    void asOfSemantics_setCompletedAfterAsOf_isNotCountedAsCompletedAtAsOf() {
        UUID student = createStudent("student_set_completed_after");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID variation = createVariation(student, "Bench Press");

        // Session started at 09:00 UTC and ended at 11:00 UTC
        Instant sessionStart = Instant.parse("2026-11-03T09:00:00Z");
        Instant sessionEnd = Instant.parse("2026-11-03T11:00:00Z");
        UUID exec = createActualWorkout(student, null, sessionStart, sessionEnd, "COMPLETED", new BigDecimal("7.5"));

        // Query asOf = 10:00 UTC
        Instant asOf = Instant.parse("2026-11-03T10:00:00Z");

        // Set 1: created at 09:15 UTC, completed at 09:20 UTC (BEFORE asOf)
        Instant set1Created = Instant.parse("2026-11-03T09:15:00Z");
        Instant set1Completed = Instant.parse("2026-11-03T09:20:00Z");
        addExerciseAndSetWithTimestamps(exec, variation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("80.00"),
                kgUnit, null, 30, set1Completed, set1Created, set1Completed);

        // Set 2: created at 09:30 UTC, but completed at 10:15 UTC (AFTER asOf)
        Instant set2Created = Instant.parse("2026-11-03T09:30:00Z");
        Instant set2Completed = Instant.parse("2026-11-03T10:15:00Z");
        addExerciseAndSetWithTimestamps(exec, variation, 2, "WORKING", "COMPLETED", 10, new BigDecimal("80.00"),
                kgUnit, null, 30, set2Completed, set2Created, set2Completed);

        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        assertThat(snapshot.actualSessions()).hasSize(1);
        ActualSessionFact fact = snapshot.actualSessions().getFirst();

        // At 10:00 UTC:
        // Set 2 was NOT yet completed at asOf! Only Set 1 is completed!
        assertThat(fact.setsCompleted()).isEqualTo(1);
        assertThat(fact.workingSetsCompleted()).isEqualTo(1);
        assertThat(fact.repsCompleted()).isEqualTo(10);
        assertThat(fact.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("800.00"));

        VolumeAndIntensitySummary vol = snapshot.volumeAndIntensity();
        assertThat(vol.workingSetsCompleted()).isEqualTo(1);
        assertThat(vol.totalRepsCompleted()).isEqualTo(10);
        assertThat(vol.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("800.00"));
    }

    @Test
    @DisplayName("26. Semantics asOf: set modified after asOf triggers HISTORICAL_RECONSTRUCTION_LIMITED and hasHistoricalReconstructionLimits")
    void asOfSemantics_setModifiedAfterAsOf_reportsLimitedReconstructionStatus() {
        UUID student = createStudent("student_set_modified_after");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID variation = createVariation(student, "Incline Dumbbell");

        // Session completed before asOf: 08:00 to 09:00 UTC
        Instant sessionStart = Instant.parse("2026-11-03T08:00:00Z");
        Instant sessionEnd = Instant.parse("2026-11-03T09:00:00Z");
        UUID exec = createActualWorkout(student, null, sessionStart, sessionEnd, "COMPLETED", new BigDecimal("7.0"));

        // Query asOf = 10:00 UTC
        Instant asOf = Instant.parse("2026-11-03T10:00:00Z");

        // Set created at 08:30 UTC, completed at 08:35 UTC, but modified at 12:00 UTC (AFTER asOf)
        Instant setCreated = Instant.parse("2026-11-03T08:30:00Z");
        Instant setCompleted = Instant.parse("2026-11-03T08:35:00Z");
        Instant setUpdated = Instant.parse("2026-11-03T12:00:00Z");
        addExerciseAndSetWithTimestamps(exec, variation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("30.00"),
                kgUnit, new BigDecimal("8.0"), 30, setCompleted, setCreated, setUpdated);

        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        assertThat(snapshot.actualSessions()).hasSize(1);
        ActualSessionFact fact = snapshot.actualSessions().getFirst();

        // Modified after asOf => cannot claim HISTORICAL_SNAPSHOT_EXACT
        assertThat(fact.historicalReconstructionStatus()).isEqualTo("HISTORICAL_RECONSTRUCTION_LIMITED");
        // Direct metric checks: unverifiable values at asOf MUST NOT be used or converted to zero!
        assertThat(fact.knownVolumeKg()).isNull();
        assertThat(fact.repsCompleted()).isEqualTo(0);
        assertThat(fact.setDurationSeconds()).isNull();
        assertThat(fact.setsCompleted()).isEqualTo(1);
        assertThat(fact.workingSetsCompleted()).isEqualTo(1);

        VolumeAndIntensitySummary vol = snapshot.volumeAndIntensity();
        assertThat(vol.hasHistoricalReconstructionLimits()).isTrue();
        assertThat(vol.workingSetsCompleted()).isEqualTo(1);
        assertThat(vol.setsWithCalculableVolumeCount()).isEqualTo(0);
        assertThat(vol.setsWithMissingLoadCount()).isEqualTo(1);
        assertThat(vol.setsWithMissingRepsCount()).isEqualTo(1);
        assertThat(vol.knownVolumeKg()).isNull();
        assertThat(vol.loadCoverageRate()).isEqualByComparingTo(BigDecimal.ZERO.setScale(4));
        assertThat(vol.hasIncompleteVolumeCoverage()).isTrue();
        assertThat(vol.totalRepsCompleted()).isEqualTo(0);
        assertThat(vol.totalSetDurationSeconds()).isNull();
        // Since set was modified after asOf, its RPE must not be included in averageSetRpe
        assertThat(vol.averageSetRpe()).isNull();

        // Subcase B: Session with two sets: Set 1 unmodified before asOf, Set 2 modified after asOf
        UUID student2 = createStudent("student_set_modified_partial");
        UUID exec2 = createActualWorkout(student2, null, sessionStart, sessionEnd, "COMPLETED", new BigDecimal("7.5"));
        // Set 1: 50 kg x 10 reps = 500 kg, 30s duration, created and updated before asOf
        Instant set1Created = Instant.parse("2026-11-03T08:15:00Z");
        Instant set1Completed = Instant.parse("2026-11-03T08:20:00Z");
        addExerciseAndSetWithTimestamps(exec2, variation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("50.00"),
                kgUnit, new BigDecimal("7.0"), 30, set1Completed, set1Created, set1Completed);
        // Set 2: 80 kg x 12 reps, 45s duration, created before asOf, updated AFTER asOf (12:00 UTC)
        addExerciseAndSetWithTimestamps(exec2, variation, 2, "WORKING", "COMPLETED", 12, new BigDecimal("80.00"),
                kgUnit, new BigDecimal("8.5"), 45, setCompleted, setCreated, setUpdated);

        WorkoutProgressSnapshot snapshot2 = query.aggregate(student2, student2, fromDate, toDate, zoneVn, asOf);
        assertThat(snapshot2.actualSessions()).hasSize(1);
        ActualSessionFact fact2 = snapshot2.actualSessions().getFirst();

        assertThat(fact2.historicalReconstructionStatus()).isEqualTo("HISTORICAL_RECONSTRUCTION_LIMITED");
        // Only Set 1 is included in verified volume, reps, and duration
        assertThat(fact2.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(fact2.repsCompleted()).isEqualTo(10);
        assertThat(fact2.setDurationSeconds()).isEqualTo(30);
        assertThat(fact2.setsCompleted()).isEqualTo(2);
        assertThat(fact2.workingSetsCompleted()).isEqualTo(2);

        VolumeAndIntensitySummary vol2 = snapshot2.volumeAndIntensity();
        assertThat(vol2.hasHistoricalReconstructionLimits()).isTrue();
        assertThat(vol2.workingSetsCompleted()).isEqualTo(2);
        assertThat(vol2.setsWithCalculableVolumeCount()).isEqualTo(1);
        assertThat(vol2.setsWithMissingLoadCount()).isEqualTo(1);
        assertThat(vol2.setsWithMissingRepsCount()).isEqualTo(1);
        assertThat(vol2.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(vol2.loadCoverageRate()).isEqualByComparingTo(new BigDecimal("0.5000"));
        assertThat(vol2.hasIncompleteVolumeCoverage()).isTrue();
        assertThat(vol2.totalRepsCompleted()).isEqualTo(10);
        assertThat(vol2.totalSetDurationSeconds()).isEqualTo(30);
        // Set 1 RPE (7.0) is included, Set 2 RPE (8.5) is excluded
        assertThat(vol2.averageSetRpe()).isEqualByComparingTo(new BigDecimal("7.00"));
    }

    @Test
    @DisplayName("27. Semantics asOf: session ongoing at asOf nullifies overall RPE and triggers limited reconstruction")
    void asOfSemantics_sessionOngoingAtAsOf_nullifiesOverallRpe_andSetsLimitedReconstruction() {
        UUID student = createStudent("student_session_ongoing");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID variation = createVariation(student, "Leg Press");

        // Session started at 09:00 UTC, ended at 11:30 UTC with overall RPE 8.5
        Instant sessionStart = Instant.parse("2026-11-03T09:00:00Z");
        Instant sessionEnd = Instant.parse("2026-11-03T11:30:00Z");
        UUID exec = createActualWorkout(student, null, sessionStart, sessionEnd, "COMPLETED", new BigDecimal("8.5"));

        // Set 1 completed at 09:30 UTC
        Instant setCompleted = Instant.parse("2026-11-03T09:30:00Z");
        addExerciseAndSetWithTimestamps(exec, variation, 1, "WORKING", "COMPLETED", 12, new BigDecimal("200.00"),
                kgUnit, new BigDecimal("8.0"), 45, setCompleted, sessionStart, setCompleted);

        // Query asOf = 10:00 UTC (while session was still ongoing)
        Instant asOf = Instant.parse("2026-11-03T10:00:00Z");
        WorkoutProgressSnapshot snapshot = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);

        assertThat(snapshot.actualSessions()).hasSize(1);
        ActualSessionFact fact = snapshot.actualSessions().getFirst();

        assertThat(fact.status()).isEqualTo("IN_PROGRESS");
        assertThat(fact.performedEndAt()).isNull();
        assertThat(fact.overallRpe()).isNull();
        assertThat(fact.historicalReconstructionStatus()).isEqualTo("HISTORICAL_RECONSTRUCTION_LIMITED");

        VolumeAndIntensitySummary vol = snapshot.volumeAndIntensity();
        assertThat(vol.hasHistoricalReconstructionLimits()).isTrue();
        assertThat(vol.averageSessionRpe()).isNull();
    }

    @Test
    @DisplayName("28. Valid zero volume: session with valid 0 kg returns 0.00, missing load returns null, coverage is accurate")
    void validZeroVolume_returnsZeroNotHistoricalNull_andAccuratelyReflectsCoverage() {
        UUID student = createStudent("student_zero_volume");
        Short kgUnit = createUnit("KG", "kg", "MASS", "KG", 1.0);
        UUID variation = createVariation(student, "Bodyweight Bench");

        Instant asOf = Instant.parse("2026-11-06T00:00:00Z");

        // PART 1: A session with ONLY valid 0 kg sets (0.00 kg load, 10 reps)
        Instant t1 = Instant.parse("2026-11-02T02:00:00Z");
        UUID exec1 = createActualWorkout(student, null, t1, t1.plusSeconds(3600), "COMPLETED", new BigDecimal("7.0"));
        addExerciseAndSet(exec1, variation, 1, "WORKING", "COMPLETED", 10, BigDecimal.ZERO, kgUnit, null, 30);
        addExerciseAndSet(exec1, variation, 2, "WORKING", "COMPLETED", 8, BigDecimal.ZERO, kgUnit, null, 30);

        WorkoutProgressSnapshot snap1 = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);
        assertThat(snap1.actualSessions()).hasSize(1);
        ActualSessionFact fact1 = snap1.actualSessions().getFirst();

        // Valid zero load with known reps MUST yield 0.00 kg, NOT null!
        assertThat(fact1.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(fact1.workingSetsCompleted()).isEqualTo(2);

        VolumeAndIntensitySummary vol1 = snap1.volumeAndIntensity();
        assertThat(vol1.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(vol1.setsWithZeroLoadCount()).isEqualTo(2);
        assertThat(vol1.setsWithMissingLoadCount()).isEqualTo(0);
        assertThat(vol1.setsWithCalculableVolumeCount()).isEqualTo(2);
        assertThat(vol1.loadCoverageRate()).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(vol1.hasIncompleteVolumeCoverage()).isFalse();

        // PART 2: Add Session 2 with missing load (null load, not bodyweight)
        Instant t2 = Instant.parse("2026-11-04T02:00:00Z");
        UUID exec2 = createActualWorkout(student, null, t2, t2.plusSeconds(3600), "COMPLETED", new BigDecimal("8.0"));
        // 1 working set with null load (missing) and 10 reps
        addExerciseAndSet(exec2, variation, 1, "WORKING", "COMPLETED", 10, null, null, null, 30);

        WorkoutProgressSnapshot snap2 = query.aggregate(student, student, fromDate, toDate, zoneVn, asOf);
        assertThat(snap2.actualSessions()).hasSize(2);

        ActualSessionFact sessionFact1 = snap2.actualSessions().stream()
                .filter(s -> s.executionId().equals(exec1)).findFirst().orElseThrow();
        ActualSessionFact sessionFact2 = snap2.actualSessions().stream()
                .filter(s -> s.executionId().equals(exec2)).findFirst().orElseThrow();

        assertThat(sessionFact1.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("0.00"));
        // Missing load session volume MUST be null, NOT 0.00!
        assertThat(sessionFact2.knownVolumeKg()).isNull();

        VolumeAndIntensitySummary vol2 = snap2.volumeAndIntensity();
        // Window volume is knownVolumeKg = 0.00 (from session 1), but coverage drops to 2/3 = 66.67%
        assertThat(vol2.workingSetsCompleted()).isEqualTo(3);
        assertThat(vol2.setsWithZeroLoadCount()).isEqualTo(2);
        assertThat(vol2.setsWithMissingLoadCount()).isEqualTo(1);
        assertThat(vol2.setsWithCalculableVolumeCount()).isEqualTo(2);
        assertThat(vol2.knownVolumeKg()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(vol2.loadCoverageRate()).isEqualByComparingTo(new BigDecimal("0.6667"));
        assertThat(vol2.hasIncompleteVolumeCoverage()).isTrue();

        // PART 3: A student with ONLY missing load session has null volume
        UUID student3 = createStudent("student_missing_volume_only");
        UUID exec3 = createActualWorkout(student3, null, t2, t2.plusSeconds(3600), "COMPLETED", new BigDecimal("8.0"));
        addExerciseAndSet(exec3, variation, 1, "WORKING", "COMPLETED", 10, null, null, null, 30);

        WorkoutProgressSnapshot snap3 = query.aggregate(student3, student3, fromDate, toDate, zoneVn, asOf);
        assertThat(snap3.actualSessions()).hasSize(1);
        assertThat(snap3.actualSessions().getFirst().knownVolumeKg()).isNull();
        assertThat(snap3.volumeAndIntensity().knownVolumeKg()).isNull();
        assertThat(snap3.volumeAndIntensity().setsWithCalculableVolumeCount()).isEqualTo(0);
        assertThat(snap3.volumeAndIntensity().loadCoverageRate()).isEqualByComparingTo(BigDecimal.ZERO.setScale(4));
        assertThat(snap3.volumeAndIntensity().hasIncompleteVolumeCoverage()).isTrue();
    }

    private UUID addExerciseAndSetWithTimestamps(
            UUID executionId, UUID variationId, int seq, String setType,
            String completionStatus, Integer reps, BigDecimal load, Short loadUnitId,
            BigDecimal rpe, Integer durationSeconds, Instant completedAt, Instant createdAt, Instant updatedAt
    ) {
        UUID exerciseLogId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.exercise_logs
                (id, workout_session_log_id, exercise_variation_id, actual_exercise_variation_id, sequence_number)
                VALUES (?, ?, ?, ?, ?)
                """, exerciseLogId, executionId, variationId, variationId, seq);

        UUID setLogId = UUID.randomUUID();
        UUID clientSetId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.set_logs
                (id, exercise_log_id, client_set_id, set_number, set_type, completion_status,
                 repetitions, load_value, load_unit_id, rpe, duration_seconds, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::fitness.set_type, ?::fitness.set_completion_status,
                 ?, ?, ?, ?, ?, ?, ?, ?)
                """, setLogId, exerciseLogId, clientSetId, seq, setType, completionStatus,
                reps, load, loadUnitId, rpe, durationSeconds,
                completedAt != null ? Timestamp.from(completedAt) : null,
                Timestamp.from(createdAt),
                Timestamp.from(updatedAt));

        return setLogId;
    }

    // Helper setup methods
    private UUID createStudent(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone)
                VALUES (?, ?, 'hash', ?, 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh')
                """, id, id + "@example.com", name);
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code='STUDENT'
                """, id, id);
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        return id;
    }

    private UUID createTrainer(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone)
                VALUES (?, ?, 'hash', ?, 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh')
                """, id, id + "@example.com", name);
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code='TRAINER'
                """, id, id);
        jdbc.update("""
                INSERT INTO fitness.trainer_profiles(user_id, verification_status, verified_at, is_accepting_students, is_active)
                VALUES (?, 'VERIFIED', clock_timestamp(), true, true)
                """, id);
        return id;
    }

    private Outcome accepted(UUID student, UUID trainer) {
        Outcome pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        return lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT", 0, null, UUID.randomUUID());
    }

    private Short createUnit(String code, String symbol, String dimension, String baseUnitCode, Double multiplier) {
        return jdbc.queryForObject("""
                INSERT INTO fitness.measurement_units(code, symbol, dimension, base_unit_code, multiplier_to_base, offset_to_base)
                VALUES (?, ?, ?, ?, ?, 0)
                ON CONFLICT (code) DO UPDATE SET symbol = EXCLUDED.symbol, multiplier_to_base = EXCLUDED.multiplier_to_base
                RETURNING id
                """, Short.class, code, symbol, dimension, baseUnitCode, multiplier != null ? BigDecimal.valueOf(multiplier) : null);
    }

    private UUID createVariation(UUID creator, String name) {
        UUID exercise = UUID.randomUUID();
        UUID variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id, code, name, admin_status, created_by) VALUES (?, ?, ?, 'ACTIVE', ?)",
                exercise, "ex-" + exercise, name, creator);
        jdbc.update("INSERT INTO fitness.exercise_variations(id, exercise_id, code, name, is_active) VALUES (?, ?, ?, ?, true)",
                variation, exercise, "var-" + variation, name + " var");
        return variation;
    }

    private UUID createPlannedWorkout(UUID student, Instant plannedStart, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id, student_id, planned_start_at, planned_end_at, original_planned_start_at, status, version, created_by)
                VALUES (?, ?, ?, ?, ?, ?::fitness.planned_workout_status, 1, ?)
                """, id, student, Timestamp.from(plannedStart), Timestamp.from(plannedStart.plusSeconds(3600)),
                Timestamp.from(plannedStart), status, student);
        return id;
    }

    private UUID createRescheduledReplacement(UUID student, Instant newPlannedStart, UUID predecessorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id, student_id, planned_start_at, planned_end_at, original_planned_start_at, status, rescheduled_from_id, version, created_by)
                VALUES (?, ?, ?, ?, ?, 'SCHEDULED'::fitness.planned_workout_status, ?, 1, ?)
                """, id, student, Timestamp.from(newPlannedStart), Timestamp.from(newPlannedStart.plusSeconds(3600)),
                Timestamp.from(newPlannedStart), predecessorId, student);
        return id;
    }

    private UUID createActualWorkout(UUID student, UUID plannedId, Instant start, Instant end, String status, BigDecimal rpe) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_session_logs
                (id, student_id, planned_workout_id, performed_start_at, performed_end_at, status, overall_rpe, notes, logged_by)
                VALUES (?, ?, ?, ?, ?, ?::fitness.actual_workout_status, ?, 'Actual log notes', ?)
                """, id, student, plannedId, Timestamp.from(start), end != null ? Timestamp.from(end) : null,
                status, rpe, student);
        return id;
    }

    private UUID addExerciseAndSet(
            UUID executionId, UUID variationId, int seq, String setType,
            String completionStatus, Integer reps, BigDecimal load, Short loadUnitId,
            BigDecimal rpe, Integer durationSeconds
    ) {
        UUID exerciseLogId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.exercise_logs
                (id, workout_session_log_id, exercise_variation_id, actual_exercise_variation_id, sequence_number)
                VALUES (?, ?, ?, ?, ?)
                """, exerciseLogId, executionId, variationId, variationId, seq);

        UUID setLogId = UUID.randomUUID();
        UUID clientSetId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.set_logs
                (id, exercise_log_id, client_set_id, set_number, set_type, completion_status, repetitions, load_value, load_unit_id, rpe, duration_seconds)
                VALUES (?, ?, ?, ?, ?::fitness.set_type, ?::fitness.set_completion_status, ?, ?, ?, ?, ?)
                """, setLogId, exerciseLogId, clientSetId, seq, setType, completionStatus, reps, load, loadUnitId, rpe, durationSeconds);

        return setLogId;
    }

    private void markVariationAsBodyweight(UUID variationId) {
        jdbc.update("""
                INSERT INTO fitness.equipment(code, name) VALUES ('BODYWEIGHT', 'Bodyweight')
                ON CONFLICT (code) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO fitness.exercise_equipment(exercise_variation_id, equipment_id, requirement)
                SELECT ?, id, 'REQUIRED' FROM fitness.equipment WHERE code='BODYWEIGHT'
                ON CONFLICT DO NOTHING
                """, variationId);
    }

    private UUID addExerciseAndSetWithTimestamp(
            UUID executionId, UUID variationId, int seq, String setType,
            String completionStatus, Integer reps, BigDecimal load, Short loadUnitId,
            BigDecimal rpe, Integer durationSeconds, Instant createdAt
    ) {
        UUID exerciseLogId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.exercise_logs
                (id, workout_session_log_id, exercise_variation_id, actual_exercise_variation_id, sequence_number)
                VALUES (?, ?, ?, ?, ?)
                """, exerciseLogId, executionId, variationId, variationId, seq);

        UUID setLogId = UUID.randomUUID();
        UUID clientSetId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.set_logs
                (id, exercise_log_id, client_set_id, set_number, set_type, completion_status, repetitions, load_value, load_unit_id, rpe, duration_seconds, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::fitness.set_type, ?::fitness.set_completion_status, ?, ?, ?, ?, ?, ?, ?)
                """, setLogId, exerciseLogId, clientSetId, seq, setType, completionStatus, reps, load, loadUnitId, rpe, durationSeconds, Timestamp.from(createdAt), Timestamp.from(createdAt));

        return setLogId;
    }
}
