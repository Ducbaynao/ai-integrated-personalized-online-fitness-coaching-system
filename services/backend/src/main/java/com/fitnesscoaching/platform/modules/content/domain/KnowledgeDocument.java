package com.fitnesscoaching.platform.modules.content.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity representing a governed knowledge document.
 */
public record KnowledgeDocument(
        UUID id,
        String documentCode,
        String title,
        String sourceId,
        String sourceName,
        String sourceUrl,
        String author,
        String documentType,
        String language,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID id;
        private String documentCode;
        private String title;
        private String sourceId;
        private String sourceName;
        private String sourceUrl;
        private String author;
        private String documentType;
        private String language = "vi";
        private UUID createdBy;
        private Instant createdAt;
        private Instant updatedAt;
        private Instant deletedAt;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder documentCode(String documentCode) {
            this.documentCode = documentCode;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public Builder sourceName(String sourceName) {
            this.sourceName = sourceName;
            return this;
        }

        public Builder sourceUrl(String sourceUrl) {
            this.sourceUrl = sourceUrl;
            return this;
        }

        public Builder author(String author) {
            this.author = author;
            return this;
        }

        public Builder documentType(String documentType) {
            this.documentType = documentType;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
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

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Builder deletedAt(Instant deletedAt) {
            this.deletedAt = deletedAt;
            return this;
        }

        public KnowledgeDocument build() {
            return new KnowledgeDocument(
                    id, documentCode, title, sourceId, sourceName, sourceUrl,
                    author, documentType, language, createdBy, createdAt, updatedAt, deletedAt
            );
        }
    }
}
