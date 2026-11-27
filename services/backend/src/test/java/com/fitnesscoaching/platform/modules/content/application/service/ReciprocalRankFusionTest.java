package com.fitnesscoaching.platform.modules.content.application.service;

import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {
    @Test
    void agreementOutranksSingleBranchAndPreservesCosine() {
        var a = chunk(1, 0.99);
        var b = chunk(2, 0.2);
        var c = chunk(3, -0.1);
        var result = ReciprocalRankFusion.fuse(List.of(a, b), List.of(c, b), 3);
        assertThat(result).containsExactly(b, a, c);
        assertThat(result.getFirst().similarityScore()).isEqualTo(0.2);
    }

    @Test
    void duplicatesWithinOneBranchDoNotInflateScoreAndLimitIsRespected() {
        var a = chunk(1, 0.5);
        var b = chunk(2, 0.4);
        assertThat(ReciprocalRankFusion.fuse(List.of(a, a, a, b), List.of(b), 1))
                .containsExactly(b);
        assertThat(ReciprocalRankFusion.fuse(List.of(), List.of(), 5)).isEmpty();
        assertThat(ReciprocalRankFusion.fuse(List.of(a), List.of(b), 0)).isEmpty();
    }

    @Test
    void tieBreakIsDeterministicByChunkId() {
        var lowId = chunk(1, 0.3);
        var highId = chunk(2, 0.9);
        // Same single-branch rank (rank 1 in dense vs rank 1 in lexical) -> deterministic tie-break by chunkId string
        assertThat(ReciprocalRankFusion.fuse(List.of(highId), List.of(lowId), 2))
                .containsExactly(lowId, highId);
    }

    private RetrievedKnowledgeChunk chunk(long id, double similarity) {
        return new RetrievedKnowledgeChunk(new UUID(0, id), new UUID(1, id), "DOC", "1.0.0",
                "SOURCE", "Title", "path", "heading", 0, "content", "snippet", similarity);
    }
}
