package com.fitnesscoaching.platform.modules.content.application.service;

import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry capturing the exact knowledge chunks provided to the AI provider
 * during an F01 query run, indexed by correlationId.
 * Guarantees that citation grounding validation inspects the original evidence set
 * supplied to the model, preventing race conditions or ranking drift caused by
 * post-generation re-querying.
 */
@Component
public class F01RunEvidenceTracker {

    private final Map<UUID, List<RetrievedKnowledgeChunk>> evidenceByRunId = new ConcurrentHashMap<>();

    public void recordRunEvidence(UUID runId, List<RetrievedKnowledgeChunk> chunks) {
        if (runId != null && chunks != null) {
            evidenceByRunId.put(runId, List.copyOf(chunks));
        }
    }

    public List<RetrievedKnowledgeChunk> getRunEvidence(UUID runId) {
        if (runId == null) {
            return List.of();
        }
        return evidenceByRunId.getOrDefault(runId, List.of());
    }

    public void clearRunEvidence(UUID runId) {
        if (runId != null) {
            evidenceByRunId.remove(runId);
        }
    }
}
