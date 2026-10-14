package com.fitnesscoaching.platform.modules.ai.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Domain entity for knowledge chunk reference associated with an AI Run in fitness.ai_run_knowledge_chunks.
 */
public record AiRunKnowledgeChunk(
        UUID aiRunId,
        UUID knowledgeChunkId,
        int rank,
        BigDecimal similarityScore
) {
}
