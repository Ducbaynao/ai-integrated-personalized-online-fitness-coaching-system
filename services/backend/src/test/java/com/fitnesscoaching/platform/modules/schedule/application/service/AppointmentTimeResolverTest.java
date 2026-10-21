package com.fitnesscoaching.platform.modules.schedule.application.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentTimeResolverTest {
    @Test void normalZoneAndExplicitOffsetProducePreciseInstants() {
        var interval = AppointmentTimeResolver.resolve(LocalDateTime.parse("2026-10-20T09:00:00"),
                LocalDateTime.parse("2026-10-20T10:00:00"), "Asia/Ho_Chi_Minh", "+07:00", "+07:00");
        assertThat(interval.startAt()).isEqualTo(Instant.parse("2026-10-20T02:00:00Z"));
        assertThat(interval.endAt()).isEqualTo(Instant.parse("2026-10-20T03:00:00Z"));
    }

    @Test void gapAndWrongOffsetAreRejected() {
        assertThatThrownBy(() -> AppointmentTimeResolver.resolve(LocalDateTime.parse("2026-03-08T02:30:00"),
                LocalDateTime.parse("2026-03-08T03:30:00"), "America/New_York", "-05:00", "-04:00"))
                .isInstanceOfSatisfying(AppointmentFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("APPOINTMENT_TIME_INVALID"));
        assertThatThrownBy(() -> AppointmentTimeResolver.resolve(LocalDateTime.parse("2026-10-20T09:00:00"),
                LocalDateTime.parse("2026-10-20T10:00:00"), "Asia/Ho_Chi_Minh", "+08:00", "+07:00"))
                .isInstanceOf(AppointmentFailure.class);
    }

    @Test void repeatedHourRequiresAValidExplicitChoiceForEachEndpoint() {
        var earlier = AppointmentTimeResolver.resolve(LocalDateTime.parse("2026-11-01T01:10:00"),
                LocalDateTime.parse("2026-11-01T01:50:00"), "America/New_York", "-04:00", "-04:00");
        var later = AppointmentTimeResolver.resolve(LocalDateTime.parse("2026-11-01T01:10:00"),
                LocalDateTime.parse("2026-11-01T01:50:00"), "America/New_York", "-05:00", "-05:00");
        assertThat(later.startAt()).isAfter(earlier.startAt());
        assertThatThrownBy(() -> AppointmentTimeResolver.resolve(LocalDateTime.parse("2026-11-01T01:50:00"),
                LocalDateTime.parse("2026-11-01T01:10:00"), "America/New_York", "-05:00", "-04:00"))
                .isInstanceOf(AppointmentFailure.class);
    }
}
