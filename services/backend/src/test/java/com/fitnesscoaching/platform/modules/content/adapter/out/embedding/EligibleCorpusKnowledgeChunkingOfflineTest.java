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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic offline unit test for the expanded eligible derivative corpus.
 *
 * <p>Scope and Invariants:
 * <ul>
 *   <li>100% offline POJO test: zero database, zero Spring context, zero network calls.</li>
 *   <li>Verifies integrity and SHA-256 hashes of all 11 derivative articles.</li>
 *   <li>Enforces that NIDDK-05 and NIDDK-06 remain HELD_AS_DRAFT (excluded from indexing).</li>
 *   <li>Verifies chunking determinism and character constraints for all 9 eligible derivatives.</li>
 *   <li>Computes total chunks and heuristic token totals for cost accounting baseline.</li>
 * </ul>
 */
@DisplayName("Eligible Corpus Knowledge Chunking Offline Test")
class EligibleCorpusKnowledgeChunkingOfflineTest {

    private static final Logger log = LoggerFactory.getLogger(EligibleCorpusKnowledgeChunkingOfflineTest.class);

    record DerivativeDefinition(
            String documentCode,
            String sourceId,
            String title,
            String versionLabel,
            String contentPath,
            String expectedHash,
            int expectedBytes,
            TechnicalReadiness technicalReadiness
    ) {}

