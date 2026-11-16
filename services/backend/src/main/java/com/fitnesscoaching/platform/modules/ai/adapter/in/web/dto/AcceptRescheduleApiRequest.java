package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.util.UUID;

public record AcceptRescheduleApiRequest(
        String commandKey,
        String decisionNote,
        Integer expectedRevision,
        Long expectedOccurrenceVersion,
        Long expectedAppointmentVersion,
        UUID expectedAppointmentId
) {}
