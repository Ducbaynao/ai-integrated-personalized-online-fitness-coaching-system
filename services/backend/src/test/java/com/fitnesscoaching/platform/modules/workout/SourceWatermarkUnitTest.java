package com.fitnesscoaching.platform.modules.workout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class SourceWatermarkUnitTest {

    public record PlannedWorkoutRecord(UUID id, long version, String status, Instant plannedStart, Instant updatedAt) {}
    public record ActualWorkoutRecord(UUID id, Instant performedStart, String status, Instant updatedAt, String setLogsDigest) {}
    public record AdjustmentRecord(UUID id, String type, Instant createdAt) {}
    public record MeasurementRecord(UUID id, Instant measuredAt, BigDecimal numericValue, UUID supersededById, Instant erasedAt) {}
    public record MeasurementUnitRecord(short id, String code, String symbol, BigDecimal multiplierToBase, BigDecimal multiplierToKg) {}

    public static class CompositeSourceWatermarkCalculator {

        public static String calculateWatermark(
                LocalDate fromDate,
                LocalDate toDate,
                ZoneId timezone,
                List<PlannedWorkoutRecord> plannedWorkouts,
                List<ActualWorkoutRecord> actualWorkouts,
                List<AdjustmentRecord> adjustments,
                List<MeasurementRecord> measurements
        ) {
            return calculateWatermark(fromDate, toDate, timezone, plannedWorkouts, actualWorkouts, adjustments, measurements, List.of());
        }

        public static String calculateWatermark(
                LocalDate fromDate,
                LocalDate toDate,
                ZoneId timezone,
                List<PlannedWorkoutRecord> plannedWorkouts,
                List<ActualWorkoutRecord> actualWorkouts,
                List<AdjustmentRecord> adjustments,
                List<MeasurementRecord> measurements,
                List<MeasurementUnitRecord> units
        ) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                StringBuilder sb = new StringBuilder();
                sb.append("WINDOW:").append(fromDate).append(":").append(toDate).append(":").append(timezone.getId()).append("|");

                // 0. Measurement Units (conversion dependencies sorted by ID)
                var sortedUnits = new ArrayList<>(units);
                sortedUnits.sort(Comparator.comparingInt(u -> (int) u.id()));
                sb.append("UNITS:").append(sortedUnits.size()).append(";");
                for (var u : sortedUnits) {
                    sb.append(u.id()).append(",")
                      .append(u.code()).append(",")
                      .append(u.symbol() != null ? u.symbol() : "null").append(",")
                      .append(u.multiplierToBase() != null ? u.multiplierToBase().stripTrailingZeros().toPlainString() : "null").append(",")
                      .append(u.multiplierToKg() != null ? u.multiplierToKg().stripTrailingZeros().toPlainString() : "null").append(";");
                }
                sb.append("|");

                // 1. Planned Workouts (sorted by ID for deterministic order)
                var sortedPlanned = new ArrayList<>(plannedWorkouts);
                sortedPlanned.sort(Comparator.comparing(PlannedWorkoutRecord::id));
                sb.append("PLANNED:").append(sortedPlanned.size()).append(";");
                for (var p : sortedPlanned) {
                    sb.append(p.id()).append(",")
                      .append(p.version()).append(",")
                      .append(p.status()).append(",")
                      .append(p.plannedStart().toEpochMilli()).append(",")
                      .append(p.updatedAt().toEpochMilli()).append(";");
                }
                sb.append("|");

                // 2. Actual Workout Session Logs (sorted by ID)
                var sortedActual = new ArrayList<>(actualWorkouts);
                sortedActual.sort(Comparator.comparing(ActualWorkoutRecord::id));
                sb.append("ACTUAL:").append(sortedActual.size()).append(";");
                for (var a : sortedActual) {
                    sb.append(a.id()).append(",")
                      .append(a.performedStart().toEpochMilli()).append(",")
                      .append(a.status()).append(",")
                      .append(a.updatedAt().toEpochMilli()).append(",")
                      .append(a.setLogsDigest() != null ? a.setLogsDigest() : "empty").append(";");
                }
                sb.append("|");

                // 3. Adjustments (sorted by ID)
                var sortedAdj = new ArrayList<>(adjustments);
                sortedAdj.sort(Comparator.comparing(AdjustmentRecord::id));
                sb.append("ADJ:").append(sortedAdj.size()).append(";");
                for (var adj : sortedAdj) {
                    sb.append(adj.id()).append(",")
                      .append(adj.type()).append(",")
                      .append(adj.createdAt().toEpochMilli()).append(";");
                }
                sb.append("|");

                // 4. Measurements (sorted by ID)
                var sortedMeas = new ArrayList<>(measurements);
                sortedMeas.sort(Comparator.comparing(MeasurementRecord::id));
                sb.append("MEAS:").append(sortedMeas.size()).append(";");
                for (var m : sortedMeas) {
                    sb.append(m.id()).append(",")
                      .append(m.measuredAt().toEpochMilli()).append(",")
                      .append(m.numericValue() != null ? m.numericValue().toPlainString() : "null").append(",")
                      .append(m.supersededById() != null ? m.supersededById().toString() : "active").append(",")
                      .append(m.erasedAt() != null ? m.erasedAt().toEpochMilli() : "live").append(";");
                }

                byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private final ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
    private final LocalDate fromDate = LocalDate.of(2026, 11, 1);
    private final LocalDate toDate = LocalDate.of(2026, 11, 7);

    @Test
    @DisplayName("Deterministic: Identical inputs produce identical composite watermark")
    void identicalInputs_produceIdenticalWatermark() {
        UUID w1 = UUID.randomUUID();
        UUID a1 = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-04T10:00:00Z");

        var planned = List.of(new PlannedWorkoutRecord(w1, 1L, "SCHEDULED", now, now));
        var actual = List.of(new ActualWorkoutRecord(a1, now, "COMPLETED", now, "set-hash-1"));

        String wm1 = CompositeSourceWatermarkCalculator.calculateWatermark(fromDate, toDate, zone, planned, actual, List.of(), List.of());
        String wm2 = CompositeSourceWatermarkCalculator.calculateWatermark(fromDate, toDate, zone, planned, actual, List.of(), List.of());

        assertThat(wm1).isEqualTo(wm2);
    }

    @Test
    @DisplayName("Failure mode of MAX(updated_at): Modifying an older record inside the window changes composite watermark even if latest updated_at is unchanged")
    void modifyingOlderRecordInsideWindow_changesWatermark_evenWhenLatestUpdatedAtUnchanged() {
        Instant latestTime = Instant.parse("2026-11-06T15:00:00Z");
        Instant olderTimeBefore = Instant.parse("2026-11-02T08:00:00Z");
        Instant olderTimeCorrected = Instant.parse("2026-11-02T09:30:00Z"); // Edited older workout log

        UUID olderWorkoutId = UUID.randomUUID();
        UUID latestWorkoutId = UUID.randomUUID();

        // Initial state: older workout has set-hash-A, latest workout updated at 15:00
        var actualBefore = List.of(
                new ActualWorkoutRecord(olderWorkoutId, olderTimeBefore, "COMPLETED", olderTimeBefore, "set-hash-A"),
                new ActualWorkoutRecord(latestWorkoutId, latestTime, "COMPLETED", latestTime, "set-hash-LATEST")
        );

        // State after correction: older workout's set logs changed to set-hash-B, but latestWorkout.updatedAt is STILL 15:00 (MAX unchanged!)
        var actualAfter = List.of(
                new ActualWorkoutRecord(olderWorkoutId, olderTimeBefore, "COMPLETED", olderTimeCorrected, "set-hash-B"),
                new ActualWorkoutRecord(latestWorkoutId, latestTime, "COMPLETED", latestTime, "set-hash-LATEST")
        );

        // A naive MAX(updated_at) would see latestTime in both cases and falsely conclude data is unchanged!
        Instant naiveMaxBefore = actualBefore.stream().map(ActualWorkoutRecord::updatedAt).max(Comparator.naturalOrder()).orElseThrow();
        Instant naiveMaxAfter = actualAfter.stream().map(ActualWorkoutRecord::updatedAt).max(Comparator.naturalOrder()).orElseThrow();
        assertThat(naiveMaxBefore).isEqualTo(naiveMaxAfter); // Demonstrates naive MAX failure!

        // Composite watermark correctly detects the correction:
        String wmBefore = CompositeSourceWatermarkCalculator.calculateWatermark(fromDate, toDate, zone, List.of(), actualBefore, List.of(), List.of());
        String wmAfter = CompositeSourceWatermarkCalculator.calculateWatermark(fromDate, toDate, zone, List.of(), actualAfter, List.of(), List.of());

        assertThat(wmBefore).isNotEqualTo(wmAfter);
    }

    @Test
    @DisplayName("Record moving into or out of the date window is detected by composite watermark")
    void recordMovingAcrossWindowBoundary_changesWatermark() {
        UUID workoutId = UUID.randomUUID();
        Instant outsideWindowTime = Instant.parse("2026-10-31T20:00:00Z"); // October 31 (outside [Nov 1, Nov 7])
        Instant insideWindowTime = Instant.parse("2026-11-02T10:00:00Z");  // November 2 (inside)

        // Case 1: Workout is inside window
        var insideList = List.of(new ActualWorkoutRecord(workoutId, insideWindowTime, "COMPLETED", insideWindowTime, "hash-1"));
        String wmInside = CompositeSourceWatermarkCalculator.calculateWatermark(fromDate, toDate, zone, List.of(), insideList, List.of(), List.of());

        // Case 2: Workout date was corrected to outside window (window now has 0 records)
        List<ActualWorkoutRecord> emptyList = List.of();
        String wmEmpty = CompositeSourceWatermarkCalculator.calculateWatermark(fromDate, toDate, zone, List.of(), emptyList, List.of(), List.of());

        assertThat(wmInside).isNotEqualTo(wmEmpty);
    }

    @Test
    @DisplayName("Measurement erasure tombstone (erased_at set) changes watermark without row addition")
    void measurementErasureTombstone_changesWatermark() {
        UUID measId = UUID.randomUUID();
        Instant measuredAt = Instant.parse("2026-11-03T07:00:00Z");
        BigDecimal weight = new BigDecimal("72.5");

        // Before erasure: measurement is live
        var liveMeas = List.of(new MeasurementRecord(measId, measuredAt, weight, null, null));
        String wmLive = CompositeSourceWatermarkCalculator.calculateWatermark(fromDate, toDate, zone, List.of(), List.of(), List.of(), liveMeas);

        // After erasure: measurement is tombstoned (erased_at is populated, value cleared)
        var erasedMeas = List.of(new MeasurementRecord(measId, measuredAt, null, null, Instant.now()));
        String wmErased = CompositeSourceWatermarkCalculator.calculateWatermark(fromDate, toDate, zone, List.of(), List.of(), List.of(), erasedMeas);

        assertThat(wmLive).isNotEqualTo(wmErased);
    }

    @Test
    @DisplayName("Measurement superseded by a newer measurement changes watermark")
    void measurementSuperseded_changesWatermark() {
        UUID measId = UUID.randomUUID();
        UUID supersedingId = UUID.randomUUID();
        Instant measuredAt = Instant.parse("2026-11-03T07:00:00Z");
        BigDecimal weight = new BigDecimal("72.5");

        var activeMeas = List.of(new MeasurementRecord(measId, measuredAt, weight, null, null));
        String wmActive = CompositeSourceWatermarkCalculator.calculateWatermark(fromDate, toDate, zone, List.of(), List.of(), List.of(), activeMeas);

        var supersededMeas = List.of(new MeasurementRecord(measId, measuredAt, weight, supersedingId, null));
        String wmSuperseded = CompositeSourceWatermarkCalculator.calculateWatermark(fromDate, toDate, zone, List.of(), List.of(), List.of(), supersededMeas);

        assertThat(wmActive).isNotEqualTo(wmSuperseded);
    }

    @Test
    @DisplayName("Measurement unit conversion factor change alters composite watermark")
    void unitConversionMultiplierChange_altersWatermark() {
        var unit1 = new MeasurementUnitRecord((short) 1, "LB", "lb", new BigDecimal("0.45359237"), new BigDecimal("0.45359237"));
        String wm1 = CompositeSourceWatermarkCalculator.calculateWatermark(
                fromDate, toDate, zone, List.of(), List.of(), List.of(), List.of(), List.of(unit1));

        var unit2 = new MeasurementUnitRecord((short) 1, "LB", "lb", new BigDecimal("0.45000000"), new BigDecimal("0.45000000"));
        String wm2 = CompositeSourceWatermarkCalculator.calculateWatermark(
                fromDate, toDate, zone, List.of(), List.of(), List.of(), List.of(), List.of(unit2));

        assertThat(wm1).isNotEqualTo(wm2);
    }
}
