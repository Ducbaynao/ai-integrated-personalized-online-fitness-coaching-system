package com.fitnesscoaching.platform.modules.content.application.port.out;

import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeDocumentRepository {

    Optional<KnowledgeDocument> findById(UUID id);

    Optional<KnowledgeDocument> findByDocumentCode(String documentCode);

    KnowledgeDocument save(KnowledgeDocument document);

    List<KnowledgeDocument> findAllActive();

    List<KnowledgeDocument> findAll(int offset, int limit);
}
