package com.fitnesscoaching.platform.modules.content.domain;

import java.util.List;
import java.util.UUID;

/**
 * Result of an import corpus operation.
 */
public record ImportCorpusResult(
        int totalItems,
        int importedCount,
        int skippedCount,
        List<ImportedItemSummary> importedItems,
        List<String> skippedItems
) {
    public record ImportedItemSummary(
            UUID documentId,
            UUID versionId,
            String documentCode,
            String versionLabel,
            KnowledgeStatus status,
            TechnicalReadiness technicalReadiness
    ) {
    }
}
