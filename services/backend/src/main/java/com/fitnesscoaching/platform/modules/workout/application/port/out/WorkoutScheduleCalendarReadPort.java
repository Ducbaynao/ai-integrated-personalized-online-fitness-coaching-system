package com.fitnesscoaching.platform.modules.workout.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkoutScheduleCalendarReadPort {
    Page findStudentWindow(UUID studentId, Instant from, Instant until, int page, int size);
    Row findOccurrence(UUID occurrenceId);
    boolean hasInProgressExecution(UUID studentId);

    record PlanSummary(UUID planId, UUID planVersionId, int planVersionNumber, String planName) {}
    record SessionSummary(UUID planSessionId, int weekNumber, int dayNumber, int sequenceNumber,
                          String name, String focus, Integer estimatedDurationMinutes,
                          String supervisionRequirement) {}
    record Row(UUID occurrenceId, long occurrenceVersion, UUID studentId, String status,
               Instant plannedStartAt, Instant originalPlannedStartAt, UUID rescheduledFromOccurrenceId,
               UUID replacementOccurrenceId, Instant plannedEndAt,
               String timezone, String localStart, String localEnd, String startUtcOffset,
               String endUtcOffset, PlanSummary planSummary, SessionSummary sessionSummary,
               Instant executionStartedAt, String supervisionRequirement, boolean hasValidSupervisionOverride) {}
    record Page(List<Row> items, long totalItems) {}
}
