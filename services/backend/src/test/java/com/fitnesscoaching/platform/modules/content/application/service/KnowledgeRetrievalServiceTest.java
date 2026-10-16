package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private EmbeddingModelPort embeddingModelPort;

    private KnowledgeRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeRetrievalService(jdbcTemplate, embeddingModelPort);
    }

    @Test
    @DisplayName("Empty allowedSourceIds returns empty without calling embedding or DB")
    void emptyAllowedSourcesReturnsEmpty() {
        List<RetrievedKnowledgeChunk> result = service.retrieveActiveKnowledge("query", List.of(), 5);
        assertThat(result).isEmpty();
        verify(embeddingModelPort, never()).generateEmbedding(anyString());
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @DisplayName("Blank query returns empty without calling embedding or DB")
    void blankQueryReturnsEmpty() {
        List<RetrievedKnowledgeChunk> result = service.retrieveActiveKnowledge("   ", List.of("WHO-PA-SB-2020"), 5);
        assertThat(result).isEmpty();
        verify(embeddingModelPort, never()).generateEmbedding(anyString());
    }

    @Test
    @DisplayName("Unapproved sources are filtered out fail-closed")
    void unapprovedSourcesFilteredOut() {
        List<RetrievedKnowledgeChunk> result = service.retrieveActiveKnowledge("query", List.of("UNKNOWN-SOURCE-999"), 5);
        assertThat(result).isEmpty();
        verify(embeddingModelPort, never()).generateEmbedding(anyString());
    }

    @Test
    @DisplayName("Valid query embeds vector and executes pgvector cosine similarity SQL")
    void executesPgvectorQueryForApprovedSource() {
        float[] vector = new float[1536];
        vector[0] = 0.5f;
        when(embeddingModelPort.generateEmbedding("hoạt động thể lực")).thenReturn(vector);
        when(embeddingModelPort.modelVersionId()).thenReturn(UUID.randomUUID());

        UUID chunkId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        RetrievedKnowledgeChunk mockChunk = new RetrievedKnowledgeChunk(
                chunkId,
                docId,
                "WHO-PA-SB-2020-VI-01",
                "1.1.0",
                "WHO-PA-SB-2020",
                "WHO Physical Activity",
                "derivatives/who-2020/01-scope.vi.md",
                "Hướng dẫn Vận động > Người trưởng thành",
                0,
                "Người trưởng thành nên vận động ít nhất 150-300 phút...",
                "Người trưởng thành nên vận động...",
                0.92
        );

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(mockChunk));

        List<RetrievedKnowledgeChunk> chunks = service.retrieveActiveKnowledge(
                "hoạt động thể lực",
                List.of("WHO-PA-SB-2020", "MALICIOUS-SOURCE"),
                3
        );

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).documentCode()).isEqualTo("WHO-PA-SB-2020-VI-01");
        assertThat(chunks.get(0).similarityScore()).isEqualTo(0.92);

        // Verify SQL includes strict eligibility guards: ACTIVE, TECHNICAL_VERIFICATION_PASSED, index_ready, and relevance threshold
        verify(jdbcTemplate).query(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.contains("v.status = 'ACTIVE'") &&
                        sql.contains("v.technical_readiness = 'TECHNICAL_VERIFICATION_PASSED'") &&
                        sql.contains("index_ready") &&
                        sql.contains("<=>") &&
                        sql.contains("> ?")),
                any(RowMapper.class),
                any(Object[].class)
        );
    }

    @Test
    @DisplayName("isChunkEligible returns true when matching active verified chunk with embedding exists")
    void isChunkEligibleReturnsTrue() {
        UUID modelVersionId = UUID.randomUUID();
        when(embeddingModelPort.modelVersionId()).thenReturn(modelVersionId);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("WHO-PA-SB-2020-VI-01"), eq("1.1.0"), eq(0), eq(modelVersionId)))
                .thenReturn(1);

        boolean eligible = service.isChunkEligible("WHO-PA-SB-2020-VI-01", "1.1.0", 0);
        assertThat(eligible).isTrue();
    }

    @Test
    @DisplayName("isChunkEligible returns false when chunk is missing or inactive")
    void isChunkEligibleReturnsFalse() {
        UUID modelVersionId = UUID.randomUUID();
        when(embeddingModelPort.modelVersionId()).thenReturn(modelVersionId);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("UNKNOWN"), eq("1.0.0"), eq(0), eq(modelVersionId)))
                .thenReturn(0);

        boolean eligible = service.isChunkEligible("UNKNOWN", "1.0.0", 0);
        assertThat(eligible).isFalse();
    }

    @Test
    @DisplayName("isChunkEligible returns false immediately for null or blank inputs")
    void isChunkEligibleReturnsFalseForBlank() {
        assertThat(service.isChunkEligible(null, "1.0.0", 0)).isFalse();
        assertThat(service.isChunkEligible("WHO", "  ", 0)).isFalse();
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any());
    }
}
