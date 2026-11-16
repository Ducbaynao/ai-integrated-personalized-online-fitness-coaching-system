package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record F05WorkoutReschedulePayload(
        String recommendationType,
        String summary,
        List<String> reasoning,
        List<String> usedData,
        List<String> missingData,
        List<String> limitations,
        Double confidence,
        RescheduleDetail reschedule
) {
    public record RescheduleDetail(
            UUID occurrenceId,
            UUID planSessionId,
            String sessionName,
            String previousPlannedStartAt,
            String originalPlannedStartAt,
            String supervisionRequirement,
            DestinationDto destination
    ) {}

    public record DestinationDto(
            String localStart,
            String localEnd,
            String timezone,
            String startUtcOffset,
            String endUtcOffset,
            Instant proposedStartAt,
            Instant proposedEndAt
    ) {}
}
