package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;

import java.time.Instant;
import java.util.UUID;

public record RejectAdjustmentApiResponse(
        UUID recommendationId,
        AiRecommendationStatus status,
        UUID rejectedBy,
        String reason,
        Instant rejectedAt
) {}
