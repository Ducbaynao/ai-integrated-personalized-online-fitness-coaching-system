package com.fitnesscoaching.platform.modules.ai;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryApiRequest;
import com.fitnesscoaching.platform.modules.ai.application.service.F04ContextBuilder;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementProgressQuery;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.ContinuitySignal;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.ContinuityState;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.HistoricalLimitsSummary;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.MeasurementProgressSection;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.MetricTrendDelta;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.ProgressSnapshot;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.SourceAvailability;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.TrendDirection;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.WorkoutProgressSection;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutProgressAggregationQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class F04ContextBuilderUnitTest {

    private ProgressEngineQuery progressEngineQuery;
    private UserAccountStatusQuery accountStatusQuery;
    private Clock clock;
    private F04ContextBuilder contextBuilder;

    private final UUID studentId = UUID.randomUUID();
    private final UUID trainerId = UUID.randomUUID();
    private final Instant fixedNow = Instant.parse("2026-10-28T12:00:00Z");

    @BeforeEach
    void setUp() {
        progressEngineQuery = Mockito.mock(ProgressEngineQuery.class);
        accountStatusQuery = Mockito.mock(UserAccountStatusQuery.class);
        clock = Clock.fixed(fixedNow, ZoneId.of("UTC"));
        contextBuilder = new F04ContextBuilder(progressEngineQuery, accountStatusQuery, clock);
    }

    @Test
    @DisplayName("Actor inactive: rejects with AiRunAccessDeniedException")
    void actorInactive_throwsAccessDenied() {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 28),
                "Asia/Ho_Chi_Minh",
                null,
                null
        );

        assertThatThrownBy(() -> contextBuilder.buildContext(studentId, req, UUID.randomUUID(), null))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Actor account is not active");
    }

    @Test
    @DisplayName("Target student inactive: rejects with AiRunAccessDeniedException")
    void studentInactive_throwsAccessDenied() {
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.DISABLED));

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 28),
                "Asia/Ho_Chi_Minh",
                null,
                null
        );

        assertThatThrownBy(() -> contextBuilder.buildContext(trainerId, req, UUID.randomUUID(), null))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Target student account is not active");
    }

    @Test
    @DisplayName("Invalid date window: fromDate after toDate throws AiRunValidationException")
    void invalidDateWindow_throwsValidationException() {
        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId,
                LocalDate.of(2026, 10, 28),
                LocalDate.of(2026, 10, 1),
                "Asia/Ho_Chi_Minh",
                null,
                null
        );

        assertThatThrownBy(() -> contextBuilder.buildContext(studentId, req, UUID.randomUUID(), null))
                .isInstanceOf(AiRunValidationException.class)
                .hasMessageContaining("fromDate cannot be after toDate");
    }

    @Test
    @DisplayName("Student self-request with full available data: builds complete minimal envelope")
    void studentSelfRequest_fullData_buildsCompleteEnvelope() {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        ProgressSnapshot mockSnapshot = new ProgressSnapshot(
                studentId,
                studentId,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 28),
                ZoneId.of("Asia/Ho_Chi_Minh"),
                fixedNow,
                new WorkoutProgressSection(
                        SourceAvailability.AVAILABLE,
                        new WorkoutProgressAggregationQuery.AdherenceSummary(
                                12, 11, 10, 1, 1, 0, 0, 0, 0, 0, 0, 2, 0, 0,
                                BigDecimal.valueOf(0.9167), BigDecimal.valueOf(0.8333), null
                        ),
                        new WorkoutProgressAggregationQuery.VolumeAndIntensitySummary(
                                BigDecimal.valueOf(14500.5), 48, 40, 8, 0, 0, 0, 0, 0, 40,
                                BigDecimal.ONE, false, 400, 2400, 14400, BigDecimal.valueOf(7.5), BigDecimal.valueOf(7.8), false
                        ),
                        List.of(), List.of(), "wm-workout-1", null
                ),
                new MeasurementProgressSection(
                        SourceAvailability.AVAILABLE,
                        new MeasurementProgressQuery.MeasurementSummary(
                                4, 1, 4, false, null, null, false, null
                        ),
                        List.of(),
                        List.of(
                                new MetricTrendDelta(
                                        "F_WEIGHT", "Body Weight", "KG",
                                        BigDecimal.valueOf(72.5), fixedNow.minusSeconds(86400 * 20),
                                        BigDecimal.valueOf(71.8), fixedNow,
                                        BigDecimal.valueOf(-0.7), BigDecimal.valueOf(-0.9655),
                                        TrendDirection.DECREASED, 4
                                )
                        ),
                        "wm-meas-1", null
                ),
                new ContinuitySignal(
                        ContinuityState.RECENT_ACTIVITY_OBSERVED, 2, LocalDate.of(2026, 10, 26),
                        UUID.randomUUID(), List.of(), "Observed 11 completed sessions."
                ),
                new HistoricalLimitsSummary(false, false, false, null),
                "ENGINE:composite-wm-1"
        );

        when(progressEngineQuery.query(
                eq(studentId), eq(studentId), (Set<String>) any(),
                eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 10, 28)),
                eq(ZoneId.of("Asia/Ho_Chi_Minh")), any()
        )).thenReturn(mockSnapshot);

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                null, // defaults to actorId
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 28),
                "Asia/Ho_Chi_Minh",
                Set.of("F_WEIGHT"),
                UUID.randomUUID()
        );

        UUID requestId = UUID.randomUUID();
        F04ContextBuilder.BuildContextResult result = contextBuilder.buildContext(studentId, req, requestId, req.correlationId());

        F04InternalContextEnvelope envelope = result.envelope();
        assertThat(envelope.requestId()).isEqualTo(requestId);
        assertThat(envelope.studentId()).isEqualTo(studentId);
        assertThat(envelope.actorId()).isEqualTo(studentId);
        assertThat(envelope.actorRole()).isEqualTo("STUDENT");

        var ctx = envelope.context();
        assertThat(ctx.workout().availability()).isEqualTo("AVAILABLE");
        assertThat(ctx.workout().adherenceSummary().completedDueCount()).isEqualTo(11);
        assertThat(ctx.workout().volumeSummary().knownVolumeKg()).isEqualTo(BigDecimal.valueOf(14500.5));
        assertThat(ctx.workout().sourceWatermark()).isEqualTo("wm-workout-1");

        assertThat(ctx.measurement().availability()).isEqualTo("AVAILABLE");
        assertThat(ctx.measurement().metricDeltas()).hasSize(1);
        assertThat(ctx.measurement().metricDeltas().get(0).metricCode()).isEqualTo("F_WEIGHT");
        assertThat(ctx.measurement().sourceWatermark()).isEqualTo("wm-meas-1");

        assertThat(ctx.continuity().state()).isEqualTo("RECENT_ACTIVITY_OBSERVED");
        assertThat(ctx.continuity().daysSinceLastWorkout()).isEqualTo(2);

        assertThat(ctx.historicalLimits().hasHistoricalLimits()).isFalse();
        assertThat(ctx.compositeWatermark()).isEqualTo("ENGINE:composite-wm-1");
    }

    @Test
    @DisplayName("Trainer request with restricted measurement: shields measurement data and nullifies watermark")
    void trainerRequest_restrictedMeasurement_shieldsDataAndWatermark() {
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        ProgressSnapshot mockSnapshot = new ProgressSnapshot(
                studentId,
                trainerId,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 28),
                ZoneId.of("Asia/Ho_Chi_Minh"),
                fixedNow,
                new WorkoutProgressSection(
                        SourceAvailability.AVAILABLE,
                        new WorkoutProgressAggregationQuery.AdherenceSummary(
                                8, 8, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                BigDecimal.ONE, BigDecimal.ONE, null
                        ),
                        new WorkoutProgressAggregationQuery.VolumeAndIntensitySummary(
                                BigDecimal.valueOf(9000), 24, 24, 0, 0, 0, 0, 0, 0, 24,
                                BigDecimal.ONE, false, 240, 1200, 7200, BigDecimal.valueOf(8.0), BigDecimal.valueOf(8.0), false
                        ),
                        List.of(), List.of(), "wm-workout-1", null
                ),
                MeasurementProgressSection.restricted("BODY_METRICS scope not granted"),
                new ContinuitySignal(
                        ContinuityState.RECENT_ACTIVITY_OBSERVED, 1, LocalDate.of(2026, 10, 27),
                        UUID.randomUUID(), List.of(), "Recent workout observed."
                ),
                new HistoricalLimitsSummary(false, false, false, null),
                "ENGINE:composite-wm-restricted"
        );

        when(progressEngineQuery.query(any(), any(), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(mockSnapshot);

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 28),
                "Asia/Ho_Chi_Minh",
                null,
                null
        );

        F04ContextBuilder.BuildContextResult result = contextBuilder.buildContext(trainerId, req, UUID.randomUUID(), null);
        F04InternalContextEnvelope envelope = result.envelope();

        assertThat(envelope.actorRole()).isEqualTo("TRAINER");
        assertThat(envelope.context().workout().availability()).isEqualTo("AVAILABLE");
        assertThat(envelope.context().measurement().availability()).isEqualTo("RESTRICTED");
        assertThat(envelope.context().measurement().restrictionReason()).isEqualTo("BODY_METRICS scope not granted");
        assertThat(envelope.context().measurement().summary()).isNull();
        assertThat(envelope.context().measurement().metricDeltas()).isEmpty();
        // Invariant: Source watermark of restricted source MUST be null
        assertThat(envelope.context().measurement().sourceWatermark()).isNull();
    }

    @Test
    @DisplayName("Prolonged inactivity: maps safety flags and state correctly")
    void prolongedInactivity_mapsSafetyFlags() {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        ProgressSnapshot mockSnapshot = new ProgressSnapshot(
                studentId,
                studentId,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 28),
                ZoneId.of("Asia/Ho_Chi_Minh"),
                fixedNow,
                new WorkoutProgressSection(SourceAvailability.NO_DATA, null, null, List.of(), List.of(), null, null),
                new MeasurementProgressSection(SourceAvailability.NO_DATA, null, List.of(), List.of(), null, null),
                new ContinuitySignal(
                        ContinuityState.PROLONGED_INACTIVITY, 115, LocalDate.of(2026, 7, 5),
                        UUID.randomUUID(), List.of("RETURN_AFTER_100_DAYS_V1"), "Inactivity >= 100 days"
                ),
                new HistoricalLimitsSummary(true, false, true, "Measurement modified after asOf"),
                "ENGINE:composite-wm-prolonged"
        );

        when(progressEngineQuery.query(any(), any(), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(mockSnapshot);

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 28),
                "Asia/Ho_Chi_Minh",
                null,
                null
        );

        F04ContextBuilder.BuildContextResult result = contextBuilder.buildContext(studentId, req, UUID.randomUUID(), null);
        var ctx = result.envelope().context();

        assertThat(ctx.continuity().state()).isEqualTo("PROLONGED_INACTIVITY");
        assertThat(ctx.continuity().daysSinceLastWorkout()).isEqualTo(115);
        assertThat(ctx.continuity().safetyFlags()).contains("RETURN_AFTER_100_DAYS_V1");
        assertThat(ctx.historicalLimits().hasHistoricalLimits()).isTrue();
        assertThat(ctx.historicalLimits().measurementLimits()).isTrue();
    }
}
