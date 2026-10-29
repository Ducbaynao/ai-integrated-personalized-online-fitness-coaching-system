package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;

import java.time.Instant;
import java.util.UUID;

public record AcceptRecommendationApiResponse(
        UUID recommendationId,
        AiRecommendationStatus status,
        UUID appliedBy,
        String resultingEntityType,
        UUID resultingEntityId,
        Instant appliedAt,
        boolean replayed
) {}
