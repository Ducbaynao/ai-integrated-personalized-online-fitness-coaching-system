package com.fitnesscoaching.platform.modules.notification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class PushDeliveryWorkerTest {
    @Test void quietHoursRespectUserTimezoneAndCrossMidnight() {
        Instant at = Instant.parse("2026-10-25T16:30:00Z");
        assertThat(PushDeliveryWorker.quietNow(at, "Asia/Ho_Chi_Minh",
                LocalTime.of(22, 0), LocalTime.of(7, 0))).isTrue();
        assertThat(PushDeliveryWorker.quietNow(at, "America/New_York",
                LocalTime.of(22, 0), LocalTime.of(7, 0))).isFalse();
        assertThat(PushDeliveryWorker.quietNow(at, "Asia/Ho_Chi_Minh", null, null)).isFalse();
        // Both UTC instants in New York's repeated autumn hour remain quiet.
        assertThat(PushDeliveryWorker.quietNow(Instant.parse("2026-11-01T05:30:00Z"), "America/New_York",
                LocalTime.of(1, 0), LocalTime.of(2, 0))).isTrue();
        assertThat(PushDeliveryWorker.quietNow(Instant.parse("2026-11-01T06:30:00Z"), "America/New_York",
                LocalTime.of(1, 0), LocalTime.of(2, 0))).isTrue();
    }

    @Test void nextAttemptUsesWallClockBoundaryAcrossMidnightAndDst() {
        assertThat(PushQuietHours.nextAllowed(Instant.parse("2026-10-25T16:30:00Z"),
                "Asia/Ho_Chi_Minh", LocalTime.of(22, 0), LocalTime.of(7, 0)))
                .isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
        // Spring-forward 02:30 does not exist; the first legal instant is local 03:00.
        assertThat(PushQuietHours.nextAllowed(Instant.parse("2026-03-08T06:15:00Z"),
                "America/New_York", LocalTime.of(1, 0), LocalTime.of(2, 30)))
                .isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
        // Both occurrences of the repeated autumn hour must resolve to their own end boundary.
        assertThat(PushQuietHours.nextAllowed(Instant.parse("2026-11-01T05:30:00Z"),
                "America/New_York", LocalTime.of(1, 0), LocalTime.of(2, 0)))
                .isEqualTo(Instant.parse("2026-11-01T07:00:00Z"));
        assertThat(PushQuietHours.nextAllowed(Instant.parse("2026-11-01T06:30:00Z"),
                "America/New_York", LocalTime.of(1, 0), LocalTime.of(2, 0)))
                .isEqualTo(Instant.parse("2026-11-01T07:00:00Z"));
        // Falling back from 01:59 to 01:00 can itself leave [01:30,02:00) quiet hours.
        assertThat(PushQuietHours.nextAllowed(Instant.parse("2026-11-01T05:45:00Z"),
                "America/New_York", LocalTime.of(1, 30), LocalTime.of(2, 0)))
                .isEqualTo(Instant.parse("2026-11-01T06:00:00Z"));
    }

    @Test void categorizesOnlyApprovedFactFamilies() {
        assertThat(PushQueueWriter.category("CHAT_MESSAGE")).isEqualTo("CHAT");
        assertThat(PushQueueWriter.category("ATTENTION_DETECTED")).isEqualTo("ATTENTION_REVIEW");
        assertThat(PushQueueWriter.category("REVIEW_TASK_COMPLETED")).isEqualTo("ATTENTION_REVIEW");
        assertThat(PushQueueWriter.category("APPOINTMENT_CANCELLED")).isEqualTo("APPOINTMENT");
        assertThat(PushQueueWriter.category("AI_MARKETING")).isNull();
    }
}