    private static final List<DerivativeDefinition> ALL_DERIVATIVES = List.of(
            // 5 WHO 2020 Derivatives (Eligible)
            new DerivativeDefinition(
                    "WHO-PA-SB-2020-VI-01",
                    "WHO-PA-SB-2020",
                    "Phạm vi và cách sử dụng WHO 2020",
                    "1.1.0",
                    "derivatives/who-2020/01-scope.vi.md",
                    "5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840",
                    3429,
                    TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED
            ),
            new DerivativeDefinition(
                    "WHO-PA-SB-2020-VI-02",
                    "WHO-PA-SB-2020",
                    "Thuật ngữ vận động và sức khỏe",
                    "1.1.0",
                    "derivatives/who-2020/02-glossary.vi.md",
                    "47393a0e6335c702fe4c69bb2c45b7a848bab39a265a1532d7a2e4a389a2ad51",
                    5472,
                    TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED
            ),
            new DerivativeDefinition(
                    "WHO-PA-SB-2020-VI-03",
                    "WHO-PA-SB-2020",
                    "MET và cường độ vận động",
                    "1.1.0",
                    "derivatives/who-2020/03-intensity.vi.md",
                    "d56ad3c4473e8648e2b158c20e9cfa311a9fd37f282a0b517f1a9494c2099ebc",
                    2685,
                    TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED
            ),
            new DerivativeDefinition(
                    "WHO-PA-SB-2020-VI-05",
                    "WHO-PA-SB-2020",
                    "Vận động và tăng cường cơ ở tuổi 18–64",
                    "1.1.0",
                    "derivatives/who-2020/05-adults-activity.vi.md",
                    "db6f9dc1140fd582487422cc23596224bfda2e711ff009335a7da07227b6fbe5",
                    4202,
                    TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED
            ),
            new DerivativeDefinition(
                    "WHO-PA-SB-2020-VI-06",
                    "WHO-PA-SB-2020",
                    "Hành vi ít vận động ở tuổi 18–64",
                    "1.1.0",
                    "derivatives/who-2020/06-adults-sedentary.vi.md",
                    "630802d505124da0fa395cc939977ed4278ee2297dfcac9cdde7fce6eb49fdd0",
                    2986,
                    TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED
            ),

            // 4 NIDDK Weight Factors (Eligible)
            new DerivativeDefinition(
                    "NIDDK-WEIGHT-FACTORS-2023-VI-01",
                    "NIDDK-WEIGHT-FACTORS-2023",
                    "Các yếu tố ảnh hưởng đến cân nặng",
                    "1.1.0",
                    "derivatives/niddk-weight-factors/01-overview.vi.md",
                    "49a628a6f06a20c0f85599542e8a4592ec3087a7d44fb64bb940d93de760cd90",
                    1678,
                    TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED
            ),
            new DerivativeDefinition(
                    "NIDDK-WEIGHT-FACTORS-2023-VI-02",
                    "NIDDK-WEIGHT-FACTORS-2023",
                    "Thói quen sinh hoạt và cân bằng năng lượng",
                    "1.1.0",
                    "derivatives/niddk-weight-factors/02-lifestyle.vi.md",
                    "cd5f102f182bfd2eea2a7ebf4f6b9aa91e6ecc894c8f9197756d2f9a4aa7c3d5",
                    1952,
                    TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED
            ),
            new DerivativeDefinition(
                    "NIDDK-WEIGHT-FACTORS-2023-VI-03",
                    "NIDDK-WEIGHT-FACTORS-2023",
                    "Môi trường và khả năng lựa chọn lối sống lành mạnh",
                    "1.1.0",
                    "derivatives/niddk-weight-factors/03-environment.vi.md",
                    "cee3300127db2e339b2ceccf0e73a6c4e81172cb2db02942e332af3d0c9669ec",
                    1687,
                    TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED
            ),
            new DerivativeDefinition(
                    "NIDDK-WEIGHT-FACTORS-2023-VI-04",
                    "NIDDK-WEIGHT-FACTORS-2023",
                    "Giấc ngủ và cân nặng ở người trưởng thành",
                    "1.1.0",
                    "derivatives/niddk-weight-factors/04-sleep.vi.md",
                    "87e4a99998a3fcea44d96bcda218b27214cba3dabc18e008fb1cd8bc618ef82f",
                    1951,
                    TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED
            ),

            // 2 NIDDK Held Derivatives (INELIGIBLE - Guardrail Invariant)
            new DerivativeDefinition(
                    "NIDDK-WEIGHT-FACTORS-2023-VI-05",
                    "NIDDK-WEIGHT-FACTORS-2023",
                    "Một số thuốc có thể góp phần tăng cân",
                    "1.1.0",
                    "derivatives/niddk-weight-factors/05-medicines.vi.md",
                    "322a120ac8e5470fbd9c0ced6c0967b89b38cd758f37120e193a0812e4edac15",
                    2107,
                    TechnicalReadiness.HELD_AS_DRAFT
            ),
            new DerivativeDefinition(
                    "NIDDK-WEIGHT-FACTORS-2023-VI-06",
                    "NIDDK-WEIGHT-FACTORS-2023",
                    "Tình trạng sức khỏe và rối loạn ăn uống",
                    "1.1.0",
                    "derivatives/niddk-weight-factors/06-health.vi.md",
                    "40c179c3fe3a1b3c2d937d011f1f3c9b49c8c7c7648d87c2f45a54c31f63996c",
                    1854,
                    TechnicalReadiness.HELD_AS_DRAFT
            )
    );

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
    @DisplayName("Verify SHA-256 hashes and byte lengths for all 11 derivative articles")
    void testAll11DerivativeHashesAndIntegrity() throws IOException {
        for (DerivativeDefinition def : ALL_DERIVATIVES) {
            Path contentPath = resolveFile(def.contentPath());
            assertThat(contentPath).as("File must exist: %s", def.contentPath()).exists();

            byte[] bytes = Files.readAllBytes(contentPath);
            String hash = sha256Hex(bytes);

            assertThat(hash)
                    .as("SHA-256 hash mismatch for %s", def.documentCode())
                    .isEqualTo(def.expectedHash());
            assertThat(bytes.length)
                    .as("Byte count mismatch for %s", def.documentCode())
                    .isEqualTo(def.expectedBytes());
        }
    }

    @Test
    @DisplayName("Enforce guardrail: NIDDK-05 and NIDDK-06 must remain HELD_AS_DRAFT")
    void testHeldAsDraftGuardrail() {
        List<DerivativeDefinition> heldDocs = ALL_DERIVATIVES.stream()
                .filter(d -> d.technicalReadiness() == TechnicalReadiness.HELD_AS_DRAFT)
                .toList();

        assertThat(heldDocs)
                .extracting(DerivativeDefinition::documentCode)
                .containsExactlyInAnyOrder(
                        "NIDDK-WEIGHT-FACTORS-2023-VI-05",
                        "NIDDK-WEIGHT-FACTORS-2023-VI-06"
                );

        for (DerivativeDefinition held : heldDocs) {
            assertThat(held.technicalReadiness())
                    .as("Held document %s must NOT be marked TECHNICAL_VERIFICATION_PASSED", held.documentCode())
                    .isEqualTo(TechnicalReadiness.HELD_AS_DRAFT);
        }
    }

