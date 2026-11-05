package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Outcome;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.*;
import com.fitnesscoaching.platform.modules.progress.application.service.ProgressEngineService;
import com.fitnesscoaching.platform.modules.progress.domain.ProgressError;
import com.fitnesscoaching.platform.modules.progress.domain.ProgressFailure;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ProgressEngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("progress_engine_test_db")
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
    private ProgressEngineQuery query;

    @Autowired
    private CoachingLifecycleUseCase lifecycle;

    @Autowired
    private CoachingSharingUseCase sharing;

    private final ZoneId zoneVn = ZoneId.of("Asia/Ho_Chi_Minh");
    private final LocalDate fromDate = LocalDate.of(2026, 11, 1);
    private final LocalDate toDate = LocalDate.of(2026, 11, 7);
    private final Instant asOfFixed = Instant.parse("2026-11-08T00:00:00Z");

    @Test
    @DisplayName("1. Both sources have data: returns unified snapshot with facts, calculated deltas, and composite watermark")
    void bothSourcesHaveData_returnsUnifiedSnapshotWithCalculatedDeltasAndCompositeWatermark() {
        UUID student = createStudent("student_both_data");
        Short kgUnit = createUnit("ENG_KG", "kg", "MASS", "ENG_KG", 1.0);
        Integer weightMetric = createMetric("ENG_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_ENG_1", "Manual", "MANUAL");
        UUID exerciseVariation = createVariation(student, "Barbell Bench Press");

        // Workout data in window
        Instant tWorkout = Instant.parse("2026-11-03T03:00:00Z");
        UUID planned = createPlannedWorkout(student, tWorkout, "COMPLETED");
        UUID exec = createActualWorkout(student, planned, tWorkout, tWorkout.plusSeconds(3600), "COMPLETED", new BigDecimal("8.0"));
        addExerciseAndSet(exec, exerciseVariation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("80.00"), kgUnit, null, 40);

        // Measurement data in window (2 observations: baseline and latest)
        Instant tMeas1 = Instant.parse("2026-11-02T02:00:00Z");
        Instant tMeas2 = Instant.parse("2026-11-06T02:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("75.0"), kgUnit, tMeas1, manualSource, null, "VALID", tMeas1);
        createMeasurement(student, weightMetric, new BigDecimal("74.2"), kgUnit, tMeas2, manualSource, null, "VALID", tMeas2);

        ProgressSnapshot snapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfFixed);

        assertThat(snapshot.studentId()).isEqualTo(student);
        assertThat(snapshot.workout().availability()).isEqualTo(SourceAvailability.AVAILABLE);
        assertThat(snapshot.workout().actualSessions()).hasSize(1);
        assertThat(snapshot.workout().volumeAndIntensity().knownVolumeKg()).isEqualByComparingTo("800.00");

        assertThat(snapshot.measurement().availability()).isEqualTo(SourceAvailability.AVAILABLE);
        assertThat(snapshot.measurement().observations()).hasSize(2);
        assertThat(snapshot.measurement().metricDeltas()).hasSize(1);

        MetricTrendDelta delta = snapshot.measurement().metricDeltas().get(0);
        assertThat(delta.metricCode()).isEqualTo("ENG_WEIGHT");
        assertThat(delta.baselineValue()).isEqualByComparingTo("75.0");
        assertThat(delta.latestValue()).isEqualByComparingTo("74.2");
        assertThat(delta.deltaValue()).isEqualByComparingTo("-0.8");
        assertThat(delta.trendDirection()).isEqualTo(TrendDirection.DECREASED);

        assertThat(snapshot.compositeWatermark()).isNotNull().hasSize(64);
        assertThat(snapshot.historicalLimits().hasHistoricalLimits()).isFalse();
    }

    @Test
    @DisplayName("2. Only workout has data: measurement section reports NO_DATA with empty observations and deltas")
    void onlyWorkoutHasData_measurementReportsNoData() {
        UUID student = createStudent("student_only_workout");
        Short kgUnit = createUnit("W_KG", "kg", "MASS", "W_KG", 1.0);
        UUID exerciseVariation = createVariation(student, "Squat");

        Instant tWorkout = Instant.parse("2026-11-04T04:00:00Z");
        UUID planned = createPlannedWorkout(student, tWorkout, "COMPLETED");
        UUID exec = createActualWorkout(student, planned, tWorkout, tWorkout.plusSeconds(3600), "COMPLETED", new BigDecimal("7.0"));
        addExerciseAndSet(exec, exerciseVariation, 1, "WORKING", "COMPLETED", 8, new BigDecimal("100.00"), kgUnit, null, 45);

        ProgressSnapshot snapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfFixed);

        assertThat(snapshot.workout().availability()).isEqualTo(SourceAvailability.AVAILABLE);
        assertThat(snapshot.measurement().availability()).isEqualTo(SourceAvailability.NO_DATA);
        assertThat(snapshot.measurement().observations()).isEmpty();
        assertThat(snapshot.measurement().metricDeltas()).isEmpty();
        assertThat(snapshot.compositeWatermark()).isNotNull();
    }

    @Test
    @DisplayName("3. Only measurement has data: workout section reports NO_DATA with empty sessions")
    void onlyMeasurementHasData_workoutReportsNoData() {
        UUID student = createStudent("student_only_measurement");
        Short kgUnit = createUnit("M_KG", "kg", "MASS", "M_KG", 1.0);
        Integer weightMetric = createMetric("M_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_M_1", "Manual", "MANUAL");

        Instant tMeas = Instant.parse("2026-11-03T02:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("68.5"), kgUnit, tMeas, manualSource, null, "VALID", tMeas);

        ProgressSnapshot snapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfFixed);

        assertThat(snapshot.workout().availability()).isEqualTo(SourceAvailability.NO_DATA);
        assertThat(snapshot.workout().actualSessions()).isEmpty();
        assertThat(snapshot.measurement().availability()).isEqualTo(SourceAvailability.AVAILABLE);
        assertThat(snapshot.measurement().observations()).hasSize(1);
        assertThat(snapshot.measurement().metricDeltas().get(0).trendDirection()).isEqualTo(TrendDirection.INSUFFICIENT_DATA);
    }

    @Test
    @DisplayName("4. Both sources empty: valid actor receives snapshot with NO_DATA on both sections without error")
    void bothSourcesEmpty_validActor_returnsEmptySectionsWithoutError() {
        UUID student = createStudent("student_empty_sources");

        ProgressSnapshot snapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfFixed);

        assertThat(snapshot.workout().availability()).isEqualTo(SourceAvailability.NO_DATA);
        assertThat(snapshot.measurement().availability()).isEqualTo(SourceAvailability.NO_DATA);
        assertThat(snapshot.compositeWatermark()).isNotNull();
    }

    @Test
    @DisplayName("5. Trainer with Workout access but denied Body Metrics receives partial snapshot without leaking Measurement data")
    void trainerWithWorkoutAccess_butDeniedMeasurement_returnsPartialSnapshot() {
        UUID student = createStudent("student_partial_auth");
        UUID trainer = createTrainer("trainer_partial_auth");
        Outcome active = accepted(student, trainer);

        // Grant WORKOUT_HISTORY only (do NOT grant BODY_METRICS)
        sharing.grantOrReplace(student, active.relationship().id(), DataScope.WORKOUT_HISTORY,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, null, UUID.randomUUID());

        Short kgUnit = createUnit("PART_KG", "kg", "MASS", "PART_KG", 1.0);
        Integer weightMetric = createMetric("PART_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_PART", "Manual", "MANUAL");
        UUID exerciseVariation = createVariation(student, "Pull Up");

        // Add both workout and measurement data
        Instant tWorkout = Instant.parse("2026-11-04T05:00:00Z");
        UUID planned = createPlannedWorkout(student, tWorkout, "COMPLETED");
        UUID exec = createActualWorkout(student, planned, tWorkout, tWorkout.plusSeconds(3600), "COMPLETED", new BigDecimal("7.5"));
        addExerciseAndSet(exec, exerciseVariation, 1, "WORKING", "COMPLETED", 10, new BigDecimal("0.00"), kgUnit, null, 30);

        Instant tMeas = Instant.parse("2026-11-04T06:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("82.0"), kgUnit, tMeas, manualSource, null, "VALID", tMeas);

        ProgressSnapshot snapshot = query.query(trainer, student, fromDate, toDate, zoneVn, asOfFixed);

        assertThat(snapshot.workout().availability()).isEqualTo(SourceAvailability.AVAILABLE);
        assertThat(snapshot.workout().actualSessions()).hasSize(1);

        assertThat(snapshot.measurement().availability()).isEqualTo(SourceAvailability.RESTRICTED);
        assertThat(snapshot.measurement().observations()).isEmpty();
        assertThat(snapshot.measurement().metricDeltas()).isEmpty();
        assertThat(snapshot.measurement().sourceWatermark()).isNull(); // strictly hidden
        assertThat(snapshot.compositeWatermark()).isNotNull();
    }

    @Test
    @DisplayName("6. Stranger actor denied on all sources throws PROGRESS_ACCESS_DENIED 403")
    void strangerActor_deniedOnAllSources_throwsProgressAccessDenied() {
        UUID student = createStudent("student_stranger_test");
        UUID stranger = createStudent("stranger_actor_test");

        assertThatThrownBy(() -> query.query(stranger, student, fromDate, toDate, zoneVn, asOfFixed))
                .isInstanceOf(ProgressFailure.class)
                .satisfies(e -> assertThat(((ProgressFailure) e).error()).isEqualTo(ProgressError.PROGRESS_ACCESS_DENIED));
    }

    @Test
    @DisplayName("7. Non-existent student throws STUDENT_NOT_FOUND 404")
    void nonExistentStudent_throwsStudentNotFound() {
        UUID actor = createStudent("actor_valid");
        UUID nonExistentStudent = UUID.randomUUID();

        assertThatThrownBy(() -> query.query(actor, nonExistentStudent, fromDate, toDate, zoneVn, asOfFixed))
                .isInstanceOf(ProgressFailure.class)
                .satisfies(e -> assertThat(((ProgressFailure) e).error()).isEqualTo(ProgressError.STUDENT_NOT_FOUND));
    }

    @Test
    @DisplayName("8. Paired test: Out-of-scope mutation does not affect trainer response or composite watermark")
    void pairedTest_outOfScopeDataChange_doesNotAffectTrainerResponseOrCompositeWatermark() {
        UUID student = createStudent("student_paired_engine");
        UUID trainer = createTrainer("trainer_paired_engine");
        Outcome active = accepted(student, trainer);

        Instant scopeFrom = Instant.parse("2026-11-04T00:00:00Z");
        Instant scopeUntil = Instant.parse("2026-11-06T23:59:59Z");

        sharing.grantOrReplace(student, active.relationship().id(), DataScope.WORKOUT_HISTORY,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, scopeFrom, scopeUntil, null, null, UUID.randomUUID());
        sharing.grantOrReplace(student, active.relationship().id(), DataScope.BODY_METRICS,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, scopeFrom, scopeUntil, null, null, UUID.randomUUID());

        Short kgUnit = createUnit("PAIR_KG", "kg", "MASS", "PAIR_KG", 1.0);
        Integer weightMetric = createMetric("PAIR_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_PAIR", "Manual", "MANUAL");

        // Baseline: In-scope measurement on Nov 5
        Instant tInScope = Instant.parse("2026-11-05T08:00:00Z");
        UUID inScopeMeas = createMeasurement(student, weightMetric, new BigDecimal("75.0"), kgUnit, tInScope, manualSource, null, "VALID", tInScope);

        ProgressSnapshot baselineSnapshot = query.query(trainer, student, fromDate, toDate, zoneVn, asOfFixed);
        assertThat(baselineSnapshot.measurement().observations()).hasSize(1);
        String baselineWatermark = baselineSnapshot.compositeWatermark();

        // Mutation: Student adds an out-of-scope measurement on Nov 2 (before scopeFrom)
        Instant tOutOfScope = Instant.parse("2026-11-02T08:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("76.0"), kgUnit, tOutOfScope, manualSource, null, "VALID", tOutOfScope);

        ProgressSnapshot mutatedSnapshot = query.query(trainer, student, fromDate, toDate, zoneVn, asOfFixed);
        assertThat(mutatedSnapshot.measurement().observations()).hasSize(1);
        assertThat(mutatedSnapshot.measurement().observations().get(0).id()).isEqualTo(inScopeMeas);
        assertThat(mutatedSnapshot.compositeWatermark()).isEqualTo(baselineWatermark); // Watermark must NOT change!
    }

    @Test
    @DisplayName("9. Continuity: detects PROLONGED_INACTIVITY and flags RETURN_AFTER_100_DAYS_V1 when last workout >= 100 days")
    void continuity_prolongedInactivityFlagged_whenLastWorkout100DaysAgo() {
        UUID student = createStudent("student_100d_test");
        Short kgUnit = createUnit("CONT_KG", "kg", "MASS", "CONT_KG", 1.0);
        UUID exerciseVariation = createVariation(student, "Deadlift");

        // Workout was 120 days before asOf
        Instant tOldWorkout = asOfFixed.minus(120, ChronoUnit.DAYS);
        UUID planned = createPlannedWorkout(student, tOldWorkout, "COMPLETED");
        UUID exec = createActualWorkout(student, planned, tOldWorkout, tOldWorkout.plusSeconds(3600), "COMPLETED", new BigDecimal("8.5"));
        addExerciseAndSet(exec, exerciseVariation, 1, "WORKING", "COMPLETED", 5, new BigDecimal("120.00"), kgUnit, null, 60);

        ProgressSnapshot snapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfFixed);

        var continuity = snapshot.continuity();
        assertThat(continuity.state()).isEqualTo(ContinuityState.PROLONGED_INACTIVITY);
        assertThat(continuity.daysSinceLastWorkout()).isGreaterThanOrEqualTo(120);
        assertThat(continuity.lastWorkoutExecutionId()).isEqualTo(exec);
        assertThat(continuity.safetyFlags()).contains(ProgressEngineService.SAFETY_FLAG_RETURN_100_DAYS);
    }

    @Test
    @DisplayName("10. Historical limits: correctly summarized when measurement has post-asOf backfill or modification")
    void historicalLimits_correctlySummarized_whenMeasurementHasPostAsOfModification() {
        UUID student = createStudent("student_limits_test");
        Short kgUnit = createUnit("LIM_KG", "kg", "MASS", "LIM_KG", 1.0);
        Integer weightMetric = createMetric("LIM_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_LIM", "Manual", "MANUAL");

        Instant asOfCutoff = Instant.parse("2026-11-04T12:00:00Z");

        // 1. Clean measurement before asOf
        Instant t1 = Instant.parse("2026-11-02T08:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("80.0"), kgUnit, t1, manualSource, null, "VALID", t1);

        ProgressSnapshot cleanSnapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfCutoff);
        assertThat(cleanSnapshot.historicalLimits().hasHistoricalLimits()).isFalse();

        // 2. Add backfill measurement: measured before asOf (Nov 3) but created after asOf (Nov 6)
        Instant tBackfilled = Instant.parse("2026-11-03T08:00:00Z");
        Instant createdLate = Instant.parse("2026-11-06T10:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("80.5"), kgUnit, tBackfilled, manualSource, null, "VALID", createdLate);

        ProgressSnapshot limitedSnapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfCutoff);
        assertThat(limitedSnapshot.historicalLimits().hasHistoricalLimits()).isTrue();
        assertThat(limitedSnapshot.historicalLimits().measurementLimits()).isTrue();
        assertThat(limitedSnapshot.historicalLimits().explanation()).contains("Measurement data");
    }

    @Test
    @DisplayName("11. Continuity: recent workout observed returns RECENT_ACTIVITY_OBSERVED with facts and unclassified consistency")
    void continuity_recentActivityObserved_withoutUnapprovedSpecialistLabel() {
        UUID student = createStudent("student_recent_act");
        Short kgUnit = createUnit("REC_KG", "kg", "MASS", "REC_KG", 1.0);
        UUID exerciseVariation = createVariation(student, "Overhead Press");

        // Workout was 3 days before asOf
        Instant tRecentWorkout = asOfFixed.minus(3, ChronoUnit.DAYS);
        UUID planned = createPlannedWorkout(student, tRecentWorkout, "COMPLETED");
        UUID exec = createActualWorkout(student, planned, tRecentWorkout, tRecentWorkout.plusSeconds(3600), "COMPLETED", new BigDecimal("7.0"));
        addExerciseAndSet(exec, exerciseVariation, 1, "WORKING", "COMPLETED", 8, new BigDecimal("50.00"), kgUnit, null, 30);

        ProgressSnapshot snapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfFixed);

        var continuity = snapshot.continuity();
        assertThat(continuity.state()).isEqualTo(ContinuityState.RECENT_ACTIVITY_OBSERVED);
        assertThat(continuity.daysSinceLastWorkout()).isEqualTo(3);
        assertThat(continuity.lastWorkoutExecutionId()).isEqualTo(exec);
        assertThat(continuity.safetyFlags()).isEmpty();
        assertThat(continuity.rationale()).contains("consistency unclassified without approved specialist threshold");
    }

    @Test
    @DisplayName("12. Continuity: beginner with 0 months and onboarding completed is CONFIRMED_NEW")
    void continuity_confirmedNew_whenBeginnerWithZeroMonthsAndOnboarding() {
        UUID student = createStudent("student_conf_new");
        jdbc.update("""
                UPDATE fitness.student_profiles
                SET training_experience_level = 'BEGINNER',
                    training_experience_months = 0.0,
                    onboarding_completed_at = now()
                WHERE user_id = ?
                """, student);

        ProgressSnapshot snapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfFixed);

        var continuity = snapshot.continuity();
        assertThat(continuity.state()).isEqualTo(ContinuityState.CONFIRMED_NEW);
        assertThat(continuity.daysSinceLastWorkout()).isNull();
        assertThat(continuity.safetyFlags()).isEmpty();
    }

    @Test
    @DisplayName("13. Continuity: beginner with non-zero or missing months returns UNKNOWN_HISTORY")
    void continuity_unknownHistory_whenBeginnerLacksZeroMonthsEvidence() {
        UUID student = createStudent("student_unconf_new");
        jdbc.update("""
                UPDATE fitness.student_profiles
                SET training_experience_level = 'BEGINNER',
                    training_experience_months = 6.0,
                    onboarding_completed_at = now()
                WHERE user_id = ?
                """, student);

        ProgressSnapshot snapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfFixed);

        var continuity = snapshot.continuity();
        assertThat(continuity.state()).isEqualTo(ContinuityState.UNKNOWN_HISTORY);
        assertThat(continuity.rationale()).contains("lacks explicit confirmed beginner evidence");
    }

    @Test
    @DisplayName("14. Metric deltas: converts compatible units (e.g. LB to KG) and accurately calculates delta")
    void metricDeltas_unitConversionAcrossCompatibleUnits() {
        UUID student = createStudent("student_unit_conv");
        Short kgUnit = createUnit("U_KG", "kg", "MASS", "U_KG", 1.0);
        Short lbUnit = createUnit("U_LB", "lb", "MASS", "U_KG", 0.45359237);
        Integer weightMetric = createMetric("U_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_U", "Manual", "MANUAL");

        // Baseline: 70.0 kg on Nov 2
        Instant t1 = Instant.parse("2026-11-02T04:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("70.00"), kgUnit, t1, manualSource, null, "VALID", t1);

        // Latest: 154.3236 lb (~70.0 kg) on Nov 5
        Instant t2 = Instant.parse("2026-11-05T04:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("154.3236"), lbUnit, t2, manualSource, null, "VALID", t2);

        ProgressSnapshot snapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfFixed);

        MetricTrendDelta delta = snapshot.measurement().metricDeltas().get(0);
        assertThat(delta.metricCode()).isEqualTo("U_WEIGHT");
        assertThat(delta.trendDirection()).isEqualTo(TrendDirection.STABLE);
        assertThat(delta.deltaValue()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("15. Metric deltas: observations sharing identical timestamp yield INSUFFICIENT_DATA with null delta")
    void metricDeltas_identicalTimestamps_yieldInsufficientData() {
        UUID student = createStudent("student_incompat_units");
        Short kgUnit = createUnit("INC_KG", "kg", "MASS", "INC_KG", 1.0);
        Integer testMetric = createMetric("INC_TEST", "Test Metric", kgUnit);
        Short manualSource = createSource("SRC_INC", "Manual", "MANUAL");

        Instant t1 = Instant.parse("2026-11-02T04:00:00Z");
        createMeasurement(student, testMetric, new BigDecimal("70.0"), kgUnit, t1, manualSource, null, "VALID", t1);
        createMeasurement(student, testMetric, new BigDecimal("72.0"), kgUnit, t1, manualSource, null, "VALID", t1);

        ProgressSnapshot snapshot = query.query(student, student, fromDate, toDate, zoneVn, asOfFixed);

        MetricTrendDelta delta = snapshot.measurement().metricDeltas().get(0);
        assertThat(delta.trendDirection()).isEqualTo(TrendDirection.INSUFFICIENT_DATA);
        assertThat(delta.deltaValue()).isNull();
        assertThat(delta.percentageChange()).isNull();
    }

    @Test
    @DisplayName("16. Continuity: Trainer with historyUntil before asOf receives RESTRICTED_HISTORY")
    void continuity_trainerWithHistoryUntilBeforeAsOf_returnsRestrictedHistory() {
        UUID student = createStudent("student_trainer_scope");
        UUID trainer = createTrainer("trainer_trainer_scope");
        Outcome active = accepted(student, trainer);

        // Grant history that stops 2 days before asOf
        Instant scopeFrom = asOfFixed.minus(30, ChronoUnit.DAYS);
        Instant scopeUntil = asOfFixed.minus(2, ChronoUnit.DAYS);

        sharing.grantOrReplace(student, active.relationship().id(), DataScope.WORKOUT_HISTORY,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, scopeFrom, scopeUntil, null, null, UUID.randomUUID());

        ProgressSnapshot snapshot = query.query(trainer, student, fromDate, toDate, zoneVn, asOfFixed);

        var continuity = snapshot.continuity();
        assertThat(continuity.state()).isEqualTo(ContinuityState.RESTRICTED_HISTORY);
        assertThat(continuity.rationale()).contains("Permitted workout history scope ends before asOf");
    }

    // --- Helper fixture methods ---

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

    private Integer createMetric(String code, String displayName, Short defaultUnitId) {
        return jdbc.queryForObject("""
                INSERT INTO fitness.metric_definitions(code, display_name, collection_type, default_unit_id)
                VALUES (?, ?, 'DIRECT', ?)
                ON CONFLICT (code) DO UPDATE SET display_name = EXCLUDED.display_name
                RETURNING id
                """, Integer.class, code, displayName, defaultUnitId);
    }

    private Short createSource(String code, String name, String kind) {
        return jdbc.queryForObject("""
                INSERT INTO fitness.measurement_sources(code, name, source_kind)
                VALUES (?, ?, ?)
                ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Short.class, code, name, kind);
    }

    private UUID createMeasurement(
            UUID studentId, Integer metricId, BigDecimal numericValue, Short unitId,
            Instant measuredAt, Short sourceId, Short methodId, String status, Instant createdAt
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.measurements
                (id, student_id, metric_definition_id, numeric_value, unit_id, measured_at,
                 source_id, method_id, entered_by, validation_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::fitness.measurement_validation_status, ?)
                """, id, studentId, metricId, numericValue, unitId, Timestamp.from(measuredAt),
                sourceId, methodId, studentId, status, Timestamp.from(createdAt));
        return id;
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

    private UUID createActualWorkout(UUID student, UUID plannedId, Instant start, Instant end, String status, BigDecimal rpe) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_session_logs
                (id, student_id, planned_workout_id, performed_start_at, performed_end_at, status,
                 overall_rpe, notes, logged_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::fitness.actual_workout_status, ?, 'Actual log notes', ?, ?, ?)
                """, id, student, plannedId, Timestamp.from(start), end != null ? Timestamp.from(end) : null,
                status, rpe, student, Timestamp.from(start), Timestamp.from(start));
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
        Timestamp loggedAt = jdbc.queryForObject("SELECT performed_start_at FROM fitness.workout_session_logs WHERE id=?",
                Timestamp.class, executionId);
        jdbc.update("""
                INSERT INTO fitness.set_logs
                (id, exercise_log_id, client_set_id, set_number, set_type, completion_status, repetitions,
                 load_value, load_unit_id, rpe, duration_seconds, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::fitness.set_type, ?::fitness.set_completion_status, ?, ?, ?, ?, ?, ?, ?)
                """, setLogId, exerciseLogId, clientSetId, seq, setType, completionStatus, reps,
                load, loadUnitId, rpe, durationSeconds, loggedAt, loggedAt);

        return setLogId;
    }
}
