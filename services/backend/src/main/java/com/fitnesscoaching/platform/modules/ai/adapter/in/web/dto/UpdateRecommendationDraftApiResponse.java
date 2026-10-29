package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import tools.jackson.databind.JsonNode;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;

import java.time.Instant;
import java.util.UUID;

public record UpdateRecommendationDraftApiResponse(
        UUID recommendationId,
        int revision,
        boolean isCustomized,
        AiRecommendationStatus status,
        String title,
        String explanation,
        Instant updatedAt,
        JsonNode structuredPayload,
        JsonNode originalPayload
) {}
