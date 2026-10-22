package com.fitnesscoaching.platform.modules.workout.application.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class WorkoutRecurrenceTimeResolverTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test void ordinaryLocalTimeHasExactlyOneOffset() {
        var result = WorkoutRecurrenceTimeResolver.resolve(LocalDateTime.of(2026, 2, 9, 9, 30), NEW_YORK);
        assertThat(result.status()).isEqualTo(WorkoutRecurrenceTimeResolver.Status.READY);
        assertThat(result.offset()).isEqualTo(ZoneOffset.of("-05:00"));
        assertThat(result.instant()).isEqualTo("2026-02-09T14:30:00Z");
    }

    @Test void springGapDoesNotInventAnOffset() {
        var result = WorkoutRecurrenceTimeResolver.resolve(LocalDateTime.of(2027, 3, 14, 2, 30), NEW_YORK);
        assertThat(result.status()).isEqualTo(WorkoutRecurrenceTimeResolver.Status.DST_GAP);
        assertThat(result.instant()).isNull();
        assertThat(result.offset()).isNull();
    }

    @Test void autumnRepeatedHourDoesNotChooseAnOffset() {
        var result = WorkoutRecurrenceTimeResolver.resolve(LocalDateTime.of(2026, 11, 1, 1, 30), NEW_YORK);
        assertThat(result.status()).isEqualTo(WorkoutRecurrenceTimeResolver.Status.DST_AMBIGUOUS);
        assertThat(result.instant()).isNull();
        assertThat(result.offset()).isNull();
    }
}
