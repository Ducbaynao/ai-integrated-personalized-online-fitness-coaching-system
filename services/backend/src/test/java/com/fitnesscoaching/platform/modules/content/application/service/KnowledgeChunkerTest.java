package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeChunkerTest {

    private KnowledgeChunker chunker;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        chunker = new KnowledgeChunker(objectMapper);
    }

    @Test
    @DisplayName("Chunking is deterministic: same content produces identical chunks, IDs, hashes, and sequences")
    void deterministicChunking() {
        UUID docId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-16T12:00:00Z");

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(docId)
                .documentCode("WHO-PA-SB-2020-VI-01")
                .sourceId("WHO-PA-SB-2020")
                .title("WHO Physical Activity Guidelines")
                .build();

        String markdown = """
                # Hướng dẫn Vận động WHO 2020
                
                WHO 2020 đưa ra khuyến nghị sức khỏe cộng đồng về lượng, tần suất và cường độ vận động.
                
                ## Người trưởng thành 18-64 tuổi
                
                Người trưởng thành nên thực hiện ít nhất 150-300 phút hoạt động thể lực cường độ trung bình mỗi tuần.
                
                Cũng nên tập các bài tăng cường cơ bắp ở cường độ trung bình hoặc cao hơn ít nhất 2 ngày mỗi tuần.
                
                ## Giới hạn
                
                Tài liệu không đưa ra đơn thuốc vận động cá nhân hóa cho từng cá nhân.
                """;

        KnowledgeVersion version = KnowledgeVersion.builder()
                .id(versionId)
                .knowledgeDocumentId(docId)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.ACTIVE)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .contentPath("derivatives/who-2020/01-scope.vi.md")
                .content(markdown)
                .build();

        List<KnowledgeChunk> chunks1 = chunker.chunk(version, doc, now);
        List<KnowledgeChunk> chunks2 = chunker.chunk(version, doc, now);

        assertThat(chunks1).isNotEmpty();
        assertThat(chunks1).hasSameSizeAs(chunks2);

        for (int i = 0; i < chunks1.size(); i++) {
            KnowledgeChunk c1 = chunks1.get(i);
            KnowledgeChunk c2 = chunks2.get(i);

            assertThat(c1.id()).isEqualTo(c2.id());
            assertThat(c1.chunkIndex()).isEqualTo(i);
            assertThat(c1.chunkIndex()).isEqualTo(c2.chunkIndex());
            assertThat(c1.metadataJson()).isEqualTo(c2.metadataJson());
            assertThat(c1.metadataJson()).contains("contentHash");
            assertThat(c1.metadataJson()).contains("chunkHash");
            assertThat(c1.metadataJson()).contains("lineageBaseDocumentId");
            assertThat(c1.headingPath()).isEqualTo(c2.headingPath());
            assertThat(c1.tokenCount()).isEqualTo(c2.tokenCount());
            assertThat(c1.content()).isEqualTo(c2.content());
        }

        // Verify heading paths are captured
        assertThat(chunks1.get(0).headingPath()).contains("Hướng dẫn Vận động WHO 2020");
        assertThat(chunks1.stream().anyMatch(c -> c.headingPath().contains("Người trưởng thành 18-64 tuổi"))).isTrue();
        assertThat(chunks1.stream().anyMatch(c -> c.headingPath().contains("Giới hạn"))).isTrue();
    }

    @Test
    @DisplayName("Overlong paragraphs and sentences exceeding max characters are cleanly partitioned")
    void overlongSentencesAreSplitWithinMaxChars() {
        UUID docId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(docId)
                .documentCode("WHO-PA-SB-2020-VI-02")
                .sourceId("WHO-PA-SB-2020")
                .title("Long document")
                .build();

        // 3000 chars unbroken sentence
        String hugeText = "Khuyến nghị hoạt động thể lực đều đặn cho mọi nhóm tuổi ".repeat(60);
        assertThat(hugeText.length()).isGreaterThan(2000);

        KnowledgeVersion version = KnowledgeVersion.builder()
                .id(versionId)
                .knowledgeDocumentId(docId)
                .versionLabel("1.0.0")
                .status(KnowledgeStatus.ACTIVE)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .contentPath("derivatives/who-2020/02-long.vi.md")
                .content("# Chương 1\n\n" + hugeText)
                .build();

        List<KnowledgeChunk> chunks = chunker.chunk(version, doc, now);
        assertThat(chunks).hasSizeGreaterThan(1);
        for (KnowledgeChunk chunk : chunks) {
            assertThat(chunk.content().length()).isLessThanOrEqualTo(1600);
        }
    }

    @Test
    @DisplayName("Evaluation and holdout files are strictly guarded against entering the index corpus")
    void guardsAgainstEvaluationFiles() {
        UUID docId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(docId)
                .documentCode("EVAL-DOC-01")
                .sourceId("EVAL")
                .title("Evaluation Set")
                .build();

        KnowledgeVersion version = KnowledgeVersion.builder()
                .id(versionId)
                .knowledgeDocumentId(docId)
                .versionLabel("1.0.0")
                .status(KnowledgeStatus.ACTIVE)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .contentPath("evaluations/eval-cases.jsonl")
                .content("{\"question\":\"test\"}")
                .build();

        assertThatThrownBy(() -> chunker.chunk(version, doc, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Evaluation, holdout, or test files must never be chunked for retrieval index");
    }
}
