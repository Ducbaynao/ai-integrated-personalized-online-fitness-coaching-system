package com.fitnesscoaching.platform.modules.ai.adapter.in.scheduled;

import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiOrphanRecoveryProperties;
import com.fitnesscoaching.platform.modules.ai.application.service.AiRunOrphanRecoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRunOrphanRecoverySchedulerUnitTest {

    @Mock
    private AiRunOrphanRecoveryService recoveryService;

    @Test
    @DisplayName("runScheduledRecovery: When recovery is disabled, returns 0 and does not invoke service")
    void runScheduledRecovery_disabled_skipsExecution() {
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(false, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryScheduler scheduler = new AiRunOrphanRecoveryScheduler(recoveryService, properties);

        int result = scheduler.runScheduledRecovery();

        assertThat(result).isZero();
        verifyNoInteractions(recoveryService);
    }

    @Test
    @DisplayName("runScheduledRecovery: When recovery is enabled, invokes recoveryService.executeRecoveryBatch()")
    void runScheduledRecovery_enabled_delegatesToService() {
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryScheduler scheduler = new AiRunOrphanRecoveryScheduler(recoveryService, properties);

        when(recoveryService.executeRecoveryBatch()).thenReturn(3);

        int result = scheduler.runScheduledRecovery();

        assertThat(result).isEqualTo(3);
        verify(recoveryService).executeRecoveryBatch();
    }
}
