package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;

import java.time.Instant;
import java.util.UUID;

public record RejectRecommendationApiResponse(
        UUID recommendationId,
        AiRecommendationStatus status,
        UUID decidedBy,
        Instant decidedAt,
        String decisionNote
) {}
