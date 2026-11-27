package com.fitnesscoaching.platform.modules.content.application.service;

import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class F01RunEvidenceTrackerTest {

    @Test
    void multiSearchPreservesEarlierEvidenceAndDeduplicatesByChunkId() {
        F01RunEvidenceTracker tracker = new F01RunEvidenceTracker();
        UUID runId = UUID.randomUUID();
        RetrievedKnowledgeChunk c1 = chunk(1, "WHO-PA-SB-2020-VI-01");
        RetrievedKnowledgeChunk c2 = chunk(2, "ISSN-PROTEIN-EXERCISE-2017-VI-01");

        tracker.recordRunEvidence(runId, List.of(c1));
        tracker.recordRunEvidence(runId, List.of(c1, c2));

        assertThat(tracker.getRunEvidence(runId)).containsExactly(c1, c2);
        tracker.clearRunEvidence(runId);
        assertThat(tracker.getRunEvidence(runId)).isEmpty();
    }

    private RetrievedKnowledgeChunk chunk(long id, String docCode) {
        return new RetrievedKnowledgeChunk(
                new UUID(0, id),
                new UUID(1, id),
                docCode,
                "1.0.0",
                "WHO-PA-SB-2020",
                "Title",
                "who.md",
                "Heading",
                0,
                "content",
                "snippet",
                0.9
        );
    }
}
