package com.fitnesscoaching.platform.modules.content.domain;

import java.util.UUID;

/**
 * Domain record representing an eligible, active knowledge chunk retrieved via dense vector similarity.
 */
public record RetrievedKnowledgeChunk(
        UUID chunkId,
        UUID documentId,
        String documentCode,
        String versionLabel,
        String sourceId,
        String title,
        String contentPath,
        String headingPath,
        int sectionIndex,
        String content,
        String snippet,
        double similarityScore
) {
}
