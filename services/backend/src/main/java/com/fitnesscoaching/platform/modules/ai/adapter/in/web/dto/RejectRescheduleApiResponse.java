package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;

import java.time.Instant;
import java.util.UUID;

public record RejectRescheduleApiResponse(
        UUID recommendationId,
        AiRecommendationStatus status,
        Instant rejectedAt
) {}
