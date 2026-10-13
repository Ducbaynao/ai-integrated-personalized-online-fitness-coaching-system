package com.fitnesscoaching.platform.modules.content.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeVersionResponse(
        UUID id,
        UUID knowledgeDocumentId,
        int versionNumber,
        String versionLabel,
        String status,
        String technicalReadiness,
        String holdReason,
        String contentHash,
        String contentPath,
        Integer sourceBytes,
        String reviewEvidenceId,
        String lineageBaseDocumentId,
        String lineageBaseVersion,
        String content,
        UUID reviewedBy,
        Instant reviewedAt,
        UUID publishedBy,
        Instant publishedAt,
        Instant activatedAt,
        Instant archivedAt,
        Instant createdAt
) {
    public static KnowledgeVersionResponse fromDomain(KnowledgeVersion v) {
        return new KnowledgeVersionResponse(
                v.id(),
                v.knowledgeDocumentId(),
                v.versionNumber(),
                v.versionLabel(),
                v.status().name(),
                v.technicalReadiness() != null ? v.technicalReadiness().name() : null,
                v.holdReason(),
                v.contentHash(),
                v.contentPath(),
                v.sourceBytes(),
                v.reviewEvidenceId(),
                v.lineageBaseDocumentId(),
                v.lineageBaseVersion(),
                v.content(),
                v.reviewedBy(),
                v.reviewedAt(),
                v.publishedBy(),
                v.publishedAt(),
                v.activatedAt(),
                v.archivedAt(),
                v.createdAt()
        );
    }
}
