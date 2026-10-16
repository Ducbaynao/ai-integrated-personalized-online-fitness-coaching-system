package com.fitnesscoaching.platform.modules.content.application.port.out;

import com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeChunkRepository {

    void saveBatch(List<KnowledgeChunk> chunks);

    void deleteByKnowledgeVersionId(UUID versionId);

    List<KnowledgeChunk> findByKnowledgeVersionId(UUID versionId);

    Optional<KnowledgeChunk> findByDocumentCodeAndVersionAndIndex(String documentCode, String versionLabel, int chunkIndex);

    Optional<KnowledgeChunk> findById(UUID chunkId);
}
