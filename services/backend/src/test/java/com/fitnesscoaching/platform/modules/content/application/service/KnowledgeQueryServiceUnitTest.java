package com.fitnesscoaching.platform.modules.content.application.service;

import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryRequest;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryResult;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeDocumentRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeVersionRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeQueryServiceUnitTest {

    @Mock private KnowledgeVersionRepository versionRepository;
    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase retrievalUseCase;

    private KnowledgeQueryService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeQueryService(versionRepository, documentRepository, retrievalUseCase);
    }

    @Test
    void query_returnsEmpty_whenAllowedSourceIdsIsEmpty() {
        KnowledgeQueryRequest request = new KnowledgeQueryRequest("How much protein?", List.of(), 5);

        KnowledgeQueryResult result = service.queryActiveKnowledge(request);

        assertThat(result.citations()).isEmpty();
        verify(versionRepository, never()).findActiveBySourceIds(anyList(), anyInt());
    }

    @Test
    void query_returnsEmpty_whenAllowedSourceIdsIsNull() {
        KnowledgeQueryRequest request = new KnowledgeQueryRequest("How much protein?", null, 5);

        KnowledgeQueryResult result = service.queryActiveKnowledge(request);

        assertThat(result.citations()).isEmpty();
        verify(versionRepository, never()).findActiveBySourceIds(anyList(), anyInt());
    }

    @Test
    void query_intersectsWithApprovedSources_ignoresUnapprovedSources() {
        UUID docId = UUID.randomUUID();
        KnowledgeVersion activeVersion = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeDocumentId(docId)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.ACTIVE)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .content("Valid physical activity content")
                .build();

        when(versionRepository.findActiveBySourceIds(List.of("WHO-PA-SB-2020"), 5))
                .thenReturn(List.of(activeVersion));

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(docId)
                .documentCode("WHO-PA-SB-2020-VI-01")
                .sourceId("WHO-PA-SB-2020")
                .title("WHO Guidelines")
                .build();
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        // Malicious, unapproved, or invalid source IDs: whitespace, sql injection pattern, lowercase, unapproved
        KnowledgeQueryRequest request = new KnowledgeQueryRequest(
                "query", List.of("  ", "MALICIOUS;DROP TABLE", "UNAPPROVED-SECRET-SOURCE", "WHO-PA-SB-2020"), 5
        );

        KnowledgeQueryResult result = service.queryActiveKnowledge(request);

        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).sourceId()).isEqualTo("WHO-PA-SB-2020");
        verify(versionRepository).findActiveBySourceIds(List.of("WHO-PA-SB-2020"), 5);
    }

    @Test
    void query_onlyReturnsActiveVersions_ignoresDraftAndArchived() {
        UUID docId1 = UUID.randomUUID();
        UUID docId2 = UUID.randomUUID();

        KnowledgeVersion activeVersion = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeDocumentId(docId1)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.ACTIVE)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .contentPath("derivatives/who-2020/01-scope.vi.md")
                .content("Physical activity recommendations for adults.")
                .build();

        KnowledgeVersion draftVersion = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeDocumentId(docId2)
                .versionLabel("1.0.0")
                .status(KnowledgeStatus.DRAFT)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .content("Draft content that must not leak.")
                .build();

        when(versionRepository.findActiveBySourceIds(List.of("WHO-PA-SB-2020"), 10))
                .thenReturn(List.of(activeVersion, draftVersion));

        KnowledgeDocument doc1 = KnowledgeDocument.builder()
                .id(docId1)
                .documentCode("WHO-PA-SB-2020-VI-01")
                .sourceId("WHO-PA-SB-2020")
                .title("WHO Physical Activity")
                .build();
        when(documentRepository.findById(docId1)).thenReturn(Optional.of(doc1));

        KnowledgeQueryRequest request = new KnowledgeQueryRequest(
                "Physical activity guidelines", List.of("WHO-PA-SB-2020"), 10
        );

        KnowledgeQueryResult result = service.queryActiveKnowledge(request);

        // Strict guard: only the ACTIVE version is returned
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).documentCode()).isEqualTo("WHO-PA-SB-2020-VI-01");
        assertThat(result.citations().get(0).versionLabel()).isEqualTo("1.1.0");
        assertThat(result.citations().get(0).sourceId()).isEqualTo("WHO-PA-SB-2020");
    }

    @Test
    void query_ignoresActiveVersionsIfReadinessIsHeldAsDraft() {
        UUID docId = UUID.randomUUID();
        KnowledgeVersion heldVersion = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeDocumentId(docId)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.ACTIVE)
                .technicalReadiness(TechnicalReadiness.HELD_AS_DRAFT)
                .holdReason("Awaiting clinical check")
                .content("Held content that must not be cited.")
                .build();

        when(versionRepository.findActiveBySourceIds(List.of("NIDDK-WEIGHT-FACTORS-2023"), 5))
                .thenReturn(List.of(heldVersion));

        KnowledgeQueryRequest request = new KnowledgeQueryRequest(
                "medicines", List.of("NIDDK-WEIGHT-FACTORS-2023"), 5
        );

        KnowledgeQueryResult result = service.queryActiveKnowledge(request);

        // Fail-closed: HELD_AS_DRAFT is excluded even if marked ACTIVE
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void query_filtersByAllowedSourceIds() {
        UUID docId = UUID.randomUUID();
        KnowledgeVersion activeVersion = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeDocumentId(docId)
                .versionLabel("1.0.0")
                .status(KnowledgeStatus.ACTIVE)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .content("Protein intake 1.4-2.0 g/kg/day.")
                .build();

        when(versionRepository.findActiveBySourceIds(List.of("ISSN-PROTEIN-EXERCISE-2017"), 5))
                .thenReturn(List.of(activeVersion));

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(docId)
                .documentCode("ISSN-PROT-2017-VI-01")
                .sourceId("ISSN-PROTEIN-EXERCISE-2017")
                .title("ISSN Protein Guidelines")
                .build();
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        KnowledgeQueryRequest request = new KnowledgeQueryRequest(
                "protein intake", List.of("ISSN-PROTEIN-EXERCISE-2017"), 5
        );

        KnowledgeQueryResult result = service.queryActiveKnowledge(request);

        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).sourceId()).isEqualTo("ISSN-PROTEIN-EXERCISE-2017");
        assertThat(result.citations().get(0).documentCode()).isEqualTo("ISSN-PROT-2017-VI-01");
    }
}
