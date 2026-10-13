package com.fitnesscoaching.platform.modules.content.application.port.in;

public interface KnowledgeQueryUseCase {

    KnowledgeQueryResult queryActiveKnowledge(KnowledgeQueryRequest request);
}
