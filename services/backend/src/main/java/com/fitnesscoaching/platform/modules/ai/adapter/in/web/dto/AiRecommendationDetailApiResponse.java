package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import tools.jackson.databind.JsonNode;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;

import java.time.Instant;
import java.util.UUID;

public record AiRecommendationDetailApiResponse(
        UUID id,
        UUID runId,
        UUID studentId,
        UUID coachingPeriodId,
        String recommendationType,
        String title,
        String explanation,
        AiRecommendationStatus status,
        AiDecisionAuthority decisionAuthority,
        String targetEntityType,
        UUID targetEntityId,
        UUID decidedBy,
        Instant decidedAt,
        String decisionNote,
        Instant expiresAt,
        int revision,
        boolean isCustomized,
        UUID lastModifiedBy,
        Instant createdAt,
        Instant updatedAt,
        JsonNode structuredPayload,
        JsonNode originalPayload
) {}
