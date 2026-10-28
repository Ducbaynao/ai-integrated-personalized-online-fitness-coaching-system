package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.util.Map;
import java.util.UUID;

public record WorkoutProposalApiRequest(
        UUID studentId,
        String commandKey,
        Map<String, Object> preferences
) {}
