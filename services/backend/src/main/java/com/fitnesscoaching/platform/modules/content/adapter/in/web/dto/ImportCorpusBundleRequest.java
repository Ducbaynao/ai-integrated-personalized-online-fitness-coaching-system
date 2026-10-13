package com.fitnesscoaching.platform.modules.content.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.content.domain.ImportCorpusBundle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ImportCorpusBundleRequest(
        Integer formatVersion,
        String purpose,
        String bundleName,
        @NotEmpty @Valid List<ImportKnowledgeItemRequest> items
) {
    public ImportCorpusBundle toDomain() {
        return new ImportCorpusBundle(
                formatVersion != null && formatVersion > 0 ? formatVersion : 1,
                purpose != null ? purpose : "F01_GENERAL_KNOWLEDGE",
                bundleName != null ? bundleName : "corpus-bundle",
                items.stream().map(ImportKnowledgeItemRequest::toDomain).toList()
        );
    }
}
