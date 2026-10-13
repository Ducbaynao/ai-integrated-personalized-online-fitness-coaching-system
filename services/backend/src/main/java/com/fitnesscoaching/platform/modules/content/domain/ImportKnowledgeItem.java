package com.fitnesscoaching.platform.modules.content.domain;

/**
 * Single document/version item within an import corpus bundle.
 */
public record ImportKnowledgeItem(
        String documentCode,
        String sourceId,
        String title,
        String sourceName,
        String sourceUrl,
        String author,
        String documentType,
        String language,
        String versionLabel,
        int versionNumber,
        String contentHash,
        String contentPath,
        Integer sourceBytes,
        String reviewEvidenceId,
        TechnicalReadiness technicalReadiness,
        String holdReason,
        String lineageBaseDocumentId,
        String lineageBaseVersion,
        String lineageBaseHash,
        String content
) {
}
