package com.fitnesscoaching.platform.modules.content.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity representing a governed knowledge version.
 * Encapsulates publication status, technical readiness, hold reason, provenance, and content.
 */
public record KnowledgeVersion(
        UUID id,
        UUID knowledgeDocumentId,
        int versionNumber,
        String versionLabel,
        KnowledgeStatus status,
        TechnicalReadiness technicalReadiness,
        String holdReason,
        String contentHash,
        String contentPath,
        Integer sourceBytes,
        String reviewEvidenceId,
        String lineageBaseDocumentId,
        String lineageBaseVersion,
        String lineageBaseHash,
        String content,
        UUID reviewedBy,
        Instant reviewedAt,
        UUID publishedBy,
        Instant publishedAt,
        Instant sourcePublishedAt,
        Instant activatedAt,
        Instant archivedAt,
        UUID createdBy,
        Instant createdAt
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID id;
        private UUID knowledgeDocumentId;
        private int versionNumber = 1;
        private String versionLabel = "1.0.0";
        private KnowledgeStatus status = KnowledgeStatus.DRAFT;
        private TechnicalReadiness technicalReadiness = TechnicalReadiness.DRAFT_STAGED;
        private String holdReason;
        private String contentHash;
        private String contentPath;
        private Integer sourceBytes;
        private String reviewEvidenceId;
        private String lineageBaseDocumentId;
        private String lineageBaseVersion;
        private String lineageBaseHash;
        private String content;
        private UUID reviewedBy;
        private Instant reviewedAt;
        private UUID publishedBy;
        private Instant publishedAt;
        private Instant sourcePublishedAt;
        private Instant activatedAt;
        private Instant archivedAt;
        private UUID createdBy;
        private Instant createdAt;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder knowledgeDocumentId(UUID knowledgeDocumentId) {
            this.knowledgeDocumentId = knowledgeDocumentId;
            return this;
        }

        public Builder versionNumber(int versionNumber) {
            this.versionNumber = versionNumber;
            return this;
        }

        public Builder versionLabel(String versionLabel) {
            this.versionLabel = versionLabel;
            return this;
        }

        public Builder status(KnowledgeStatus status) {
            this.status = status;
            return this;
        }

        public Builder technicalReadiness(TechnicalReadiness technicalReadiness) {
            this.technicalReadiness = technicalReadiness;
            return this;
        }

        public Builder holdReason(String holdReason) {
            this.holdReason = holdReason;
            return this;
        }

        public Builder contentHash(String contentHash) {
            this.contentHash = contentHash;
            return this;
        }

        public Builder contentPath(String contentPath) {
            this.contentPath = contentPath;
            return this;
        }

        public Builder sourceBytes(Integer sourceBytes) {
            this.sourceBytes = sourceBytes;
            return this;
        }

        public Builder reviewEvidenceId(String reviewEvidenceId) {
            this.reviewEvidenceId = reviewEvidenceId;
            return this;
        }

        public Builder lineageBaseDocumentId(String lineageBaseDocumentId) {
            this.lineageBaseDocumentId = lineageBaseDocumentId;
            return this;
        }

        public Builder lineageBaseVersion(String lineageBaseVersion) {
            this.lineageBaseVersion = lineageBaseVersion;
            return this;
        }

        public Builder lineageBaseHash(String lineageBaseHash) {
            this.lineageBaseHash = lineageBaseHash;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder reviewedBy(UUID reviewedBy) {
            this.reviewedBy = reviewedBy;
            return this;
        }

        public Builder reviewedAt(Instant reviewedAt) {
            this.reviewedAt = reviewedAt;
            return this;
        }

        public Builder publishedBy(UUID publishedBy) {
            this.publishedBy = publishedBy;
            return this;
        }

        public Builder publishedAt(Instant publishedAt) {
            this.publishedAt = publishedAt;
            return this;
        }

        public Builder sourcePublishedAt(Instant sourcePublishedAt) {
            this.sourcePublishedAt = sourcePublishedAt;
            return this;
        }

        public Builder activatedAt(Instant activatedAt) {
            this.activatedAt = activatedAt;
            return this;
        }

        public Builder archivedAt(Instant archivedAt) {
            this.archivedAt = archivedAt;
            return this;
        }

        public Builder createdBy(UUID createdBy) {
            this.createdBy = createdBy;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public KnowledgeVersion build() {
            return new KnowledgeVersion(
                    id, knowledgeDocumentId, versionNumber, versionLabel,
                    status, technicalReadiness, holdReason, contentHash, contentPath,
                    sourceBytes, reviewEvidenceId, lineageBaseDocumentId, lineageBaseVersion,
                    lineageBaseHash, content, reviewedBy, reviewedAt, publishedBy, publishedAt,
                    sourcePublishedAt, activatedAt, archivedAt, createdBy, createdAt
            );
        }
    }
}
