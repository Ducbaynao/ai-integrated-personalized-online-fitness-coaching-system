package com.fitnesscoaching.platform.modules.workout.application.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Resolves an automatic period only when the named zone has one valid offset. */
final class WorkoutRecurrenceTimeResolver {
    private WorkoutRecurrenceTimeResolver() {}

    enum Status { READY, DST_GAP, DST_AMBIGUOUS }

    record Result(Status status, Instant instant, ZoneOffset offset) {
        static Result unresolved(Status status) { return new Result(status, null, null); }
    }

    static Result resolve(LocalDateTime local, ZoneId zone) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) return Result.unresolved(Status.DST_GAP);
        if (offsets.size() != 1) return Result.unresolved(Status.DST_AMBIGUOUS);
        ZoneOffset offset = offsets.getFirst();
        return new Result(Status.READY, local.toInstant(offset).truncatedTo(ChronoUnit.MICROS), offset);
    }
}
