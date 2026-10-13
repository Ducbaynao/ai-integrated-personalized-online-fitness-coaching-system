package com.fitnesscoaching.platform.modules.content.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.content.domain.ImportKnowledgeItem;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ImportKnowledgeItemRequest(
        @NotBlank String documentCode,
        @NotBlank String sourceId,
        @NotBlank String title,
        String sourceName,
        String sourceUrl,
        String author,
        String documentType,
        String language,
        @NotBlank String versionLabel,
        Integer versionNumber,
        @NotBlank String contentHash,
        String contentPath,
        Integer sourceBytes,
        String reviewEvidenceId,
        String technicalReadiness,
        String holdReason,
        String lineageBaseDocumentId,
        String lineageBaseVersion,
        String lineageBaseHash,
        @NotNull String content
) {
    public ImportKnowledgeItem toDomain() {
        TechnicalReadiness readiness = TechnicalReadiness.DRAFT_STAGED;
        if (technicalReadiness != null) {
            try {
                readiness = TechnicalReadiness.valueOf(technicalReadiness);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new ImportKnowledgeItem(
                documentCode, sourceId, title, sourceName, sourceUrl,
                author, documentType, language != null ? language : "vi",
                versionLabel, versionNumber != null && versionNumber > 0 ? versionNumber : 1,
                contentHash, contentPath, sourceBytes, reviewEvidenceId,
                readiness, holdReason, lineageBaseDocumentId, lineageBaseVersion,
                lineageBaseHash, content
        );
    }
}
