package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeChunker;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic offline test for WHO-01 source file verification and chunking.
 *
 * <p>Key properties:
 * <ul>
 *   <li>100% offline unit test: no Spring Boot context, no database connection, no external network.</li>
 *   <li>Runs in standard CI/CD test lifecycle (Maven test) without requiring local testbeds or secrets.</li>
 *   <li>Verifies the exact SHA-256 hash of the canonical WHO-01 Vietnamese summary markdown source.</li>
 *   <li>Verifies structural properties of the generated 5 chunks (heading paths, token boundaries, determinism).</li>
 * </ul>
 */
@DisplayName("WHO-01 Knowledge Chunking Offline Test")
class Who01KnowledgeChunkingOfflineTest {

    private static final String DOCUMENT_CODE = "WHO-PA-SB-2020-VI-01";
    private static final String VERSION_LABEL = "1.1.0";
    private static final String SOURCE_ID = "WHO-PA-SB-2020";
    private static final String EXPECTED_SOURCE_HASH =
            "5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840";

    private KnowledgeChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new KnowledgeChunker(new ObjectMapper());
    }

    private static Path resolveFile(String relative) {
        List<Path> candidates = List.of(
                Path.of("..", "..", relative),
                Path.of(relative),
                Path.of("data", "knowledge", relative),
                Path.of("..", "..", "data", "knowledge", relative)
        );
        for (Path c : candidates) {
            if (Files.exists(c)) return c;
        }
        throw new IllegalStateException("Required file not found on disk: " + relative);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Verify WHO-01 source file integrity and SHA-256 content hash")
    void testWho01SourceIntegrityAndHash() throws IOException {
        Path contentPath = resolveFile("derivatives/who-2020/01-scope.vi.md");
        assertThat(contentPath).exists();

        String content = Files.readString(contentPath, StandardCharsets.UTF_8);
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String calculatedHash = sha256Hex(contentBytes);

        assertThat(calculatedHash)
                .as("WHO-01 markdown source content hash must match approved corpus baseline")
                .isEqualTo(EXPECTED_SOURCE_HASH);
        assertThat(contentBytes.length)
                .as("WHO-01 content length in bytes")
                .isEqualTo(3429);
    }

    @Test
    @DisplayName("Verify WHO-01 deterministic chunking into 5 structured chunks with expected headings")
    void testWho01DeterministicChunking() throws IOException {
        Path contentPath = resolveFile("derivatives/who-2020/01-scope.vi.md");
        String content = Files.readString(contentPath, StandardCharsets.UTF_8);
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String contentHash = sha256Hex(contentBytes);

        UUID docId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(docId)
                .documentCode(DOCUMENT_CODE)
                .sourceId(SOURCE_ID)
                .title("Phạm vi và cách sử dụng WHO 2020")
                .build();

        KnowledgeVersion ver = KnowledgeVersion.builder()
                .id(versionId)
                .knowledgeDocumentId(doc.id())
                .versionLabel(VERSION_LABEL)
                .versionNumber(1)
                .status(KnowledgeStatus.DRAFT)
                .content(content)
                .contentHash(contentHash)
                .contentPath("derivatives/who-2020/01-scope.vi.md")
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .lineageBaseDocumentId(DOCUMENT_CODE)
                .lineageBaseVersion("1.0.0")
                .lineageBaseHash("83f7921e04986d1d3843ceb5d07d7911aac0c93dd81b87ab6f4659fae42277c1")
                .build();

        Instant timestamp = Instant.parse("2026-10-19T10:00:00Z");

        // First pass
        List<KnowledgeChunk> chunksPass1 = chunker.chunk(ver, doc, timestamp);
        // Second pass (verifying determinism)
        List<KnowledgeChunk> chunksPass2 = chunker.chunk(ver, doc, timestamp);

        assertThat(chunksPass1)
                .as("WHO-01 must produce exactly 5 semantic chunks")
                .hasSize(5);
        assertThat(chunksPass1)
                .as("Chunking must be perfectly deterministic across runs")
                .hasSameSizeAs(chunksPass2);

        for (int i = 0; i < chunksPass1.size(); i++) {
            KnowledgeChunk c1 = chunksPass1.get(i);
            KnowledgeChunk c2 = chunksPass2.get(i);

            assertThat(c1.id()).isEqualTo(c2.id());
            assertThat(c1.chunkIndex()).isEqualTo(i);
            assertThat(c1.chunkIndex()).isEqualTo(c2.chunkIndex());
            assertThat(c1.metadataJson()).isEqualTo(c2.metadataJson());
            assertThat(c1.headingPath()).isEqualTo(c2.headingPath());
            assertThat(c1.tokenCount()).isEqualTo(c2.tokenCount());
            assertThat(c1.content()).isEqualTo(c2.content());
            assertThat(c1.tokenCount()).isGreaterThan(0);
            assertThat(c1.content()).isNotBlank();
        }

        // Verify specific heading paths for each chunk
        assertThat(chunksPass1.get(0).headingPath()).isEqualTo("Phạm vi và cách sử dụng WHO 2020");
        assertThat(chunksPass1.get(1).headingPath()).isEqualTo("Phạm vi và cách sử dụng WHO 2020 > Nội dung");
        assertThat(chunksPass1.get(2).headingPath()).isEqualTo("Phạm vi và cách sử dụng WHO 2020 > Giới hạn của nguồn");
        assertThat(chunksPass1.get(3).headingPath()).isEqualTo("Phạm vi và cách sử dụng WHO 2020 > Bối cảnh lịch sử");
        assertThat(chunksPass1.get(4).headingPath()).isEqualTo("Phạm vi và cách sử dụng WHO 2020 > Truy vết nguồn và trạng thái");

        // Verify token counts match expected heuristics
        assertThat(chunksPass1.get(0).tokenCount()).isEqualTo(75);
        assertThat(chunksPass1.get(1).tokenCount()).isEqualTo(150);
        assertThat(chunksPass1.get(2).tokenCount()).isEqualTo(149);
        assertThat(chunksPass1.get(3).tokenCount()).isEqualTo(55);
        assertThat(chunksPass1.get(4).tokenCount()).isEqualTo(193);
    }
}
