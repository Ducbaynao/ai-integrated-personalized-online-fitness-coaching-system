package com.fitnesscoaching.platform.modules.workout.application.port.in;

import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleCalendarReadPort.PlanSummary;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleCalendarReadPort.SessionSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkoutScheduleCalendarQuery {
    Page listMine(UUID actorId, Instant from, Instant until, int page, int size);
    Page listForTrainer(UUID actorId, UUID studentId, Instant from, Instant until, int page, int size);

    record Occurrence(UUID occurrenceId, long occurrenceVersion, UUID studentId, String status,
                      Instant plannedStartAt, Instant originalPlannedStartAt, Instant plannedEndAt,
                      String timezone, String localStart, String localEnd, String startUtcOffset,
                      String endUtcOffset, PlanSummary planSummary, SessionSummary sessionSummary,
                      List<String> availableActions) {}
    record Page(List<Occurrence> items, int page, int size, long totalItems, int totalPages) {}
}
