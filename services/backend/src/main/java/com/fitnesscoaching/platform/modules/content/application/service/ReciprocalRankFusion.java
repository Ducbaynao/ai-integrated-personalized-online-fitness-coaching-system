package com.fitnesscoaching.platform.modules.content.application.service;

import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Combines rank positions, never raw cosine and lexical scores. Preserves original cosine metadata. */
final class ReciprocalRankFusion {
    private ReciprocalRankFusion() {}

    static List<RetrievedKnowledgeChunk> fuse(
            List<RetrievedKnowledgeChunk> dense, List<RetrievedKnowledgeChunk> lexical, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<UUID, RetrievedKnowledgeChunk> chunks = new LinkedHashMap<>();
        Map<UUID, Double> scores = new LinkedHashMap<>();
        for (List<RetrievedKnowledgeChunk> ranked : List.of(dense, lexical)) {
            java.util.Set<UUID> seen = new java.util.HashSet<>();
            for (int i = 0; i < ranked.size(); i++) {
                RetrievedKnowledgeChunk chunk = ranked.get(i);
                if (!seen.add(chunk.chunkId())) continue;
                chunks.putIfAbsent(chunk.chunkId(), chunk);
                scores.merge(chunk.chunkId(), 1.0 / (60 + i + 1), Double::sum);
            }
        }
        return chunks.values().stream()
                .sorted(Comparator.<RetrievedKnowledgeChunk>comparingDouble(c -> scores.get(c.chunkId()))
                        .reversed().thenComparing(c -> c.chunkId().toString()))
                .limit(limit)
                .toList();
    }
}
