package com.fitnesscoaching.platform.modules.content.application.service;

import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded in-memory registry capturing the knowledge chunks provided to the AI provider
 * during a run, indexed by runId/correlationId.
 * Supports multiple search calls within one run by merging and deduplicating by chunkId
 * without overwriting earlier search evidence, while bounding memory.
 */
@Component
public class F01RunEvidenceTracker {

    private static final int MAX_TRACKED_RUNS = 2000;
    private static final int MAX_CHUNKS_PER_RUN = 100;

    private final Map<UUID, Map<UUID, RetrievedKnowledgeChunk>> evidenceByRunId = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Map<UUID, RetrievedKnowledgeChunk>> eldest) {
            return size() > MAX_TRACKED_RUNS;
        }
    };

    public synchronized void recordRunEvidence(UUID runId, List<RetrievedKnowledgeChunk> chunks) {
        if (runId == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        Map<UUID, RetrievedKnowledgeChunk> existing = evidenceByRunId.computeIfAbsent(runId, k -> new LinkedHashMap<>());
        for (RetrievedKnowledgeChunk chunk : chunks) {
            if (existing.size() >= MAX_CHUNKS_PER_RUN) {
                break;
            }
            existing.putIfAbsent(chunk.chunkId(), chunk);
        }
    }

    public synchronized List<RetrievedKnowledgeChunk> getRunEvidence(UUID runId) {
        if (runId == null) {
            return List.of();
        }
        Map<UUID, RetrievedKnowledgeChunk> chunks = evidenceByRunId.get(runId);
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(chunks.values()));
    }

    public synchronized void clearRunEvidence(UUID runId) {
        if (runId != null) {
            evidenceByRunId.remove(runId);
        }
    }
}
