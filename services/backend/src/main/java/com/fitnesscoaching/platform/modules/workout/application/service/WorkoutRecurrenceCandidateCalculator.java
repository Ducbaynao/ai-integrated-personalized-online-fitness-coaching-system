package com.fitnesscoaching.platform.modules.workout.application.service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

/** Maps a selected published-plan session into a numbered repeat of the full plan-week block. */
final class WorkoutRecurrenceCandidateCalculator {
    private WorkoutRecurrenceCandidateCalculator() {}

    enum Outcome { READY, DST_GAP, DST_AMBIGUOUS, INVALID_INTERVAL }

    record Candidate(long blockIteration, UUID planSessionId, LocalDateTime localStart,
                     LocalDateTime localEnd, ZoneOffset startOffset, ZoneOffset endOffset,
                     Instant start, Instant end, Outcome outcome) {}

    static Candidate calculate(LocalDate mondayAnchor, int planWeeks, long blockIteration,
                               UUID planSessionId, int weekNumber, int dayNumber,
                               LocalDateTime firstLocalStart, LocalDateTime firstLocalEnd, ZoneId zone) {
        if (mondayAnchor == null || mondayAnchor.getDayOfWeek() != DayOfWeek.MONDAY
                || planWeeks < 1 || blockIteration < 0 || planSessionId == null
                || weekNumber < 1 || weekNumber > planWeeks || dayNumber < 1 || dayNumber > 7
                || firstLocalStart == null || firstLocalEnd == null || zone == null
                || !firstLocalEnd.isAfter(firstLocalStart)) {
            throw new IllegalArgumentException("Invalid recurrence source coordinates or local intent");
        }
        try {
            if (!firstLocalStart.toLocalDate().equals(
                    mondayAnchor.plusWeeks(weekNumber - 1L).plusDays(dayNumber - 1L))) {
                throw new IllegalArgumentException("First occurrence does not match source coordinates");
            }
            long elapsedWeeks = Math.multiplyExact(blockIteration, (long) planWeeks);
            LocalDateTime localStart = firstLocalStart.plusWeeks(elapsedWeeks);
            LocalDateTime localEnd = firstLocalEnd.plusWeeks(elapsedWeeks);
            var start = WorkoutRecurrenceTimeResolver.resolve(localStart, zone);
            var end = WorkoutRecurrenceTimeResolver.resolve(localEnd, zone);
            if (start.status() != WorkoutRecurrenceTimeResolver.Status.READY
                    || end.status() != WorkoutRecurrenceTimeResolver.Status.READY) {
                Outcome outcome = start.status() == WorkoutRecurrenceTimeResolver.Status.DST_GAP
                        || end.status() == WorkoutRecurrenceTimeResolver.Status.DST_GAP
                        ? Outcome.DST_GAP : Outcome.DST_AMBIGUOUS;
                return new Candidate(blockIteration, planSessionId, localStart, localEnd,
                        null, null, null, null, outcome);
            }
            if (!end.instant().isAfter(start.instant())) {
                return new Candidate(blockIteration, planSessionId, localStart, localEnd,
                        null, null, null, null, Outcome.INVALID_INTERVAL);
            }
            return new Candidate(blockIteration, planSessionId, localStart, localEnd,
                    start.offset(), end.offset(), start.instant(), end.instant(), Outcome.READY);
        } catch (ArithmeticException | DateTimeException overflow) {
            throw new IllegalArgumentException("Recurrence iteration exceeds supported time range", overflow);
        }
    }
}
