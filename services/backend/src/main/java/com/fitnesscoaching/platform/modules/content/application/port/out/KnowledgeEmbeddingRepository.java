package com.fitnesscoaching.platform.modules.content.application.port.out;

import com.fitnesscoaching.platform.modules.content.domain.KnowledgeEmbeddingRecord;

import java.util.List;
import java.util.UUID;

public interface KnowledgeEmbeddingRepository {

    void saveBatch(List<KnowledgeEmbeddingRecord> embeddings);

    void deleteByKnowledgeVersionId(UUID versionId);
}
