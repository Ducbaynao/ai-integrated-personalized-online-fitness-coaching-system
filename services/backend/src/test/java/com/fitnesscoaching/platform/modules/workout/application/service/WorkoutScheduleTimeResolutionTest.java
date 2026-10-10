package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkoutScheduleTimeResolutionTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test void ambiguousLocalTimeUsesSuppliedValidOffset() {
        LocalDateTime wallTime = LocalDateTime.of(2026, 11, 1, 1, 30);
        UUID item = UUID.randomUUID();
        var first = WorkoutScheduleBatchService.resolveInstant(wallTime, "-04:00", NEW_YORK, item);
        var second = WorkoutScheduleBatchService.resolveInstant(wallTime, "-05:00", NEW_YORK, item);
        assertThat(second).isEqualTo(first.plusSeconds(3600));
        assertThatThrownBy(() -> WorkoutScheduleBatchService.resolveInstant(wallTime, "-03:00", NEW_YORK, item))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code").isEqualTo("VALIDATION_FAILED");
    }

    @Test void daylightSavingGapAndInvalidOffsetAreRejected() {
        LocalDateTime gap = LocalDateTime.of(2027, 3, 14, 2, 30);
        UUID item = UUID.randomUUID();
        assertThatThrownBy(() -> WorkoutScheduleBatchService.resolveInstant(gap, "-05:00", NEW_YORK, item))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code").isEqualTo("VALIDATION_FAILED");
        assertThatThrownBy(() -> WorkoutScheduleBatchService.resolveInstant(
                LocalDateTime.of(2026, 11, 1, 1, 30), "Z", NEW_YORK, item))
                .isInstanceOf(WorkoutScheduleFailure.class).extracting("code").isEqualTo("VALIDATION_FAILED");
    }
}
