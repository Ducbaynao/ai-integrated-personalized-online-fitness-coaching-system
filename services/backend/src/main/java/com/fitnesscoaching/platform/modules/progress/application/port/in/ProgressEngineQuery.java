package com.fitnesscoaching.platform.modules.progress.application.port.in;

import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementProgressQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutProgressAggregationQuery;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Inbound port for Progress Engine Query unifying Workout and Measurement sources.
 *
 * <p>Produces a deterministic, verified ProgressSnapshot incorporating:
 * <ul>
 *     <li>Workout progress aggregation (planned vs actual, adherence, volume)</li>
 *     <li>Measurement progress query (multi-observation series, baseline, latest)</li>
 *     <li>Deterministic metric deltas with strict unit conversion guards</li>
 *     <li>Training continuity signals with evidence-backed prolonged inactivity checks</li>
 *     <li>Aggregated historical reconstruction limits summary</li>
 *     <li>Composite source watermark for tamper/staleness detection</li>
 * </ul>
 */
public interface ProgressEngineQuery {

    ProgressSnapshot query(
            UUID actorId,
            UUID studentId,
            Set<String> metricCodes,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    );

    default ProgressSnapshot query(
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

    default ProgressSnapshot query(
            UUID actorId,
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        return query(actorId, studentId, (Set<String>) null, fromDate, toDate, timezone, asOf);
    }

    enum SourceAvailability {
        AVAILABLE,
        RESTRICTED,
        NO_DATA
    }

    enum TrendDirection {
        INCREASED,
        DECREASED,
        STABLE,
        INSUFFICIENT_DATA
    }

    enum ContinuityState {
        CONFIRMED_NEW,
        PROLONGED_INACTIVITY,
        RECENT_ACTIVITY_OBSERVED,
        UNKNOWN_HISTORY,
        RESTRICTED_HISTORY
    }

    record ProgressSnapshot(
            UUID studentId,
            UUID actorId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant effectiveAsOf,
            WorkoutProgressSection workout,
            MeasurementProgressSection measurement,
            ContinuitySignal continuity,
            HistoricalLimitsSummary historicalLimits,
            String compositeWatermark
    ) {}

    record WorkoutProgressSection(
            SourceAvailability availability,
            WorkoutProgressAggregationQuery.AdherenceSummary adherence,
            WorkoutProgressAggregationQuery.VolumeAndIntensitySummary volumeAndIntensity,
            List<WorkoutProgressAggregationQuery.PlannedOccurrenceFact> plannedOccurrences,
            List<WorkoutProgressAggregationQuery.ActualSessionFact> actualSessions,
            String sourceWatermark,
            String restrictionReason
    ) {
        public static WorkoutProgressSection available(WorkoutProgressAggregationQuery.WorkoutProgressSnapshot snapshot) {
            boolean hasData = (snapshot.actualSessions() != null && !snapshot.actualSessions().isEmpty())
                    || (snapshot.plannedOccurrences() != null && !snapshot.plannedOccurrences().isEmpty());
            return new WorkoutProgressSection(
                    hasData ? SourceAvailability.AVAILABLE : SourceAvailability.NO_DATA,
                    snapshot.adherence(),
                    snapshot.volumeAndIntensity(),
                    snapshot.plannedOccurrences() != null ? snapshot.plannedOccurrences() : List.of(),
                    snapshot.actualSessions() != null ? snapshot.actualSessions() : List.of(),
                    snapshot.sourceWatermark(),
                    null
            );
        }

        public static WorkoutProgressSection restricted(String reason) {
            return new WorkoutProgressSection(
                    SourceAvailability.RESTRICTED,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    reason
            );
        }
    }

    record MeasurementProgressSection(
            SourceAvailability availability,
            MeasurementProgressQuery.MeasurementSummary summary,
            List<MeasurementProgressQuery.MeasurementObservationFact> observations,
            List<MetricTrendDelta> metricDeltas,
            String sourceWatermark,
            String restrictionReason
    ) {
        public static MeasurementProgressSection available(
                MeasurementProgressQuery.MeasurementProgressSnapshot snapshot,
                List<MetricTrendDelta> deltas
        ) {
            boolean hasData = snapshot.observations() != null && !snapshot.observations().isEmpty();
            return new MeasurementProgressSection(
                    hasData ? SourceAvailability.AVAILABLE : SourceAvailability.NO_DATA,
                    snapshot.summary(),
                    snapshot.observations() != null ? snapshot.observations() : List.of(),
                    deltas != null ? deltas : List.of(),
                    snapshot.sourceWatermark(),
                    null
            );
        }

        public static MeasurementProgressSection restricted(String reason) {
            return new MeasurementProgressSection(
                    SourceAvailability.RESTRICTED,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    reason
            );
        }
    }

    record MetricTrendDelta(
            String metricCode,
            String metricDisplayName,
            String unitCode,
            BigDecimal baselineValue,
            Instant baselineMeasuredAt,
            BigDecimal latestValue,
            Instant latestMeasuredAt,
            BigDecimal deltaValue,
            BigDecimal percentageChange,
            TrendDirection trendDirection,
            int numericObservationsCount
    ) {}

    record ContinuitySignal(
            ContinuityState state,
            Integer daysSinceLastWorkout,
            LocalDate lastWorkoutDate,
            UUID lastWorkoutExecutionId,
            List<String> safetyFlags,
            String rationale
    ) {}

    record HistoricalLimitsSummary(
            boolean hasHistoricalLimits,
            boolean workoutLimits,
            boolean measurementLimits,
            String explanation
    ) {}
}
