package com.fitnesscoaching.platform.modules.ai.domain;

import java.time.Instant;
import java.util.UUID;

public record AiRecommendation(
        UUID id,
        UUID aiRunId,
        UUID studentId,
        UUID coachingPeriodId,
        String recommendationType,
        String title,
        String explanation,
        String structuredPayload,
        AiRecommendationStatus status,
        AiDecisionAuthority decisionAuthority,
        String targetEntityType,
        UUID targetEntityId,
        UUID decidedBy,
        Instant decidedAt,
        String decisionNote,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static Builder builder() {
        return new Builder();
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public static class Builder {
        private UUID id;
        private UUID aiRunId;
        private UUID studentId;
        private UUID coachingPeriodId;
        private String recommendationType = "WORKOUT_PLAN_PROPOSAL";
        private String title;
        private String explanation;
        private String structuredPayload = "{}";
        private AiRecommendationStatus status = AiRecommendationStatus.PENDING;
        private AiDecisionAuthority decisionAuthority;
        private String targetEntityType;
        private UUID targetEntityId;
        private UUID decidedBy;
        private Instant decidedAt;
        private String decisionNote;
        private Instant expiresAt;
        private Instant createdAt;
        private Instant updatedAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder aiRunId(UUID aiRunId) { this.aiRunId = aiRunId; return this; }
        public Builder studentId(UUID studentId) { this.studentId = studentId; return this; }
        public Builder coachingPeriodId(UUID coachingPeriodId) { this.coachingPeriodId = coachingPeriodId; return this; }
        public Builder recommendationType(String recommendationType) { this.recommendationType = recommendationType; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder explanation(String explanation) { this.explanation = explanation; return this; }
        public Builder structuredPayload(String structuredPayload) { this.structuredPayload = structuredPayload; return this; }
        public Builder status(AiRecommendationStatus status) { this.status = status; return this; }
        public Builder decisionAuthority(AiDecisionAuthority decisionAuthority) { this.decisionAuthority = decisionAuthority; return this; }
        public Builder targetEntityType(String targetEntityType) { this.targetEntityType = targetEntityType; return this; }
        public Builder targetEntityId(UUID targetEntityId) { this.targetEntityId = targetEntityId; return this; }
        public Builder decidedBy(UUID decidedBy) { this.decidedBy = decidedBy; return this; }
        public Builder decidedAt(Instant decidedAt) { this.decidedAt = decidedAt; return this; }
        public Builder decisionNote(String decisionNote) { this.decisionNote = decisionNote; return this; }
        public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public AiRecommendation build() {
            return new AiRecommendation(
                    id != null ? id : UUID.randomUUID(),
                    aiRunId,
                    studentId,
                    coachingPeriodId,
                    recommendationType,
                    title,
                    explanation,
                    structuredPayload,
                    status != null ? status : AiRecommendationStatus.PENDING,
                    decisionAuthority,
                    targetEntityType,
                    targetEntityId,
                    decidedBy,
                    decidedAt,
                    decisionNote,
                    expiresAt,
                    createdAt != null ? createdAt : Instant.now(),
                    updatedAt != null ? updatedAt : Instant.now()
            );
        }
    }
}
