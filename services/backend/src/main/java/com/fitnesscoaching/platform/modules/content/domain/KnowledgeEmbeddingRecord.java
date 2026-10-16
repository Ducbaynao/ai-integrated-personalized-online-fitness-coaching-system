package com.fitnesscoaching.platform.modules.content.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain record representing an embedding vector for a Knowledge Chunk.
 * Stored in fitness.knowledge_embeddings.
 */
public record KnowledgeEmbeddingRecord(
        UUID id,
        UUID knowledgeChunkId,
        UUID embeddingModelVersionId,
        float[] embedding,
        Instant createdAt
) {
}
