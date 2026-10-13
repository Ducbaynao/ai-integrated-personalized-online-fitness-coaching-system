package com.fitnesscoaching.platform.modules.content.application.port.in;

import java.util.List;
import java.util.UUID;

public record KnowledgeQueryResult(
        List<KnowledgeCitationItem> citations
) {
    public record KnowledgeCitationItem(
            UUID documentId,
            String documentCode,
            String versionLabel,
            String sourceId,
            String title,
            String contentPath,
            String contentSnippet,
            String fullContent
    ) {
    }
}
