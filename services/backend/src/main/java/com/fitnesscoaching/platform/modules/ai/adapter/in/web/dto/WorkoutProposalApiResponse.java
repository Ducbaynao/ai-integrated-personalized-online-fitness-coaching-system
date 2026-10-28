package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import tools.jackson.databind.JsonNode;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;

import java.time.Instant;
import java.util.UUID;

public record WorkoutProposalApiResponse(
        UUID recommendationId,
        UUID runId,
        AiRecommendationStatus status,
        AiDecisionAuthority decisionAuthority,
        String title,
        String explanation,
        Instant expiresAt,
        Instant createdAt,
        JsonNode structuredPayload
) {}
