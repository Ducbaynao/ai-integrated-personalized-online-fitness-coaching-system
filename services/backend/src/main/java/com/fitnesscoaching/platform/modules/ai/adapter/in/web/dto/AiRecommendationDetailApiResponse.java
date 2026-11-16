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
        JsonNode originalPayload,
        boolean sharedWithStudent,
        Integer sharedRevision,
        Instant sharedAt,
        UUID sharedBy
) {
    @com.fasterxml.jackson.annotation.JsonProperty("recommendationId")
    public UUID recommendationId() {
        return id;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("aiRunId")
    public UUID aiRunId() {
        return runId;
    }

    public AiRecommendationDetailApiResponse(
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
    ) {
        this(
                id, runId, studentId, coachingPeriodId, recommendationType,
                title, explanation, status, decisionAuthority, targetEntityType,
                targetEntityId, decidedBy, decidedAt, decisionNote, expiresAt,
                revision, isCustomized, lastModifiedBy, createdAt, updatedAt,
                structuredPayload, originalPayload, false, null, null, null
        );
    }
}
