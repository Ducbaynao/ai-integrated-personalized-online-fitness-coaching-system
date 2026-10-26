package com.fitnesscoaching.platform.modules.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.List;

/** Computes the first permitted instant from the user's explicit IANA-zone wall-clock interval. */
final class PushQuietHours {
    private PushQuietHours() {}

    static boolean isQuiet(Instant at, String timezone, LocalTime start, LocalTime end) {
        if (start == null || end == null) return false;
        LocalTime local = at.atZone(ZoneId.of(timezone)).toLocalTime();
        if (start.isBefore(end)) return !local.isBefore(start) && local.isBefore(end);
        return !local.isBefore(start) || local.isBefore(end);
    }

    static Instant nextAllowed(Instant now, String timezone, LocalTime start, LocalTime end) {
        if (!isQuiet(now, timezone, start, end)) return now;
        ZoneId zone = ZoneId.of(timezone);
        ZoneRules rules = zone.getRules();
        LocalDate today = now.atZone(zone).toLocalDate();
        List<Instant> candidates = new ArrayList<>();
        // Include both offsets of a repeated wall-clock end; a gap begins at its first valid instant.
        for (int day = -1; day <= 3; day++) {
            LocalDateTime boundary = today.plusDays(day).atTime(end);
            List<ZoneOffset> offsets = rules.getValidOffsets(boundary);
            if (offsets.isEmpty()) {
                candidates.add(rules.getTransition(boundary).getInstant());
            } else {
                for (ZoneOffset offset : offsets) candidates.add(boundary.toInstant(offset));
            }
        }
        // A backward clock jump can leave quiet hours at the transition itself,
        // even though the configured local end has not been reached yet.
        ZoneOffsetTransition transition = rules.nextTransition(now);
        Instant horizon = now.plusSeconds(4L * 24 * 60 * 60);
        while (transition != null && !transition.getInstant().isAfter(horizon)) {
            candidates.add(transition.getInstant());
            transition = rules.nextTransition(transition.getInstant());
        }
        return candidates.stream().filter(candidate -> candidate.isAfter(now))
                .filter(candidate -> !isQuiet(candidate, timezone, start, end))
                .min(Instant::compareTo)
                .orElseThrow(() -> new IllegalStateException("No permitted quiet-hours boundary"));
    }
}
