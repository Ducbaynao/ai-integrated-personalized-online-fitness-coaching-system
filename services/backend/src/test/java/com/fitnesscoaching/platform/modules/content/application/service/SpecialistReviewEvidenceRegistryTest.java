package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ContentValidationException;
import com.fitnesscoaching.platform.modules.content.domain.ImportKnowledgeItem;
import com.fitnesscoaching.platform.modules.content.domain.ReviewDecision;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecialistReviewEvidenceRegistryTest {

    private SpecialistReviewEvidenceRegistry registry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        registry = new SpecialistReviewEvidenceRegistry(objectMapper);
    }

    @Test
    void verifyAndResolve_nullOrBlankEvidence_returnsEmpty() {
        ImportKnowledgeItem item = createItem("WHO-PA-SB-2020-VI-01", "1.1.0", "hash123", null);
        Optional<SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence> result =
                registry.verifyAndResolve(null, item);
        assertThat(result).isEmpty();

        result = registry.verifyAndResolve("   ", item);
        assertThat(result).isEmpty();
    }

    @Test
    void verifyAndResolve_unknownEvidenceId_throwsContentValidationException() {
        ImportKnowledgeItem item = createItem("WHO-PA-SB-2020-VI-01", "1.1.0", "hash123", "FAKE-EVIDENCE-999");
        assertThatThrownBy(() -> registry.verifyAndResolve("FAKE-EVIDENCE-999", item))
                .isInstanceOf(ContentValidationException.class)
                .hasMessageContaining("Unknown or untrusted specialist review evidence ID: FAKE-EVIDENCE-999");
    }

    @Test
    void verifyAndResolve_uncoveredDocument_throwsContentValidationException() {
        ImportKnowledgeItem item = createItem("UNKNOWN-DOC-01", "1.1.0", "hash123", "REV-PO-20261008-01");
        assertThatThrownBy(() -> registry.verifyAndResolve("REV-PO-20261008-01", item))
                .isInstanceOf(ContentValidationException.class)
                .hasMessageContaining("is not covered by review evidence REV-PO-20261008-01");
    }

    @Test
    void verifyAndResolve_tamperedHash_throwsContentValidationException() {
        ImportKnowledgeItem item = createItem("WHO-PA-SB-2020-VI-01", "1.1.0", "tampered-hash-123456", "REV-PO-20261008-01");
        assertThatThrownBy(() -> registry.verifyAndResolve("REV-PO-20261008-01", item))
                .isInstanceOf(ContentValidationException.class)
                .hasMessageContaining("Content hash mismatch for document WHO-PA-SB-2020-VI-01");
    }

    @Test
    void verifyAndResolve_pathTraversal_throwsContentValidationException() {
        ImportKnowledgeItem item = new ImportKnowledgeItem(
                "WHO-PA-SB-2020-VI-01", "WHO-PA-SB-2020", "Title",
                "WHO", null, null, null, "vi", "1.1.0", 1,
                "5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840",
                "../derivatives/who-2020/01-scope.vi.md", 3429, "REV-PO-20261008-01",
                TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, null,
                null, null, null,
                "Sample content"
        );

        assertThatThrownBy(() -> registry.verifyAndResolve("REV-PO-20261008-01", item))
                .isInstanceOf(ContentValidationException.class)
                .hasMessageContaining("Content path is invalid or contains traversal sequences");
    }

    @Test
    void verifyAndResolve_mismatchedSourceBytes_throwsContentValidationException() {
        // UTF-8 content length is 14 bytes ("Sample content"), but client claims 9999 bytes
        ImportKnowledgeItem item = new ImportKnowledgeItem(
                "WHO-PA-SB-2020-VI-01", "WHO-PA-SB-2020", "Title",
                "WHO", null, null, null, "vi", "1.1.0", 1,
                "5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840",
                "derivatives/who-2020/01-scope.vi.md", 9999, "REV-PO-20261008-01",
                TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, null,
                null, null, null,
                "Sample content"
        );

        assertThatThrownBy(() -> registry.verifyAndResolve("REV-PO-20261008-01", item))
                .isInstanceOf(ContentValidationException.class)
                .hasMessageContaining("sourceBytes mismatch for WHO-PA-SB-2020-VI-01");
    }

    @Test
    void verifyAndResolve_mismatchedLineage_throwsContentValidationException() {
        String whoHash = "5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840";
        // Client provides mismatched lineage base version ("9.9.9" instead of "1.0.0")
        ImportKnowledgeItem item = new ImportKnowledgeItem(
                "WHO-PA-SB-2020-VI-01", "WHO-PA-SB-2020", "Title",
                "WHO", null, null, null, "vi", "1.1.0", 1,
                whoHash, "derivatives/who-2020/01-scope.vi.md", null, "REV-PO-20261008-01",
                TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, null,
                "WHO-PA-SB-2020-VI-01", "9.9.9", "83f7921e04986d1d3843ceb5d07d7911aac0c93dd81b87ab6f4659fae42277c1",
                null
        );

        assertThatThrownBy(() -> registry.verifyAndResolve("REV-PO-20261008-01", item))
                .isInstanceOf(ContentValidationException.class)
                .hasMessageContaining("Lineage base version mismatch for WHO-PA-SB-2020-VI-01");
    }

    @Test
    void verifyAndResolve_byteCountMismatchFromEvidence_throwsContentValidationException() {
        String whoHash = "5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840";
        // Actual content is 14 bytes but evidence specifies 3429 bytes
        ImportKnowledgeItem item = new ImportKnowledgeItem(
                "WHO-PA-SB-2020-VI-01", "WHO-PA-SB-2020", "Title",
                "WHO", null, null, null, "vi", "1.1.0", 1,
                whoHash, "derivatives/who-2020/01-scope.vi.md", null, "REV-PO-20261008-01",
                TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, null,
                null, null, null,
                "Sample content"
        );

        assertThatThrownBy(() -> registry.verifyAndResolve("REV-PO-20261008-01", item))
                .isInstanceOf(ContentValidationException.class)
                .hasMessageContaining("Byte count mismatch for document WHO-PA-SB-2020-VI-01");
    }

    @Test
    void verifyAndResolve_validWhoEvidence_resolvesAuthenticReviewerAndPassedReadiness() {
        String correctHash = "5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840";
        ImportKnowledgeItem item = createItem("WHO-PA-SB-2020-VI-01", "1.1.0", correctHash, "REV-PO-20261008-01");

        Optional<SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence> result =
                registry.verifyAndResolve("REV-PO-20261008-01", item);

        assertThat(result).isPresent();
        SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence evidence = result.get();
        assertThat(evidence.reviewEvidenceId()).isEqualTo("REV-PO-20261008-01");
        assertThat(evidence.reviewerIdentity()).isEqualTo("Chủ dự án — xác nhận trực tiếp của người dùng");
        assertThat(evidence.reviewerRole()).isEqualTo("PROJECT_OWNER");
        assertThat(evidence.decision()).isEqualTo(ReviewDecision.APPROVED);
        assertThat(evidence.technicalReadiness()).isEqualTo(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED);
        assertThat(evidence.holdReason()).isNull();
        // Derivative lineage is resolved from verified evidence
        assertThat(evidence.baseDocumentId()).isEqualTo("WHO-PA-SB-2020-VI-01");
        assertThat(evidence.baseVersion()).isEqualTo("1.0.0");
        assertThat(evidence.baseSha256()).isEqualTo("83f7921e04986d1d3843ceb5d07d7911aac0c93dd81b87ab6f4659fae42277c1");
    }

    @Test
    void verifyAndResolve_validNiddkEvidence_forcesHeldAsDraftReadiness() {
        String correctNiddkHash = "322a120ac8e5470fbd9c0ced6c0967b89b38cd758f37120e193a0812e4edac15";
        // Even if client item claimed TECHNICAL_VERIFICATION_PASSED:
        ImportKnowledgeItem item = new ImportKnowledgeItem(
                "NIDDK-WEIGHT-FACTORS-2023-VI-05", "NIDDK-WEIGHT-FACTORS-2023", "Title",
                "NIDDK", null, null, null, "vi", "1.1.0", 1,
                correctNiddkHash, "derivatives/niddk-weight-factors/05-medicines.vi.md", null, "REV-PO-20261008-01",
                TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, null,
                null, null, null,
                null
        );

        Optional<SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence> result =
                registry.verifyAndResolve("REV-PO-20261008-01", item);

        assertThat(result).isPresent();
        SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence evidence = result.get();
        assertThat(evidence.technicalReadiness()).isEqualTo(TechnicalReadiness.HELD_AS_DRAFT);
        assertThat(evidence.holdReason()).isNotNull();
    }

    @Test
    void verifyAndResolve_snapshotArticleHasApproval_remainsDraftStagedWithoutTechnicalVerification() {
        // ISSN-PROTEIN-EXERCISE-2017-VI-01 is in approved_snapshot_articles (curated source, not derivative)
        String issnHash = "78f5c8cab39d2524bc9a309de18800280ae92ccd4f2f085180e4fd6e71d7f084";
        ImportKnowledgeItem item = new ImportKnowledgeItem(
                "ISSN-PROTEIN-EXERCISE-2017-VI-01", "ISSN-PROTEIN-EXERCISE-2017", "Title",
                "ISSN", null, null, null, "vi", "1.0.0", 1,
                issnHash, "data/knowledge/curated/issn-protein-2017/01-scope.vi.md", null, "REV-PO-20261008-01",
                TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, null,
                null, null, null,
                null
        );

        Optional<SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence> result =
                registry.verifyAndResolve("REV-PO-20261008-01", item);

        assertThat(result).isPresent();
        SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence evidence = result.get();
        // Crucial invariant: Specialist approval does NOT imply technical verification passed!
        // Snapshot articles remain DRAFT_STAGED and cannot be published until verified.
        assertThat(evidence.decision()).isEqualTo(ReviewDecision.APPROVED);
        assertThat(evidence.technicalReadiness()).isEqualTo(TechnicalReadiness.DRAFT_STAGED);
    }

    @Test
    void unavailableRegistry_throwsContentValidationExceptionFailClosed(@TempDir Path tempDir) {
        Path nonExistent = tempDir.resolve("non_existent.json");
        SpecialistReviewEvidenceRegistry brokenRegistry = new SpecialistReviewEvidenceRegistry(
                objectMapper, nonExistent, nonExistent
        );

        assertThat(brokenRegistry.isAvailable()).isFalse();

        ImportKnowledgeItem item = createItem("WHO-PA-SB-2020-VI-01", "1.1.0", "hash", "REV-PO-20261008-01");
        assertThatThrownBy(() -> brokenRegistry.verifyAndResolve("REV-PO-20261008-01", item))
                .isInstanceOf(ContentValidationException.class)
                .hasMessageContaining("Specialist review evidence registry is unavailable or misconfigured");
    }

    @Test
    void corruptedRegistryWithoutVerdict_failsToInitializeWithoutDefaultApproved(@TempDir Path tempDir) throws IOException {
        Path invalidJson = tempDir.resolve("corrupted_review.json");
        Files.writeString(invalidJson, """
                {
                  "review_record_id": "REV-TEST-01",
                  "reviewer_role": "PROJECT_OWNER",
                  "reviewer_identity": "Test Reviewer"
                }
                """);

        SpecialistReviewEvidenceRegistry brokenRegistry = new SpecialistReviewEvidenceRegistry(
                objectMapper, invalidJson, tempDir.resolve("empty.json")
        );

        assertThat(brokenRegistry.isAvailable()).isFalse();
        assertThat(brokenRegistry.getInitializationError()).contains("specialist_review_verdict");
    }

    private ImportKnowledgeItem createItem(String docCode, String verLabel, String hash, String evidenceId) {
        return new ImportKnowledgeItem(
                docCode, "WHO-PA-SB-2020", "Title",
                "WHO", null, null, null, "vi", verLabel, 1,
                hash, "derivatives/who-2020/01-scope.vi.md", null, evidenceId,
                TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, null,
                null, null, null,
                null
        );
    }
}
