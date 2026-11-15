package com.fitnesscoaching.platform.common.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

class StorageAndScannerHealthIndicatorUnitTest {

    @Test
    @DisplayName("StorageHealthIndicator returns UNKNOWN when storage credentials are unconfigured")
    void storageHealthIndicator_unconfigured_returnsUnknown() {
        StorageHealthIndicator indicator = new StorageHealthIndicator("", "", "", "");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("status", "NOT_CONFIGURED");
        assertThat(indicator.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("StorageHealthIndicator returns DOWN when S3 is unreachable")
    void storageHealthIndicator_unreachable_returnsDown() {
        StorageHealthIndicator indicator = new StorageHealthIndicator(
                "http://localhost:59999", "fitness-media", "test-key", "test-secret"
        );

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
        assertThat(health.getDetails()).containsEntry("bucket", "fitness-media");
    }

    @Test
    @DisplayName("ScannerHealthIndicator returns UNKNOWN when clamd host is unconfigured")
    void scannerHealthIndicator_unconfigured_returnsUnknown() {
        ScannerHealthIndicator indicator = new ScannerHealthIndicator("", 3310);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("status", "NOT_CONFIGURED");
    }

    @Test
    @DisplayName("ScannerHealthIndicator returns DOWN when clamd host is unreachable")
    void scannerHealthIndicator_unreachable_returnsDown() {
        ScannerHealthIndicator indicator = new ScannerHealthIndicator("localhost", 59998);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