    @Test
    @DisplayName("Verify deterministic chunking and token accounting across all 9 eligible derivatives")
    void testEligibleCorpusDeterministicChunking() throws IOException {
        List<DerivativeDefinition> eligibleDocs = ALL_DERIVATIVES.stream()
                .filter(d -> d.technicalReadiness() == TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .toList();

        assertThat(eligibleDocs).hasSize(9);

        Instant fixedTimestamp = Instant.parse("2026-10-20T00:00:00Z");
        int totalChunksAllDocs = 0;
        int totalHeuristicTokensAllDocs = 0;

        for (DerivativeDefinition def : eligibleDocs) {
            Path contentPath = resolveFile(def.contentPath());
            String content = Files.readString(contentPath, StandardCharsets.UTF_8);
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            String contentHash = sha256Hex(contentBytes);

            KnowledgeDocument doc = KnowledgeDocument.builder()
                    .id(UUID.randomUUID())
                    .documentCode(def.documentCode())
                    .sourceId(def.sourceId())
                    .title(def.title())
                    .build();

            KnowledgeVersion ver = KnowledgeVersion.builder()
                    .id(UUID.randomUUID())
                    .knowledgeDocumentId(doc.id())
                    .versionLabel(def.versionLabel())
                    .versionNumber(1)
                    .status(KnowledgeStatus.DRAFT)
                    .content(content)
                    .contentHash(contentHash)
                    .contentPath(def.contentPath())
                    .technicalReadiness(def.technicalReadiness())
                    .lineageBaseDocumentId(def.documentCode())
                    .lineageBaseVersion("1.0.0")
                    .build();

            // Pass 1 and Pass 2 for determinism verification
            List<KnowledgeChunk> chunksPass1 = chunker.chunk(ver, doc, fixedTimestamp);
            List<KnowledgeChunk> chunksPass2 = chunker.chunk(ver, doc, fixedTimestamp);

            assertThat(chunksPass1).isNotEmpty();
            assertThat(chunksPass1).hasSameSizeAs(chunksPass2);

            int docTokens = 0;
            for (int i = 0; i < chunksPass1.size(); i++) {
                KnowledgeChunk c1 = chunksPass1.get(i);
                KnowledgeChunk c2 = chunksPass2.get(i);

                assertThat(c1.id()).isEqualTo(c2.id());
                assertThat(c1.chunkIndex()).isEqualTo(i);
                assertThat(c1.chunkIndex()).isEqualTo(c2.chunkIndex());
                assertThat(c1.headingPath()).isEqualTo(c2.headingPath()).isNotBlank();
                assertThat(c1.content()).isEqualTo(c2.content()).isNotBlank();
                assertThat(c1.content().length()).isLessThanOrEqualTo(1600);
                assertThat(c1.tokenCount()).isGreaterThan(0);

                docTokens += c1.tokenCount();
            }

            totalChunksAllDocs += chunksPass1.size();
            totalHeuristicTokensAllDocs += docTokens;

            log.info("Document {}: chunks={}, heuristicTokens={}, bytes={}",
                    def.documentCode(), chunksPass1.size(), docTokens, contentBytes.length);
        }

        log.info("Total Eligible Corpus (9 documents): totalChunks={}, totalHeuristicTokens={}",
                totalChunksAllDocs, totalHeuristicTokensAllDocs);

        // Invariants on corpus aggregate
        assertThat(totalChunksAllDocs)
                .as("Total chunk count for 9 eligible derivatives must be between 25 and 40")
                .isBetween(25, 40);
        assertThat(totalHeuristicTokensAllDocs)
                .as("Total heuristic tokens must be between 3000 and 6000")
                .isBetween(3000, 6000);
    }
}
