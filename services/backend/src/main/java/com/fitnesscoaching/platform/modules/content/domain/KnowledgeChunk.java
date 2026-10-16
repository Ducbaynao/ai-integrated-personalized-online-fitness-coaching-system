package com.fitnesscoaching.platform.modules.content.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain record representing an atomic chunk of a governed Knowledge Version.
 * Stored in fitness.knowledge_chunks.
 */
public record KnowledgeChunk(
        UUID id,
        UUID knowledgeVersionId,
        int chunkIndex,
        String headingPath,
        String content,
        Integer tokenCount,
        String metadataJson,
        Instant createdAt
) {
}
