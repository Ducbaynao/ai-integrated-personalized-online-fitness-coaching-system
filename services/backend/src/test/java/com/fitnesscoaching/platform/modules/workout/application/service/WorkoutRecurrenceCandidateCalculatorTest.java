package com.fitnesscoaching.platform.modules.workout.application.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkoutRecurrenceCandidateCalculatorTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);
    private static final UUID SESSION = UUID.randomUUID();

    @Test void repeatsEntireThreeWeekBlockIncludingAnUnselectedWeek() {
        var first = WorkoutRecurrenceCandidateCalculator.calculate(MONDAY, 3, 0, SESSION, 2, 3,
                LocalDateTime.of(2026, 11, 4, 9, 0), LocalDateTime.of(2026, 11, 4, 10, 0), UTC);
        var next = WorkoutRecurrenceCandidateCalculator.calculate(MONDAY, 3, 1, SESSION, 2, 3,
                LocalDateTime.of(2026, 11, 4, 9, 0), LocalDateTime.of(2026, 11, 4, 10, 0), UTC);
        assertThat(first.localStart()).isEqualTo(LocalDateTime.of(2026, 11, 4, 9, 0));
        assertThat(next.localStart()).isEqualTo(LocalDateTime.of(2026, 11, 25, 9, 0));
        assertThat(next.start()).isEqualTo(first.start().plusSeconds(21L * 24 * 3600));
    }

    @Test void rejectsASelectedSessionMappedToTheWrongPlanWeekOrDay() {
        assertThatThrownBy(() -> WorkoutRecurrenceCandidateCalculator.calculate(MONDAY, 3, 0, SESSION, 2, 3,
                LocalDateTime.of(2026, 10, 28, 9, 0), LocalDateTime.of(2026, 10, 28, 10, 0), UTC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void repeatedHourIsUnresolvedButTheFollowingBlockCanResolve() {
        var result = WorkoutRecurrenceCandidateCalculator.calculate(LocalDate.of(2026, 10, 26), 1, 1,
                SESSION, 1, 7, LocalDateTime.of(2026, 11, 1, 1, 30),
                LocalDateTime.of(2026, 11, 1, 2, 30), ZoneId.of("America/New_York"));
        assertThat(result.outcome()).isEqualTo(WorkoutRecurrenceCandidateCalculator.Outcome.READY);
        var first = WorkoutRecurrenceCandidateCalculator.calculate(LocalDate.of(2026, 10, 26), 1, 0,
                SESSION, 1, 7, LocalDateTime.of(2026, 11, 1, 1, 30),
                LocalDateTime.of(2026, 11, 1, 2, 30), ZoneId.of("America/New_York"));
        assertThat(first.outcome()).isEqualTo(WorkoutRecurrenceCandidateCalculator.Outcome.DST_AMBIGUOUS);
        assertThat(first.start()).isNull();
    }
}
