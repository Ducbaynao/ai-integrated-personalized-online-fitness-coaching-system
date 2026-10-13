package com.fitnesscoaching.platform.modules.content.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeDocumentResponse(
        UUID id,
        String documentCode,
        String title,
        String sourceId,
        String sourceName,
        String sourceUrl,
        String author,
        String documentType,
        String language,
        Instant createdAt
) {
    public static KnowledgeDocumentResponse fromDomain(KnowledgeDocument d) {
        return new KnowledgeDocumentResponse(
                d.id(),
                d.documentCode(),
                d.title(),
                d.sourceId(),
                d.sourceName(),
                d.sourceUrl(),
                d.author(),
                d.documentType(),
                d.language(),
                d.createdAt()
        );
    }
}
