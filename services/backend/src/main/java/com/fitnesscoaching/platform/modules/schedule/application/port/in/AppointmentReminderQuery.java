package com.fitnesscoaching.platform.modules.schedule.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Schedule-owned read boundary for timed Appointment reminders. */
public interface AppointmentReminderQuery {
    record Slot(UUID id, long version, UUID student, UUID trainer, UUID period, Instant startAt) {}
    List<Slot> startingBetween(Instant after, Instant through);
    Optional<Slot> confirmed(UUID id);
}
