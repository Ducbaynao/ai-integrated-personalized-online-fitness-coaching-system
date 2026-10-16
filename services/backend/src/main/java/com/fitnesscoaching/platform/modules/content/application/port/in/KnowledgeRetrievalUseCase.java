package com.fitnesscoaching.platform.modules.content.application.port.in;

import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;

import java.util.List;

public interface KnowledgeRetrievalUseCase {

    List<RetrievedKnowledgeChunk> retrieveActiveKnowledge(String query, List<String> allowedSourceIds, int limit);
}
