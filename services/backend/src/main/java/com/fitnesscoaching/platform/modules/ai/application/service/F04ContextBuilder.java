package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryApiRequest;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.ContinuitySignalDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.DateWindowDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.F04ProgressContextDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.HistoricalLimitsSummaryDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.MeasurementSectionDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.MeasurementSummaryDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.MetricTrendDeltaDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.WorkoutAdherenceSummaryDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.WorkoutSectionDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.WorkoutVolumeSummaryDto;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.ProgressSnapshot;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds minimal authorized F04 context envelopes grounded in verified facts from ProgressEngineQuery.
 */
@Component
public class F04ContextBuilder {

    private final ProgressEngineQuery progressEngineQuery;
    private final UserAccountStatusQuery accountStatusQuery;
    private final Clock clock;

    public F04ContextBuilder(
            ProgressEngineQuery progressEngineQuery,
            UserAccountStatusQuery accountStatusQuery,
            Clock clock
    ) {
        this.progressEngineQuery = progressEngineQuery;
        this.accountStatusQuery = accountStatusQuery;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public record BuildContextResult(
            F04InternalContextEnvelope envelope,
            ProgressSnapshot snapshot
    ) {}

    public BuildContextResult buildContext(
            UUID actorId,
            F04ProgressSummaryApiRequest request,
            UUID requestId,
            UUID correlationId
    ) {
        if (actorId == null) {
            throw new ApplicationValidationException("actorId cannot be null");
        }
        if (request == null) {
            throw new ApplicationValidationException("F04ProgressSummaryApiRequest cannot be null");
        }
        if (request.fromDate() == null || request.toDate() == null) {
            throw new AiRunValidationException("fromDate and toDate are required parameters.");
        }
        if (request.fromDate().isAfter(request.toDate())) {
            throw new AiRunValidationException("fromDate cannot be after toDate.");
        }

        // 1. Verify Actor Account Status (fail closed)
        AccountStatus actorStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
        if (actorStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        // 2. Resolve Student ID and Role
        UUID studentId = request.studentId() != null ? request.studentId() : actorId;
        String actorRole = actorId.equals(studentId) ? "STUDENT" : "TRAINER";

        AccountStatus studentStatus = accountStatusQuery.getAccountStatus(studentId).orElse(null);
        if (studentStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Target student account is not active.");
        }

        // 3. Resolve Timezone and effectiveAsOf
        ZoneId timezone;
        try {
            timezone = ZoneId.of(request.timezone() != null ? request.timezone() : "Asia/Ho_Chi_Minh");
        } catch (Exception ex) {
            throw new AiRunValidationException("Invalid timezone string: " + request.timezone());
        }

        Instant asOf = Instant.now(clock);

        // 4. Query Progress Engine (handles independent source access control)
        ProgressSnapshot snapshot = progressEngineQuery.query(
                actorId,
                studentId,
                request.metricCodes(),
                request.fromDate(),
                request.toDate(),
                timezone,
                asOf
        );

        // 5. Minimize Context according to Data Minimization Invariants
        WorkoutSectionDto workoutDto = mapWorkoutSection(snapshot);
        MeasurementSectionDto measurementDto = mapMeasurementSection(snapshot);
        ContinuitySignalDto continuityDto = mapContinuitySignal(snapshot);
        HistoricalLimitsSummaryDto limitsDto = mapHistoricalLimits(snapshot);

        F04ProgressContextDto contextDto = new F04ProgressContextDto(
                new DateWindowDto(request.fromDate(), request.toDate(), timezone.getId()),
                snapshot.effectiveAsOf(),
                workoutDto,
                measurementDto,
                continuityDto,
                limitsDto,
                snapshot.compositeWatermark()
        );

        UUID effectiveRequestId = requestId != null ? requestId : UUID.randomUUID();
        UUID effectiveCorrelationId = correlationId != null ? correlationId : (request.correlationId() != null ? request.correlationId() : UUID.randomUUID());

        F04InternalContextEnvelope envelope = new F04InternalContextEnvelope(
                effectiveRequestId,
                effectiveCorrelationId,
                studentId,
                actorId,
                actorRole,
                contextDto
        );

        return new BuildContextResult(envelope, snapshot);
    }

    private WorkoutSectionDto mapWorkoutSection(ProgressSnapshot snapshot) {
        var w = snapshot.workout();
        String availability = w.availability().name();

        if (w.availability() != ProgressEngineQuery.SourceAvailability.AVAILABLE) {
            // Strictly null out data and watermark for restricted / no data sources
            return new WorkoutSectionDto(
                    availability,
                    w.restrictionReason(),
                    null,
                    null,
                    null
            );
        }

        var adh = w.adherence();
        WorkoutAdherenceSummaryDto adhDto = adh != null ? new WorkoutAdherenceSummaryDto(
                adh.scheduledDueCount(),
                adh.completedDueCount(),
                adh.completedOnTimeCount(),
                adh.completedOffScheduleCount(),
                adh.missedCount(),
                adh.todayPendingCount(),
                adh.inProgressCount(),
                adh.partiallyCompletedCount(),
                adh.abortedCount(),
                adh.rescheduledCount(),
                adh.cancelledCount(),
                adh.upcomingCount(),
                adh.unplannedCompletedCount(),
                adh.unverifiableDueCount(),
                adh.rawCompletionRate(),
                adh.onTimeAdherenceRate(),
                adh.availabilityReason()
        ) : null;

        var vol = w.volumeAndIntensity();
        WorkoutVolumeSummaryDto volDto = vol != null ? new WorkoutVolumeSummaryDto(
                vol.knownVolumeKg(),
                vol.totalSetsCompleted(),
                vol.workingSetsCompleted(),
                vol.warmupSetsCompleted(),
                vol.bodyweightSetsCompleted(),
                vol.setsWithMissingLoadCount(),
                vol.setsWithZeroLoadCount(),
                vol.setsWithMissingRepsCount(),
                vol.setsWithUnknownUnitCount(),
                vol.setsWithCalculableVolumeCount(),
                vol.loadCoverageRate(),
                vol.hasIncompleteVolumeCoverage(),
                vol.totalRepsCompleted(),
                vol.totalSetDurationSeconds(),
                vol.totalSessionElapsedSeconds(),
                vol.averageSessionRpe(),
                vol.averageSetRpe()
        ) : null;

        return new WorkoutSectionDto(
                availability,
                w.restrictionReason(),
                adhDto,
                volDto,
                w.sourceWatermark()
        );
    }

    private MeasurementSectionDto mapMeasurementSection(ProgressSnapshot snapshot) {
        var m = snapshot.measurement();
        String availability = m.availability().name();

        if (m.availability() != ProgressEngineQuery.SourceAvailability.AVAILABLE) {
            return new MeasurementSectionDto(
                    availability,
                    m.restrictionReason(),
                    null,
                    List.of(),
                    null
            );
        }

        var summary = m.summary();
        MeasurementSummaryDto summaryDto = summary != null ? new MeasurementSummaryDto(
                summary.totalObservationsCount(),
                summary.distinctDaysCount(),
                summary.distinctMetricsCount()
        ) : null;

        List<MetricTrendDeltaDto> deltas = m.metricDeltas() != null ? m.metricDeltas().stream()
                .map(d -> new MetricTrendDeltaDto(
                        d.metricCode(),
                        d.metricDisplayName(),
                        d.unitCode(),
                        d.baselineValue(),
                        d.baselineMeasuredAt(),
                        d.latestValue(),
                        d.latestMeasuredAt(),
                        d.deltaValue(),
                        d.percentageChange(),
                        d.trendDirection() != null ? d.trendDirection().name() : "INSUFFICIENT_DATA",
                        d.numericObservationsCount()
                ))
                .toList() : List.of();

        return new MeasurementSectionDto(
                availability,
                m.restrictionReason(),
                summaryDto,
                deltas,
                m.sourceWatermark()
        );
    }

    private ContinuitySignalDto mapContinuitySignal(ProgressSnapshot snapshot) {
        var c = snapshot.continuity();
        return new ContinuitySignalDto(
                c.state() != null ? c.state().name() : "UNKNOWN_HISTORY",
                c.daysSinceLastWorkout(),
                c.lastWorkoutDate(),
                c.safetyFlags() != null ? c.safetyFlags() : List.of(),
                c.rationale()
        );
    }

    private HistoricalLimitsSummaryDto mapHistoricalLimits(ProgressSnapshot snapshot) {
        var h = snapshot.historicalLimits();
        return new HistoricalLimitsSummaryDto(
                h.hasHistoricalLimits(),
                h.workoutLimits(),
                h.measurementLimits(),
                h.explanation()
        );
    }
}
