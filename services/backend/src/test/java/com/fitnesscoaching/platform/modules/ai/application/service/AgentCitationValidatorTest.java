package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse.CitationDto;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse.LocatorDto;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase;
import com.fitnesscoaching.platform.modules.content.application.service.F01RunEvidenceTracker;
import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentCitationValidatorTest {
    private final F01RunEvidenceTracker tracker = new F01RunEvidenceTracker();
    private final KnowledgeRetrievalUseCase retrieval = mock(KnowledgeRetrievalUseCase.class);
    private final AgentCitationValidator validator = new AgentCitationValidator(tracker, retrieval);
    private final UUID run = UUID.randomUUID();
    private final UUID chunk = UUID.randomUUID();
    private final CitationDto citation = cite("150-300 minutes", "who.md");

    @Test
    void requiresBothRunEvidenceAndRunnerSelectionEvenForAnActiveChunk() {
        assertThat(validator.valid(run, List.of(chunk.toString()), List.of(citation))).isFalse();
        record(run, chunk);
        assertThat(validator.valid(run, List.of(), List.of(citation))).isFalse();
        assertThat(validator.valid(UUID.randomUUID(), List.of(chunk.toString()), List.of(citation))).isFalse();
        verifyNoInteractions(retrieval);
    }

    @Test
    void preservesFirstSearchEvidenceButRejectsForgedQuoteLocatorAndInactiveVersion() {
        record(run, chunk);
        record(run, UUID.randomUUID()); // A second search must not erase the first one.
        when(retrieval.isChunkEligible("WHO-VI-01", "1.0.0", 0)).thenReturn(true);
        assertThat(validator.valid(run, List.of(chunk.toString()), List.of(citation))).isTrue();
        assertThat(validator.valid(run, List.of(chunk.toString()), List.of(cite("fabricated", "who.md")))).isFalse();
        assertThat(validator.valid(run, List.of(chunk.toString()), List.of(cite("150-300 minutes", "wrong.md")))).isFalse();
        when(retrieval.isChunkEligible("WHO-VI-01", "1.0.0", 0)).thenReturn(false);
        assertThat(validator.valid(run, List.of(chunk.toString()), List.of(citation))).isFalse();
        tracker.clearRunEvidence(run);
        assertThat(validator.valid(run, List.of(chunk.toString()), List.of(citation))).isFalse();
    }

    private void record(UUID runId, UUID chunkId) {
        tracker.recordRunEvidence(runId, List.of(new RetrievedKnowledgeChunk(chunkId, UUID.randomUUID(),
                "WHO-VI-01", "1.0.0", "WHO", "WHO", "who.md", "Adults", 0,
                "Adults should do 150-300 minutes of activity.", "Adults", 0.9)));
    }

    private static CitationDto cite(String quote, String path) {
        return new CitationDto("WHO-VI-01", "1.0.0", "WHO", new LocatorDto(path, "Adults", null, 0), quote);
    }
}
