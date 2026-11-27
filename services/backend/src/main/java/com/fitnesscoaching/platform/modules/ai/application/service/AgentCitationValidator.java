package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse.CitationDto;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase;
import com.fitnesscoaching.platform.modules.content.application.service.F01RunEvidenceTracker;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Intersects runner-selected evidence with the server's immutable retrieval snapshot. */
@Component
public class AgentCitationValidator {
    private final F01RunEvidenceTracker tracker;
    private final KnowledgeRetrievalUseCase retrieval;

    public AgentCitationValidator(F01RunEvidenceTracker tracker, KnowledgeRetrievalUseCase retrieval) {
        this.tracker = tracker;
        this.retrieval = retrieval;
    }

    public boolean valid(UUID runId, List<String> presentedIds, List<CitationDto> citations) {
        if (presentedIds == null || presentedIds.size() > 40 || citations == null) return false;
        var candidates = tracker.getRunEvidence(runId);
        Set<String> presented = new java.util.HashSet<>(presentedIds);
        // Even a selected but uncited ID must have come from this run's retrieval.
        if (presented.stream().anyMatch(id -> candidates.stream()
                .noneMatch(c -> c.chunkId().toString().equals(id)))) return false;
        for (CitationDto citation : citations) {
            if (citation == null || citation.locator() == null
                    || citation.locator().sectionIndex() == null
                    || citation.quoteOrExcerpt() == null || citation.quoteOrExcerpt().isBlank()) return false;
            var match = candidates.stream().filter(c -> presented.contains(c.chunkId().toString())
                    && Objects.equals(c.documentCode(), citation.documentId())
                    && Objects.equals(c.versionLabel(), citation.version())
                    && Objects.equals(c.sourceId(), citation.sourceId())
                    && Objects.equals(c.contentPath(), citation.locator().sourcePath())
                    && c.sectionIndex() == citation.locator().sectionIndex()
                    && (citation.locator().heading() == null || citation.locator().heading().isBlank()
                        || Objects.equals(c.headingPath(), citation.locator().heading())))
                    .findFirst();
            if (match.isEmpty() || match.get().content() == null
                    || !match.get().content().contains(citation.quoteOrExcerpt())
                    || !retrieval.isChunkEligible(citation.documentId(), citation.version(),
                            citation.locator().sectionIndex())) return false;
        }
        return true;
    }
}
