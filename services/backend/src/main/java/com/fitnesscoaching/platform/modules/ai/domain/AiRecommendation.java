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
        int revision,
        String originalPayload,
        boolean isCustomized,
        UUID lastModifiedBy,
        String sourceSnapshot,
        Instant createdAt,
        Instant updatedAt,
        boolean sharedWithStudent,
        Integer sharedRevision,
        Instant sharedAt,
        UUID sharedBy,
        String sharedTitle,
        String sharedExplanation,
        String sharedPayload
) {
    public static Builder builder() {
        return new Builder();
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public AiRecommendation withSharedView(int rev, String sharedTitle, String sharedExplanation, String payload, Instant sharedAt, UUID sharedBy) {
        boolean customized = rev > 1;
        return withSharedView(rev, sharedTitle, sharedExplanation, payload, sharedAt, sharedBy, customized);
    }

    public AiRecommendation withSharedView(
            int rev,
            String sharedTitle,
            String sharedExplanation,
            String payload,
            Instant sharedAt,
            UUID sharedBy,
            boolean isCustomized
    ) {
        return new AiRecommendation(
                id,
                aiRunId,
                studentId,
                coachingPeriodId,
                recommendationType,
                sharedTitle,
                sharedExplanation,
                payload,
                status,
                decisionAuthority,
                targetEntityType,
                targetEntityId,
                decidedBy,
                decidedAt,
                decisionNote,
                expiresAt,
                rev,
                originalPayload,
                isCustomized,
                sharedBy,
                sourceSnapshot,
                createdAt,
                sharedAt,
                sharedWithStudent,
                rev,
                sharedAt,
                sharedBy,
                sharedTitle,
                sharedExplanation,
                payload
        );
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
        private int revision = 1;
        private String originalPayload;
        private boolean isCustomized = false;
        private UUID lastModifiedBy;
        private String sourceSnapshot;
        private Instant createdAt;
        private Instant updatedAt;
        private boolean sharedWithStudent = false;
        private Integer sharedRevision;
        private Instant sharedAt;
        private UUID sharedBy;
        private String sharedTitle;
        private String sharedExplanation;
        private String sharedPayload;

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
        public Builder revision(int revision) { this.revision = revision; return this; }
        public Builder originalPayload(String originalPayload) { this.originalPayload = originalPayload; return this; }
        public Builder isCustomized(boolean isCustomized) { this.isCustomized = isCustomized; return this; }
        public Builder lastModifiedBy(UUID lastModifiedBy) { this.lastModifiedBy = lastModifiedBy; return this; }
        public Builder sourceSnapshot(String sourceSnapshot) { this.sourceSnapshot = sourceSnapshot; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public Builder sharedWithStudent(boolean sharedWithStudent) { this.sharedWithStudent = sharedWithStudent; return this; }
        public Builder sharedRevision(Integer sharedRevision) { this.sharedRevision = sharedRevision; return this; }
        public Builder sharedAt(Instant sharedAt) { this.sharedAt = sharedAt; return this; }
        public Builder sharedBy(UUID sharedBy) { this.sharedBy = sharedBy; return this; }
        public Builder sharedTitle(String sharedTitle) { this.sharedTitle = sharedTitle; return this; }
        public Builder sharedExplanation(String sharedExplanation) { this.sharedExplanation = sharedExplanation; return this; }
        public Builder sharedPayload(String sharedPayload) { this.sharedPayload = sharedPayload; return this; }

        public AiRecommendation build() {
            String orig = originalPayload != null ? originalPayload : structuredPayload;
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
                    revision,
                    orig,
                    isCustomized,
                    lastModifiedBy,
                    sourceSnapshot,
                    createdAt != null ? createdAt : Instant.now(),
                    updatedAt != null ? updatedAt : Instant.now(),
                    sharedWithStudent,
                    sharedRevision,
                    sharedAt,
                    sharedBy,
                    sharedTitle,
                    sharedExplanation,
                    sharedPayload
            );
        }
    }
}
