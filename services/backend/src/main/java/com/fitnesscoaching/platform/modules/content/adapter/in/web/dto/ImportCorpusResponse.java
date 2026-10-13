package com.fitnesscoaching.platform.modules.content.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.content.domain.ImportCorpusResult;

import java.util.List;
import java.util.UUID;

public record ImportCorpusResponse(
        int totalItems,
        int importedCount,
        int skippedCount,
        List<ImportedItemDto> importedItems,
        List<String> skippedItems
) {
    public record ImportedItemDto(
            UUID documentId,
            UUID versionId,
            String documentCode,
            String versionLabel,
            String status,
            String technicalReadiness
    ) {
    }

    public static ImportCorpusResponse fromDomain(ImportCorpusResult result) {
        List<ImportedItemDto> dtos = result.importedItems().stream()
                .map(i -> new ImportedItemDto(
                        i.documentId(),
                        i.versionId(),
                        i.documentCode(),
                        i.versionLabel(),
                        i.status().name(),
                        i.technicalReadiness().name()
                ))
                .toList();

        return new ImportCorpusResponse(
                result.totalItems(),
                result.importedCount(),
                result.skippedCount(),
                dtos,
                result.skippedItems()
        );
    }
}
