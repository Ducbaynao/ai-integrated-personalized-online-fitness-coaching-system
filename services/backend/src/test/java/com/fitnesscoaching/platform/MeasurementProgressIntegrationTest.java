package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Outcome;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementProgressQuery;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementProgressQuery.*;
import com.fitnesscoaching.platform.modules.measurement.application.service.MeasurementAccessPolicy;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementError;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
class MeasurementProgressIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("measurement_progress_test_db")
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
    private MeasurementProgressQuery query;

    @MockitoSpyBean
    private MeasurementAccessPolicy accessPolicy;

    @Autowired
    private CoachingLifecycleUseCase lifecycle;

    @Autowired
    private CoachingSharingUseCase sharing;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ZoneId zoneVn = ZoneId.of("Asia/Ho_Chi_Minh");
    private final LocalDate fromDate = LocalDate.of(2026, 11, 1);
    private final LocalDate toDate = LocalDate.of(2026, 11, 7);
    private final Instant asOfFixed = Instant.parse("2026-11-08T00:00:00Z");

    @Test
    @DisplayName("1. Returns full historical series in chronological order, not just latest observation")
    void returnsFullHistorySeries_notJustLatestObservation() {
        UUID student = createStudent("student_full_series");
        Short kgUnit = createUnit("M_KG", "kg", "MASS", "M_KG", 1.0);
        Integer weightMetric = createMetric("SERIES_WEIGHT", "Body Weight", kgUnit);
        Short manualSource = createSource("SRC_MANUAL", "Manual Entry", "MANUAL");

        Instant t1 = Instant.parse("2026-11-02T01:00:00Z"); // 08:00 VN
        Instant t2 = Instant.parse("2026-11-04T01:00:00Z"); // 08:00 VN
        Instant t3 = Instant.parse("2026-11-06T01:00:00Z"); // 08:00 VN

        UUID m1 = createMeasurement(student, weightMetric, new BigDecimal("74.50000000"), kgUnit, t1, manualSource, null, "VALID", t1);
        UUID m2 = createMeasurement(student, weightMetric, new BigDecimal("74.00000000"), kgUnit, t2, manualSource, null, "VALID", t2);
        UUID m3 = createMeasurement(student, weightMetric, new BigDecimal("73.50000000"), kgUnit, t3, manualSource, null, "VALID", t3);

        MeasurementProgressSnapshot snapshot = query.query(student, student, "SERIES_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);

        assertThat(snapshot.studentId()).isEqualTo(student);
        assertThat(snapshot.summary().totalObservationsCount()).isEqualTo(3);
        assertThat(snapshot.summary().distinctDaysCount()).isEqualTo(3);
        assertThat(snapshot.summary().distinctMetricsCount()).isEqualTo(1);
        assertThat(snapshot.summary().availabilityReason()).isNull();

        List<MeasurementObservationFact> obs = snapshot.observations();
        assertThat(obs).hasSize(3);
        assertThat(obs.get(0).id()).isEqualTo(m1);
        assertThat(obs.get(0).numericValue()).isEqualByComparingTo("74.50000000");
        assertThat(obs.get(0).measuredLocalDate()).isEqualTo(LocalDate.of(2026, 11, 2));

        assertThat(obs.get(1).id()).isEqualTo(m2);
        assertThat(obs.get(1).numericValue()).isEqualByComparingTo("74.00000000");
        assertThat(obs.get(1).measuredLocalDate()).isEqualTo(LocalDate.of(2026, 11, 4));

        assertThat(obs.get(2).id()).isEqualTo(m3);
        assertThat(obs.get(2).numericValue()).isEqualByComparingTo("73.50000000");
        assertThat(obs.get(2).measuredLocalDate()).isEqualTo(LocalDate.of(2026, 11, 6));

        assertThat(snapshot.sourceWatermark()).isNotBlank();
    }

    @Test
    @DisplayName("2. Filters by Student, metric codes, date window, and timezone")
    void filtersByStudent_metricCodes_dateWindow_andTimezone() {
        UUID studentA = createStudent("student_filter_a");
        UUID studentB = createStudent("student_filter_b");
        Short kgUnit = createUnit("F_KG", "kg", "MASS", "F_KG", 1.0);
        Short cmUnit = createUnit("F_CM", "cm", "LENGTH", "F_CM", 1.0);
        Integer weightMetric = createMetric("F_WEIGHT", "Weight", kgUnit);
        Integer waistMetric = createMetric("F_WAIST", "Waist", cmUnit);
        Short manualSource = createSource("SRC_FILTER", "Manual", "MANUAL");

        Instant insideWindow = Instant.parse("2026-11-03T02:00:00Z");
        Instant beforeWindow = Instant.parse("2026-10-31T15:00:00Z"); // Oct 31
        Instant afterWindow = Instant.parse("2026-11-08T02:00:00Z");  // Nov 8

        // Student A measurements
        UUID aWeightInside = createMeasurement(studentA, weightMetric, new BigDecimal("70.0"), kgUnit, insideWindow, manualSource, null, "VALID", insideWindow);
        UUID aWaistInside = createMeasurement(studentA, waistMetric, new BigDecimal("82.0"), cmUnit, insideWindow, manualSource, null, "VALID", insideWindow);
        createMeasurement(studentA, weightMetric, new BigDecimal("69.5"), kgUnit, beforeWindow, manualSource, null, "VALID", beforeWindow);
        createMeasurement(studentA, weightMetric, new BigDecimal("70.5"), kgUnit, afterWindow, manualSource, null, "VALID", afterWindow);

        // Student B measurement inside window
        createMeasurement(studentB, weightMetric, new BigDecimal("85.0"), kgUnit, insideWindow, manualSource, null, "VALID", insideWindow);

        // Query Student A for WEIGHT only
        MeasurementProgressSnapshot weightOnly = query.query(studentA, studentA, Set.of("F_WEIGHT"), fromDate, toDate, zoneVn, asOfFixed);
        assertThat(weightOnly.observations()).hasSize(1);
        assertThat(weightOnly.observations().get(0).id()).isEqualTo(aWeightInside);
        assertThat(weightOnly.summary().distinctMetricsCount()).isEqualTo(1);

        // Query Student A for all metrics (null set)
        MeasurementProgressSnapshot allMetrics = query.query(studentA, studentA, (Set<String>) null, fromDate, toDate, zoneVn, asOfFixed);
        assertThat(allMetrics.observations()).hasSize(2);
        assertThat(allMetrics.observations().stream().map(MeasurementObservationFact::id))
                .containsExactlyInAnyOrder(aWeightInside, aWaistInside);
        assertThat(allMetrics.summary().distinctMetricsCount()).isEqualTo(2);

        // Timezone test: 2026-11-01 18:00 UTC = 2026-11-02 01:00 VN
        Instant borderTime = Instant.parse("2026-11-01T18:00:00Z");
        UUID borderMeasurement = createMeasurement(studentA, weightMetric, new BigDecimal("71.0"), kgUnit, borderTime, manualSource, null, "VALID", borderTime);
        MeasurementProgressSnapshot borderSnapshot = query.query(studentA, studentA, Set.of("F_WEIGHT"), fromDate, toDate, zoneVn, asOfFixed);
        MeasurementObservationFact borderFact = borderSnapshot.observations().stream()
                .filter(o -> o.id().equals(borderMeasurement)).findFirst().orElseThrow();
        assertThat(borderFact.measuredLocalDate()).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    @DisplayName("3. Excludes non-VALID, superseded, and erased tombstone records")
    void excludesNonValid_superseded_andErasedRecords() {
        UUID student = createStudent("student_lifecycle_filters");
        Short kgUnit = createUnit("L_KG", "kg", "MASS", "L_KG", 1.0);
        Integer weightMetric = createMetric("L_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_LIFECYCLE", "Manual", "MANUAL");

        Instant tValid = Instant.parse("2026-11-02T02:00:00Z");
        Instant tPending = Instant.parse("2026-11-03T02:00:00Z");
        Instant tSuspect = Instant.parse("2026-11-04T02:00:00Z");
        Instant tSuperseded = Instant.parse("2026-11-05T02:00:00Z");
        Instant tErased = Instant.parse("2026-11-06T02:00:00Z");

        // 1. Valid observation
        UUID mValid = createMeasurement(student, weightMetric, new BigDecimal("72.0"), kgUnit, tValid, manualSource, null, "VALID", tValid);

        // 2. Pending validation
        createMeasurement(student, weightMetric, new BigDecimal("72.1"), kgUnit, tPending, manualSource, null, "PENDING", tPending);

        // 3. Suspect validation
        createMeasurement(student, weightMetric, new BigDecimal("72.2"), kgUnit, tSuspect, manualSource, null, "SUSPECT", tSuspect);

        // 4. Superseded observation: mOld superseded by mNew
        UUID mNew = createMeasurement(student, weightMetric, new BigDecimal("72.4"), kgUnit, tSuperseded.plusSeconds(3600), manualSource, null, "VALID", tSuperseded.plusSeconds(3600));
        UUID mOld = createMeasurement(student, weightMetric, new BigDecimal("72.3"), kgUnit, tSuperseded, manualSource, null, "VALID", tSuperseded);
        jdbc.update("UPDATE fitness.measurements SET superseded_by_id=? WHERE id=?", mNew, mOld);

        // 5. Erased tombstone
        UUID mErased = createMeasurement(student, weightMetric, new BigDecimal("72.5"), kgUnit, tErased, manualSource, null, "VALID", tErased);
        UUID erasureRequest = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.account_erasure_requests(id, user_id, command_key, status_key_hash, target_at)
                VALUES (?, ?, ?, md5(random()::text || clock_timestamp()::text) || md5(random()::text || clock_timestamp()::text), clock_timestamp() + interval '24 hours')
                """, erasureRequest, student, UUID.randomUUID());
        jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?", student);
        jdbc.update("""
                UPDATE fitness.measurements
                SET numeric_value=NULL, unit_id=NULL, erased_at=clock_timestamp(), erasure_request_id=?
                WHERE id=?
                """, erasureRequest, mErased);
        jdbc.update("UPDATE fitness.users SET status='ACTIVE' WHERE id=?", student); // Restore user status for querying

        MeasurementProgressSnapshot snapshot = query.query(student, student, "L_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);

        assertThat(snapshot.observations()).hasSize(2);
        assertThat(snapshot.observations().stream().map(MeasurementObservationFact::id))
                .containsExactly(mValid, mNew);
    }

    @Test
    @DisplayName("4. Preserves valid zero value and unknown values remain null without default substitution")
    void preservesValidZeroValue_andUnknownValuesRemainNull() {
        UUID student = createStudent("student_zero_value");
        Short kgUnit = createUnit("Z_KG", "kg", "MASS", "Z_KG", 1.0);
        Integer weightChangeMetric = createMetric("WEIGHT_CHANGE", "Weight Change", kgUnit);
        Short manualSource = createSource("SRC_ZERO", "Manual", "MANUAL");

        Instant t1 = Instant.parse("2026-11-03T03:00:00Z");
        // Valid 0 numeric reading (e.g. 0.00 kg change)
        UUID mZero = createMeasurement(student, weightChangeMetric, BigDecimal.ZERO, kgUnit, t1, manualSource, null, "VALID", t1);

        MeasurementProgressSnapshot snapshot = query.query(student, student, "WEIGHT_CHANGE", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snapshot.observations()).hasSize(1);
        MeasurementObservationFact fact = snapshot.observations().get(0);
        assertThat(fact.id()).isEqualTo(mZero);
        assertThat(fact.numericValue()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fact.textValue()).isNull();
        assertThat(fact.booleanValue()).isNull();
    }

    @Test
    @DisplayName("5. Preserves unit, provenance, and quality metadata accurately")
    void preservesUnit_provenance_andQualityMetadata() {
        UUID student = createStudent("student_metadata");
        Short kgUnit = createUnit("META_KG", "kg", "MASS", "META_KG", 1.0);
        Integer weightMetric = createMetric("META_WEIGHT", "Weight", kgUnit);
        Short smartScaleSource = createSource("SMART_SCALE_PROV", "Smart Scale", "DEVICE");
        Short bioMethod = createMethod("BIO_IMPEDANCE", "Bioelectrical Impedance");

        Instant t1 = Instant.parse("2026-11-03T04:00:00Z");
        UUID batchId = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.measurement_batches(id, student_id, measured_at, source_id) VALUES (?, ?, ?, ?)",
                batchId, student, Timestamp.from(t1), smartScaleSource);

        UUID measId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.measurements
                (id, student_id, measurement_batch_id, metric_definition_id, numeric_value, unit_id,
                 measured_at, source_id, method_id, entered_by, external_record_id,
                 confidence_score, quality_score, validation_status, notes, created_at)
                VALUES (?, ?, ?, ?, 75.25000000, ?, ?, ?, ?, ?, 'ext-rec-12345',
                        95.5000, 88.0000, 'VALID', 'Morning empty stomach', ?)
                """, measId, student, batchId, weightMetric, kgUnit, Timestamp.from(t1),
                smartScaleSource, bioMethod, student, Timestamp.from(t1));

        MeasurementProgressSnapshot snapshot = query.query(student, student, "META_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snapshot.observations()).hasSize(1);
        MeasurementObservationFact fact = snapshot.observations().get(0);

        assertThat(fact.id()).isEqualTo(measId);
        assertThat(fact.metricCode()).isEqualTo("META_WEIGHT");
        assertThat(fact.metricDisplayName()).isEqualTo("Weight");
        assertThat(fact.numericValue()).isEqualByComparingTo("75.25000000");
        assertThat(fact.unitCode()).isEqualTo("META_KG");
        assertThat(fact.unitSymbol()).isEqualTo("kg");
        assertThat(fact.measurementBatchId()).isEqualTo(batchId);
        assertThat(fact.sourceCode()).isEqualTo("SMART_SCALE_PROV");
        assertThat(fact.sourceKind()).isEqualTo("DEVICE");
        assertThat(fact.methodCode()).isEqualTo("BIO_IMPEDANCE");
        assertThat(fact.enteredBy()).isEqualTo(student);
        assertThat(fact.externalRecordId()).isEqualTo("ext-rec-12345");
        assertThat(fact.confidenceScore()).isEqualByComparingTo("95.5000");
        assertThat(fact.qualityScore()).isEqualByComparingTo("88.0000");
        assertThat(fact.validationStatus()).isEqualTo("VALID");
    }

    @Test
    @DisplayName("6. Actor authority: rejects unauthorized actor with 403 and non-existent student with 404")
    void actorAuthority_rejectsUnauthorizedActor() {
        UUID student = createStudent("student_auth_test");
        UUID stranger = createStudent("stranger_actor");
        UUID nonExistentStudent = UUID.randomUUID();

        // 1. Stranger student querying another student's measurements -> 403
        assertThatThrownBy(() -> query.query(stranger, student, fromDate, toDate, zoneVn, asOfFixed))
                .isInstanceOf(MeasurementFailure.class)
                .satisfies(e -> assertThat(((MeasurementFailure) e).error()).isEqualTo(MeasurementError.MEASUREMENT_ACCESS_DENIED));

        // 2. Non-existent student -> 404
        assertThatThrownBy(() -> query.query(student, nonExistentStudent, fromDate, toDate, zoneVn, asOfFixed))
                .isInstanceOf(MeasurementFailure.class)
                .satisfies(e -> assertThat(((MeasurementFailure) e).error()).isEqualTo(MeasurementError.MEASUREMENT_NOT_FOUND));

        // 3. Trainer without BODY_METRICS permission -> 403
        UUID trainer = createTrainer("trainer_no_permission");
        accepted(student, trainer); // Coaching relationship established, but BODY_METRICS not granted
        assertThatThrownBy(() -> query.query(trainer, student, fromDate, toDate, zoneVn, asOfFixed))
                .isInstanceOf(MeasurementFailure.class)
                .satisfies(e -> assertThat(((MeasurementFailure) e).error()).isEqualTo(MeasurementError.MEASUREMENT_ACCESS_DENIED));
    }

    @Test
    @DisplayName("7. Trainer time scope clips data and reports partial restriction reason")
    void trainerTimeScope_clipsDataAndDoesNotExposeOutOfScopeMeasurements() {
        UUID student = createStudent("student_time_scope");
        UUID trainer = createTrainer("trainer_time_scope");
        Short kgUnit = createUnit("T_KG", "kg", "MASS", "T_KG", 1.0);
        Integer weightMetric = createMetric("T_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_TIME_SCOPE", "Manual", "MANUAL");

        Outcome active = accepted(student, trainer);

        // Grant BODY_METRICS permission starting from Nov 4 (00:00 UTC)
        Instant grantFrom = Instant.parse("2026-11-04T00:00:00Z");
        sharing.grantOrReplace(student, active.relationship().id(), DataScope.BODY_METRICS,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, grantFrom, null, null, null, UUID.randomUUID());

        // Measurements: Nov 2 (out of scope for trainer), Nov 5 (in scope for trainer)
        Instant tOutOfScope = Instant.parse("2026-11-02T02:00:00Z");
        Instant tInScope = Instant.parse("2026-11-05T02:00:00Z");

        createMeasurement(student, weightMetric, new BigDecimal("76.0"), kgUnit, tOutOfScope, manualSource, null, "VALID", tOutOfScope);
        UUID mInScope = createMeasurement(student, weightMetric, new BigDecimal("75.5"), kgUnit, tInScope, manualSource, null, "VALID", tInScope);

        // Student queries own data -> sees both observations
        MeasurementProgressSnapshot studentSnapshot = query.query(student, student, "T_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(studentSnapshot.observations()).hasSize(2);
        assertThat(studentSnapshot.summary().isTimeRestricted()).isFalse();

        // Trainer queries student data -> only sees in-scope observation on Nov 5
        MeasurementProgressSnapshot trainerSnapshot = query.query(trainer, student, "T_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(trainerSnapshot.observations()).hasSize(1);
        assertThat(trainerSnapshot.observations().get(0).id()).isEqualTo(mInScope);
        assertThat(trainerSnapshot.summary().isTimeRestricted()).isTrue();
        assertThat(trainerSnapshot.summary().availabilityReason()).isEqualTo("MEASUREMENT_HISTORY_PARTIALLY_RESTRICTED");
    }

    @Test
    @DisplayName("8. Paired test: Out-of-scope measurements mutation does not affect trainer response and watermark in verified scenarios")
    void pairedTest_outOfScopeDataChange_hasZeroSideChannelLeakageOnResponseAndWatermark() {
        UUID student = createStudent("student_paired_test");
        UUID trainer = createTrainer("trainer_paired_test");
        Short kgUnit = createUnit("P_KG", "kg", "MASS", "P_KG", 1.0);
        Integer weightMetric = createMetric("P_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_PAIRED", "Manual", "MANUAL");

        Outcome active = accepted(student, trainer);

        // Trainer granted only Nov 4 to Nov 6
        Instant scopeFrom = Instant.parse("2026-11-04T00:00:00Z");
        Instant scopeUntil = Instant.parse("2026-11-06T23:59:59Z");
        sharing.grantOrReplace(student, active.relationship().id(), DataScope.BODY_METRICS,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, scopeFrom, scopeUntil, null, null, UUID.randomUUID());

        // Baseline: 1 in-scope measurement on Nov 5
        Instant tInScope = Instant.parse("2026-11-05T08:00:00Z");
        UUID inScopeMeas = createMeasurement(student, weightMetric, new BigDecimal("77.0"), kgUnit, tInScope, manualSource, null, "VALID", tInScope);

        // Snapshot 1: Baseline for trainer
        MeasurementProgressSnapshot snap1 = query.query(trainer, student, "P_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snap1.observations()).hasSize(1);
        assertThat(snap1.observations().get(0).id()).isEqualTo(inScopeMeas);
        String baselineWatermark = snap1.sourceWatermark();

        // Mutation 1: Student adds an out-of-scope measurement on Nov 2 (before scopeFrom)
        Instant tOutEarly = Instant.parse("2026-11-02T08:00:00Z");
        UUID outEarly = createMeasurement(student, weightMetric, new BigDecimal("78.0"), kgUnit, tOutEarly, manualSource, null, "VALID", tOutEarly);

        MeasurementProgressSnapshot snap2 = query.query(trainer, student, "P_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snap2.observations()).hasSize(1);
        assertThat(snap2.observations().get(0).id()).isEqualTo(inScopeMeas);
        assertThat(snap2.sourceWatermark()).isEqualTo(baselineWatermark); // Watermark must NOT change!

        // Mutation 2: Student adds an out-of-scope measurement on Nov 7 (after scopeUntil)
        Instant tOutLate = Instant.parse("2026-11-07T08:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("76.5"), kgUnit, tOutLate, manualSource, null, "VALID", tOutLate);

        MeasurementProgressSnapshot snap3 = query.query(trainer, student, "P_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snap3.observations()).hasSize(1);
        assertThat(snap3.sourceWatermark()).isEqualTo(baselineWatermark); // Watermark must NOT change!

        // Mutation 3: Student supersedes out-of-scope measurement
        UUID outEarlyReplacement = createMeasurement(student, weightMetric, new BigDecimal("78.5"), kgUnit, tOutEarly.plusSeconds(3600), manualSource, null, "VALID", tOutEarly.plusSeconds(3600));
        jdbc.update("UPDATE fitness.measurements SET superseded_by_id=? WHERE id=?", outEarlyReplacement, outEarly);

        MeasurementProgressSnapshot snap4 = query.query(trainer, student, "P_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snap4.observations()).hasSize(1);
        assertThat(snap4.sourceWatermark()).isEqualTo(baselineWatermark);
    }

    @Test
    @DisplayName("9. asOf filtering excludes measurements measured or created after asOf")
    void asOfFiltering_excludesMeasurementsMeasuredOrCreatedAfterAsOf() {
        UUID student = createStudent("student_asof_filter");
        Short kgUnit = createUnit("A_KG", "kg", "MASS", "A_KG", 1.0);
        Integer weightMetric = createMetric("A_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_ASOF", "Manual", "MANUAL");

        Instant asOfCutoff = Instant.parse("2026-11-04T12:00:00Z");

        // 1. Measured before asOf, created before asOf -> INCLUDED
        Instant t1 = Instant.parse("2026-11-03T08:00:00Z");
        UUID m1 = createMeasurement(student, weightMetric, new BigDecimal("80.0"), kgUnit, t1, manualSource, null, "VALID", t1);

        // 2. Measured after asOf (Nov 5) -> EXCLUDED
        Instant t2 = Instant.parse("2026-11-05T08:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("80.5"), kgUnit, t2, manualSource, null, "VALID", t1);

        // 3. Measured before asOf (Nov 2), but backfilled/created after asOf (Nov 5) -> EXCLUDED from asOf snapshot
        Instant t3 = Instant.parse("2026-11-02T08:00:00Z");
        Instant createdLate = Instant.parse("2026-11-05T10:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("79.8"), kgUnit, t3, manualSource, null, "VALID", createdLate);

        MeasurementProgressSnapshot snapshot = query.query(student, student, "A_WEIGHT", fromDate, toDate, zoneVn, asOfCutoff);
        assertThat(snapshot.observations()).hasSize(1);
        assertThat(snapshot.observations().get(0).id()).isEqualTo(m1);
    }

    @Test
    @DisplayName("10. Historical reconstruction limits detected when data backfilled, superseded, erased, or re-validated after asOf")
    void historicalReconstructionLimits_detectedWhenDataModifiedAfterAsOf() {
        UUID student = createStudent("student_reconstruction_limits");
        Short kgUnit = createUnit("R_KG", "kg", "MASS", "R_KG", 1.0);
        Integer weightMetric = createMetric("R_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_RECON", "Manual", "MANUAL");

        Instant asOfCutoff = Instant.parse("2026-11-04T12:00:00Z");

        // Measurement 1: created and measured before asOf
        Instant t1 = Instant.parse("2026-11-02T08:00:00Z");
        UUID m1 = createMeasurement(student, weightMetric, new BigDecimal("81.0"), kgUnit, t1, manualSource, null, "VALID", t1);

        // Baseline: Clean snapshot without any modification after asOf
        MeasurementProgressSnapshot cleanSnapshot = query.query(student, student, "R_WEIGHT", fromDate, toDate, zoneVn, asOfCutoff);
        assertThat(cleanSnapshot.summary().hasHistoricalReconstructionLimits()).isFalse();
        assertThat(cleanSnapshot.observations().get(0).historicalReconstructionStatus()).isEqualTo("HISTORICAL_SNAPSHOT_EXACT");

        // Sub-case A: Backfill measurement measured on Nov 3 (before asOf) created on Nov 5 (after asOf)
        Instant tBackfilled = Instant.parse("2026-11-03T08:00:00Z");
        Instant createdAfterAsOf = Instant.parse("2026-11-05T10:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("81.5"), kgUnit, tBackfilled, manualSource, null, "VALID", createdAfterAsOf);

        MeasurementProgressSnapshot limitedSnapshot = query.query(student, student, "R_WEIGHT", fromDate, toDate, zoneVn, asOfCutoff);
        assertThat(limitedSnapshot.summary().hasHistoricalReconstructionLimits()).isTrue();
        assertThat(limitedSnapshot.observations().get(0).historicalReconstructionStatus()).isEqualTo("HISTORICAL_RECONSTRUCTION_LIMITED");

        // Sub-case B: Measurement created before asOf but superseded after asOf
        Instant tSupersededTime = Instant.parse("2026-11-02T10:00:00Z");
        UUID mOld = createMeasurement(student, weightMetric, new BigDecimal("81.2"), kgUnit, tSupersededTime, manualSource, null, "VALID", t1);
        UUID mNewSuperseding = createMeasurement(student, weightMetric, new BigDecimal("81.3"), kgUnit, tSupersededTime, manualSource, null, "VALID", createdAfterAsOf);
        jdbc.update("UPDATE fitness.measurements SET superseded_by_id=? WHERE id=?", mNewSuperseding, mOld);

        MeasurementProgressSnapshot supSnapshot = query.query(student, student, "R_WEIGHT", fromDate, toDate, zoneVn, asOfCutoff);
        assertThat(supSnapshot.summary().hasHistoricalReconstructionLimits()).isTrue();

        // Sub-case C: Validation event occurring after asOf
        jdbc.update("""
                INSERT INTO fitness.measurement_validation_events
                (id, measurement_id, previous_status, new_status, validator_type, occurred_at)
                VALUES (gen_random_uuid(), ?, 'PENDING', 'VALID', 'SYSTEM_RULE', ?)
                """, m1, Timestamp.from(createdAfterAsOf));

        MeasurementProgressSnapshot valEventSnapshot = query.query(student, student, "R_WEIGHT", fromDate, toDate, zoneVn, asOfCutoff);
        assertThat(valEventSnapshot.summary().hasHistoricalReconstructionLimits()).isTrue();

        // Sub-case D & E: Distinguish measurement erased AFTER asOf vs BEFORE/AT asOf
        UUID studentErase = createStudent("student_erase_distinction");
        UUID erasureRequest = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.account_erasure_requests(id, user_id, command_key, status_key_hash, target_at)
                VALUES (?, ?, ?, md5(random()::text || clock_timestamp()::text) || md5(random()::text || clock_timestamp()::text), clock_timestamp() + interval '24 hours')
                """, erasureRequest, studentErase, UUID.randomUUID());
        jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?", studentErase);

        Instant tMeas = Instant.parse("2026-11-02T08:00:00Z");
        Instant tEraseBeforeAsOf = Instant.parse("2026-11-03T10:00:00Z"); // erased before asOfCutoff (Nov 4 12:00)
        Instant tEraseAfterAsOf = Instant.parse("2026-11-05T10:00:00Z");  // erased after asOfCutoff

        // 1. Measurement erased BEFORE asOf: at asOf, it was already erased.
        // It does NOT cause historical reconstruction limits at asOf, and its erased data is never returned.
        UUID mErasedBefore = createMeasurement(studentErase, weightMetric, new BigDecimal("83.0"), kgUnit, tMeas, manualSource, null, "VALID", tMeas);
        jdbc.update("""
                UPDATE fitness.measurements
                SET numeric_value = NULL, unit_id = NULL, erased_at = ?, erasure_request_id = ?
                WHERE id = ?
                """, Timestamp.from(tEraseBeforeAsOf), erasureRequest, mErasedBefore);

        UUID mActive = createMeasurement(studentErase, weightMetric, new BigDecimal("82.0"), kgUnit, tMeas.plusSeconds(3600), manualSource, null, "VALID", tMeas);
        jdbc.update("UPDATE fitness.users SET status='ACTIVE' WHERE id=?", studentErase);

        MeasurementProgressSnapshot snapshotBeforeErasure = query.query(studentErase, studentErase, "R_WEIGHT", fromDate, toDate, zoneVn, asOfCutoff);
        assertThat(snapshotBeforeErasure.observations()).hasSize(1);
        assertThat(snapshotBeforeErasure.observations().get(0).id()).isEqualTo(mActive);
        assertThat(snapshotBeforeErasure.summary().hasHistoricalReconstructionLimits()).isFalse();
        assertThat(snapshotBeforeErasure.observations().get(0).historicalReconstructionStatus()).isEqualTo("HISTORICAL_SNAPSHOT_EXACT");

        // 2. Another measurement erased AFTER asOf: at asOf it was present and valid, but later erased.
        // This causes historical reconstruction limits because historical data at asOf cannot be reconstructed faithfully.
        UUID mErasedAfter = createMeasurement(studentErase, weightMetric, new BigDecimal("84.0"), kgUnit, tMeas.plusSeconds(7200), manualSource, null, "VALID", tMeas);
        jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?", studentErase);
        jdbc.update("""
                UPDATE fitness.measurements
                SET numeric_value = NULL, unit_id = NULL, erased_at = ?, erasure_request_id = ?
                WHERE id = ?
                """, Timestamp.from(tEraseAfterAsOf), erasureRequest, mErasedAfter);
        jdbc.update("UPDATE fitness.users SET status='ACTIVE' WHERE id=?", studentErase);

        MeasurementProgressSnapshot snapshotAfterErasure = query.query(studentErase, studentErase, "R_WEIGHT", fromDate, toDate, zoneVn, asOfCutoff);
        assertThat(snapshotAfterErasure.observations()).hasSize(1); // erased row is strictly excluded (erased_at IS NULL filter preserved)
        assertThat(snapshotAfterErasure.observations().get(0).id()).isEqualTo(mActive);
        assertThat(snapshotAfterErasure.summary().hasHistoricalReconstructionLimits()).isTrue();
        assertThat(snapshotAfterErasure.observations().get(0).historicalReconstructionStatus()).isEqualTo("HISTORICAL_RECONSTRUCTION_LIMITED");
    }

    @Test
    @DisplayName("11. Stable deterministic ordering when observations share identical timestamps")
    void stableDeterministicOrdering_whenObservationsShareTimestamp() {
        UUID student = createStudent("student_order_test");
        Short kgUnit = createUnit("O_KG", "kg", "MASS", "O_KG", 1.0);
        Integer weightMetric = createMetric("O_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_ORDER", "Manual", "MANUAL");

        Instant sameMeasuredTime = Instant.parse("2026-11-03T08:00:00Z");
        Instant created1 = Instant.parse("2026-11-03T08:01:00Z");
        Instant created2 = Instant.parse("2026-11-03T08:02:00Z");

        UUID mFirst = createMeasurement(student, weightMetric, new BigDecimal("82.0"), kgUnit, sameMeasuredTime, manualSource, null, "VALID", created1);
        UUID mSecond = createMeasurement(student, weightMetric, new BigDecimal("82.1"), kgUnit, sameMeasuredTime, manualSource, null, "VALID", created2);

        MeasurementProgressSnapshot snapshot = query.query(student, student, "O_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snapshot.observations()).hasSize(2);
        assertThat(snapshot.observations().get(0).id()).isEqualTo(mFirst);
        assertThat(snapshot.observations().get(1).id()).isEqualTo(mSecond);
    }

    @Test
    @DisplayName("12. Source watermark sensitivity to quality, confidence, and in-scope units without leaking unrelated catalog updates")
    void sourceWatermarkDeterminism_andDetailedDependencySensitivity() {
        UUID student = createStudent("student_wm_detailed");
        Short kgUnit = createUnit("W_KG", "kg", "MASS", "W_KG", 1.0);
        Short unrelatedUnit = createUnit("UNRELATED_UNIT", "unr", "OTHER", "UNRELATED_UNIT", 5.0);
        Integer weightMetric = createMetric("W_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_WM_DET", "Manual", "MANUAL");

        Instant t1 = Instant.parse("2026-11-03T08:00:00Z");
        UUID measId = createMeasurement(student, weightMetric, new BigDecimal("83.0"), kgUnit, t1, manualSource, null, "VALID", t1);
        jdbc.update("UPDATE fitness.measurements SET quality_score=85.0, confidence_score=90.0 WHERE id=?", measId);

        // 1. Determinism: identical queries produce identical watermark
        MeasurementProgressSnapshot snap1 = query.query(student, student, "W_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        MeasurementProgressSnapshot snap2 = query.query(student, student, "W_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snap1.sourceWatermark()).isEqualTo(snap2.sourceWatermark());

        // 2. Modifying an UNRELATED unit in catalog does NOT invalidate the watermark
        jdbc.update("UPDATE fitness.measurement_units SET multiplier_to_base = 6.0 WHERE id = ?", unrelatedUnit);
        MeasurementProgressSnapshot snapUnrelated = query.query(student, student, "W_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snapUnrelated.sourceWatermark()).isEqualTo(snap1.sourceWatermark());

        // 3. Modifying quality_score alters the watermark
        jdbc.update("UPDATE fitness.measurements SET quality_score = 95.0 WHERE id = ?", measId);
        MeasurementProgressSnapshot snapQuality = query.query(student, student, "W_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snapQuality.sourceWatermark()).isNotEqualTo(snap1.sourceWatermark());

        // 4. Modifying confidence_score alters the watermark
        jdbc.update("UPDATE fitness.measurements SET confidence_score = 99.0 WHERE id = ?", measId);
        MeasurementProgressSnapshot snapConfidence = query.query(student, student, "W_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snapConfidence.sourceWatermark()).isNotEqualTo(snapQuality.sourceWatermark());

        // 5. Modifying relevant unit multiplier_to_base alters the watermark
        jdbc.update("UPDATE fitness.measurement_units SET multiplier_to_base = 0.999999 WHERE id = ?", kgUnit);
        MeasurementProgressSnapshot snapUnit = query.query(student, student, "W_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(snapUnit.sourceWatermark()).isNotEqualTo(snapConfidence.sourceWatermark());
    }

    @Test
    @DisplayName("13. Availability reasons: empty window vs restricted time scope")
    void availabilityReasons_emptyWindowVsRestrictedTimeScope() {
        UUID student = createStudent("student_avail_test");
        UUID trainer = createTrainer("trainer_avail_test");
        createUnit("AV_KG", "kg", "MASS", "AV_KG", 1.0);
        createMetric("AV_WEIGHT", "Weight", (short) 1);

        // Case 1: Empty window for authorized student -> NO_MEASUREMENTS_RECORDED
        MeasurementProgressSnapshot emptySnapshot = query.query(student, student, "AV_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(emptySnapshot.observations()).isEmpty();
        assertThat(emptySnapshot.summary().availabilityReason()).isEqualTo("NO_MEASUREMENTS_RECORDED");

        // Case 2: Trainer granted scope outside query window -> MEASUREMENT_HISTORY_RESTRICTED
        Outcome active = accepted(student, trainer);
        Instant futureScopeFrom = Instant.parse("2026-11-15T00:00:00Z");
        sharing.grantOrReplace(student, active.relationship().id(), DataScope.BODY_METRICS,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, futureScopeFrom, null, null, null, UUID.randomUUID());

        MeasurementProgressSnapshot restrictedSnapshot = query.query(trainer, student, "AV_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
        assertThat(restrictedSnapshot.observations()).isEmpty();
        assertThat(restrictedSnapshot.summary().availabilityReason()).isEqualTo("MEASUREMENT_HISTORY_RESTRICTED");
    }

    @Test
    @DisplayName("14. Snapshot consistency: Repeatable Read isolation executes cleanly even when outer transaction is active")
    void snapshotConsistency_repeatableReadIsolationProtectsQuery() {
        UUID student = createStudent("student_isolation_test");
        Short kgUnit = createUnit("ISO_KG", "kg", "MASS", "ISO_KG", 1.0);
        Integer weightMetric = createMetric("ISO_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_ISO", "Manual", "MANUAL");

        Instant t1 = Instant.parse("2026-11-03T08:00:00Z");
        createMeasurement(student, weightMetric, new BigDecimal("84.0"), kgUnit, t1, manualSource, null, "VALID", t1);

        // Caller starts an outer transaction (read committed by default)
        var status = transactionManager.getTransaction(new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED));
        try {
            // Aggregation query runs within REQUIRES_NEW REPEATABLE READ
            MeasurementProgressSnapshot snapshot = query.query(student, student, "ISO_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
            assertThat(snapshot.observations()).hasSize(1);
            assertThat(snapshot.observations().get(0).numericValue()).isEqualByComparingTo("84.0");
        } finally {
            transactionManager.rollback(status);
        }
    }

    @Test
    @DisplayName("15. Snapshot consistency: Repeatable Read isolation shields against concurrent writer commit across independent connections")
    void snapshotConsistency_repeatableReadShieldsAgainstConcurrentWriterCommit() throws Exception {
        UUID student = createStudent("student_concurrent_test");
        Short kgUnit = createUnit("CONC_KG", "kg", "MASS", "CONC_KG", 1.0);
        Integer weightMetric = createMetric("CONC_WEIGHT", "Weight", kgUnit);
        Short manualSource = createSource("SRC_CONC", "Manual", "MANUAL");

        Instant t1 = Instant.parse("2026-11-02T08:00:00Z");
        UUID mInitial = createMeasurement(student, weightMetric, new BigDecimal("70.0"), kgUnit, t1, manualSource, null, "VALID", t1);

        CountDownLatch readerEnteredTxLatch = new CountDownLatch(1);
        CountDownLatch writerCommittedLatch = new CountDownLatch(1);

        // Spy on accessPolicy: when evaluateAccess runs inside reader's transaction, wait for writer commit
        doAnswer(invocation -> {
            var decision = (MeasurementAccessPolicy.MeasurementAccessDecision) invocation.callRealMethod();
            readerEnteredTxLatch.countDown();
            boolean completed = writerCommittedLatch.await(10, TimeUnit.SECONDS);
            if (!completed) {
                throw new IllegalStateException("Writer latch timed out");
            }
            return decision;
        }).when(accessPolicy).evaluateAccess(eq(student), eq(student));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writerFuture = executor.submit(() -> {
                try {
                    readerEnteredTxLatch.await(10, TimeUnit.SECONDS);
                    TransactionTemplate tx = new TransactionTemplate(transactionManager);
                    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    tx.execute(status -> {
                        // Concurrent writer commits a new measurement in a separate physical connection
                        Instant tConcurrent = Instant.parse("2026-11-04T08:00:00Z");
                        createMeasurement(student, weightMetric, new BigDecimal("70.5"), kgUnit, tConcurrent, manualSource, null, "VALID", tConcurrent);
                        return null;
                    });
                    writerCommittedLatch.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Reader query runs in its own REQUIRES_NEW REPEATABLE READ transaction
            MeasurementProgressSnapshot readerSnapshot = query.query(student, student, "CONC_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
            writerFuture.get(10, TimeUnit.SECONDS);

            // Reader snapshot must only see the initial observation mInitial (no tearing)
            assertThat(readerSnapshot.observations()).hasSize(1);
            assertThat(readerSnapshot.observations().get(0).id()).isEqualTo(mInitial);
            assertThat(readerSnapshot.summary().totalObservationsCount()).isEqualTo(1);

            // A subsequent new query after reader transaction completed will see both
            MeasurementProgressSnapshot secondSnapshot = query.query(student, student, "CONC_WEIGHT", fromDate, toDate, zoneVn, asOfFixed);
            assertThat(secondSnapshot.observations()).hasSize(2);
        } finally {
            org.mockito.Mockito.reset(accessPolicy);
            executor.shutdownNow();
        }
    }

    // --- Helper methods ---

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

    private Short createMethod(String code, String name) {
        return jdbc.queryForObject("""
                INSERT INTO fitness.measurement_methods(code, name)
                VALUES (?, ?)
                ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Short.class, code, name);
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
}
