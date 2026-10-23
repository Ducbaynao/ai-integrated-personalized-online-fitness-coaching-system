package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiOrphanRecoveryProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRunOrphanRecoveryServiceUnitTest {

    @Mock
    private AiRunRepository runRepository;

    private final Instant fixedNow = Instant.parse("2026-10-23T12:00:00Z");
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(fixedNow, ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("executeRecoveryBatch: When no stale runs found, returns 0")
    void executeRecoveryBatch_noStaleRuns_returnsZero() {
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryService service = new AiRunOrphanRecoveryService(runRepository, properties, fixedClock);

        Instant expectedCutoff = fixedNow.minusSeconds(120);
        when(runRepository.findStaleRunIds(expectedCutoff, 50)).thenReturn(List.of());

        int result = service.executeRecoveryBatch();

        assertThat(result).isZero();
        verify(runRepository).findStaleRunIds(expectedCutoff, 50);
        verify(runRepository, never()).reconcileStaleRun(any(), any(), any(), any());
    }

    @Test
    @DisplayName("executeRecoveryBatch: Reconciles all stale runs and marks them FAILED with TIMEOUT")
    void executeRecoveryBatch_reconcilesStaleRuns() {
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryService service = new AiRunOrphanRecoveryService(runRepository, properties, fixedClock);

        Instant expectedCutoff = fixedNow.minusSeconds(120);
        UUID run1 = UUID.randomUUID();
        UUID run2 = UUID.randomUUID();
        when(runRepository.findStaleRunIds(expectedCutoff, 50)).thenReturn(List.of(run1, run2));
        when(runRepository.reconcileStaleRun(eq(run1), eq("TIMEOUT"), any(), eq(fixedNow))).thenReturn(true);
        when(runRepository.reconcileStaleRun(eq(run2), eq("TIMEOUT"), any(), eq(fixedNow))).thenReturn(true);

        int result = service.executeRecoveryBatch();

        assertThat(result).isEqualTo(2);
        verify(runRepository).reconcileStaleRun(eq(run1), eq("TIMEOUT"), eq("Execution timed out due to system interruption or backend restart."), eq(fixedNow));
        verify(runRepository).reconcileStaleRun(eq(run2), eq("TIMEOUT"), eq("Execution timed out due to system interruption or backend restart."), eq(fixedNow));
    }

    @Test
    @DisplayName("executeRecoveryBatch: Skips runs that completed concurrently before cleaner update")
    void executeRecoveryBatch_skipsConcurrentlyCompletedRun() {
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryService service = new AiRunOrphanRecoveryService(runRepository, properties, fixedClock);

        Instant expectedCutoff = fixedNow.minusSeconds(120);
        UUID run1 = UUID.randomUUID();
        UUID run2 = UUID.randomUUID();
        when(runRepository.findStaleRunIds(expectedCutoff, 50)).thenReturn(List.of(run1, run2));
        // run1 succeeded in reconciliation, run2 was completed concurrently so reconcile returned false
        when(runRepository.reconcileStaleRun(eq(run1), eq("TIMEOUT"), any(), eq(fixedNow))).thenReturn(true);
        when(runRepository.reconcileStaleRun(eq(run2), eq("TIMEOUT"), any(), eq(fixedNow))).thenReturn(false);

        int result = service.executeRecoveryBatch();

        // Only run1 was successfully reconciled
        assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("executeRecoveryBatch: Uses configurable timeout threshold to calculate cutoff")
    void executeRecoveryBatch_customTimeout_calculatesAccurateCutoff() {
        int customTimeout = 300; // 5 minutes
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, customTimeout, 30000, 5000, 25);
        AiRunOrphanRecoveryService service = new AiRunOrphanRecoveryService(runRepository, properties, fixedClock);

        Instant expectedCutoff = fixedNow.minusSeconds(customTimeout);
        when(runRepository.findStaleRunIds(expectedCutoff, 25)).thenReturn(List.of());

        service.executeRecoveryBatch();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(runRepository).findStaleRunIds(cutoffCaptor.capture(), eq(25));
        assertThat(cutoffCaptor.getValue()).isEqualTo(expectedCutoff);
    }
}
