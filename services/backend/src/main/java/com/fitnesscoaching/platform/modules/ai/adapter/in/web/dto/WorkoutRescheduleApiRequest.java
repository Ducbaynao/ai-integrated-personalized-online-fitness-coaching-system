package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.util.UUID;

public record WorkoutRescheduleApiRequest(
        UUID studentId,
        UUID occurrenceId,
        Long expectedOccurrenceVersion,
        String commandKey,
        String reason,
        String preferredTimeOfDay
) {}
