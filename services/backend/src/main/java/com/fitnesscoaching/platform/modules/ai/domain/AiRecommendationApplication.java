package com.fitnesscoaching.platform.modules.ai.domain;

import java.time.Instant;
import java.util.UUID;

public record AiRecommendationApplication(
        UUID id,
        UUID aiRecommendationId,
        UUID appliedBy,
        String resultingEntityType,
        UUID resultingEntityId,
        String applicationPayload,
        Instant appliedAt
) {}
