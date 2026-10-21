package com.fitnesscoaching.platform.modules.schedule.application.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Resolves explicit local intent; neither a device zone nor a guessed DST offset is accepted. */
public final class AppointmentTimeResolver {
    private AppointmentTimeResolver() {}

    public record Interval(Instant startAt, Instant endAt) {}

    public static Interval resolve(LocalDateTime start, LocalDateTime end, String timezone,
                                   String startOffset, String endOffset) {
        if (start == null || end == null || timezone == null || startOffset == null || endOffset == null)
            throw invalid();
        try {
            if (!ZoneId.getAvailableZoneIds().contains(timezone)) throw invalid();
            ZoneId zone = ZoneId.of(timezone);
            Instant startAt = instant(start, startOffset, zone);
            Instant endAt = instant(end, endOffset, zone);
            if (!endAt.isAfter(startAt)) throw invalid();
            return new Interval(startAt, endAt);
        } catch (DateTimeException exception) {
            throw invalid();
        }
    }

    private static Instant instant(LocalDateTime local, String offsetText, ZoneId zone) {
        if (!offsetText.matches("[+-](0\\d|1[0-4]):[0-5]\\d")) throw invalid();
        ZoneOffset offset = ZoneOffset.of(offsetText);
        if (!zone.getRules().getValidOffsets(local).contains(offset)) throw invalid();
        return local.toInstant(offset).truncatedTo(ChronoUnit.MICROS);
    }

    private static AppointmentFailure invalid() {
        return new AppointmentFailure("APPOINTMENT_TIME_INVALID", 400);
    }
}
