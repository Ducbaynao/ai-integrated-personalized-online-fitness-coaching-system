package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ContentConflictException;
import com.fitnesscoaching.platform.common.exception.ContentValidationException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeDocumentRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeReviewRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeVersionRepository;
import com.fitnesscoaching.platform.modules.content.domain.ImportCorpusBundle;
import com.fitnesscoaching.platform.modules.content.domain.ImportCorpusResult;
import com.fitnesscoaching.platform.modules.content.domain.ImportKnowledgeItem;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeReviewRecord;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import com.fitnesscoaching.platform.modules.content.domain.ReviewDecision;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentImportServiceUnitTest {

    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private KnowledgeVersionRepository versionRepository;
    @Mock private KnowledgeReviewRepository reviewRepository;
    @Mock private SpecialistReviewEvidenceRegistry reviewEvidenceRegistry;
    @Mock private UserAccountStatusQuery accountStatusQuery;
    @Mock private UserPermissionQuery permissionQuery;
    @Mock private UserRoleQuery userRoleQuery;
    @Mock private AuditService auditService;

    private ObjectMapper objectMapper;
    private ContentImportService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        objectMapper = new ObjectMapper();

        service = new ContentImportService(
                documentRepository,
                versionRepository,
                reviewRepository,
                reviewEvidenceRegistry,
                accountStatusQuery,
                permissionQuery,
                userRoleQuery,
                auditService,
                objectMapper,
                clock
        );
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void importBundle_untrustedEvidence_rejectedWithoutApproval() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_IMPORT")).thenReturn(true);

        String content = "Untrusted content";
        String hash = sha256(content);

        ImportKnowledgeItem item = new ImportKnowledgeItem(
                "WHO-PA-SB-2020-VI-01", "WHO-PA-SB-2020", "Title", "WHO", null, null, null, "vi",
                "1.1.0", 1, hash, "path", content.length(), "FAKE-EVIDENCE-XYZ",
                TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, null, null, null, null, content
        );
        ImportCorpusBundle bundle = new ImportCorpusBundle(1, "F01_IMPORT", "test-bundle", List.of(item));

        when(reviewEvidenceRegistry.verifyAndResolve("FAKE-EVIDENCE-XYZ", item))
                .thenThrow(new ContentValidationException("Unknown or untrusted specialist review evidence ID: FAKE-EVIDENCE-XYZ"));

        assertThatThrownBy(() -> service.importBundle(ACTOR_ID, bundle))
                .isInstanceOf(ContentValidationException.class)
                .hasMessageContaining("Unknown or untrusted specialist review evidence ID: FAKE-EVIDENCE-XYZ");

        verify(versionRepository, never()).save(any());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void importBundle_withoutEvidence_importedAsDraftStagedWithoutReview() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_IMPORT")).thenReturn(true);
        when(userRoleQuery.hasActiveRole(ACTOR_ID, "ADMIN")).thenReturn(true);

        String content = "Content without review evidence";
        String hash = sha256(content);

        ImportKnowledgeItem item = new ImportKnowledgeItem(
                "WHO-PA-SB-2020-VI-01", "WHO-PA-SB-2020", "Title", "WHO", null, null, null, "vi",
                "1.1.0", 1, hash, "path", content.length(), null,
                null, null, null, null, null, content
        );
        ImportCorpusBundle bundle = new ImportCorpusBundle(1, "F01_IMPORT", "test-bundle", List.of(item));

        UUID docId = UUID.randomUUID();
        when(reviewEvidenceRegistry.verifyAndResolve(null, item)).thenReturn(Optional.empty());
        when(documentRepository.findByDocumentCode("WHO-PA-SB-2020-VI-01")).thenReturn(Optional.empty());
        when(documentRepository.save(any(KnowledgeDocument.class))).thenAnswer(inv -> {
            KnowledgeDocument d = inv.getArgument(0);
            return KnowledgeDocument.builder().id(docId).documentCode(d.documentCode()).title(d.title()).sourceId(d.sourceId()).language(d.language()).build();
        });
        when(versionRepository.findByDocumentIdAndVersionLabel(docId, "1.1.0")).thenReturn(Optional.empty());
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        ImportCorpusResult result = service.importBundle(ACTOR_ID, bundle);

        assertThat(result.importedCount()).isEqualTo(1);
        assertThat(result.importedItems().get(0).technicalReadiness()).isEqualTo(TechnicalReadiness.DRAFT_STAGED);

        // Crucial: No review record was created!
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void importBundle_withVerifiedEvidence_linksOriginalSpecialistReviewer() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_IMPORT")).thenReturn(true);
        when(userRoleQuery.hasActiveRole(ACTOR_ID, "ADMIN")).thenReturn(true);

        String content = "Verified guidelines content";
        String hash = sha256(content);

        ImportKnowledgeItem item = new ImportKnowledgeItem(
                "WHO-PA-SB-2020-VI-01", "WHO-PA-SB-2020", "WHO Title", "WHO", null, null, null, "vi",
                "1.1.0", 1, hash, "path", content.length(), "REV-PO-20261008-01",
                TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, null, null, null, null, content
        );
        ImportCorpusBundle bundle = new ImportCorpusBundle(1, "F01_IMPORT", "who-2020", List.of(item));

        SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence verified =
                new SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence(
                        "REV-PO-20261008-01", "WHO-PA-SB-2020-VI-01", "WHO-PA-SB-2020", "1.1.0", hash, "path", content.length(),
                        "Chủ dự án — xác nhận trực tiếp của người dùng", "PROJECT_OWNER",
                        ReviewDecision.APPROVED, TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, null,
                        null, null, null
                );

        when(reviewEvidenceRegistry.verifyAndResolve("REV-PO-20261008-01", item)).thenReturn(Optional.of(verified));

        UUID docId = UUID.randomUUID();
        when(documentRepository.findByDocumentCode("WHO-PA-SB-2020-VI-01")).thenReturn(Optional.empty());
        when(documentRepository.save(any(KnowledgeDocument.class))).thenAnswer(inv -> {
            KnowledgeDocument d = inv.getArgument(0);
            return KnowledgeDocument.builder().id(docId).documentCode(d.documentCode()).title(d.title()).sourceId(d.sourceId()).language(d.language()).build();
        });
        when(versionRepository.findByDocumentIdAndVersionLabel(docId, "1.1.0")).thenReturn(Optional.empty());
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        ImportCorpusResult result = service.importBundle(ACTOR_ID, bundle);

        assertThat(result.importedCount()).isEqualTo(1);
        assertThat(result.importedItems().get(0).technicalReadiness()).isEqualTo(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED);

        // Verify review record linked with authentic specialist reviewer identity
        ArgumentCaptor<KnowledgeReviewRecord> reviewCaptor = ArgumentCaptor.forClass(KnowledgeReviewRecord.class);
        verify(reviewRepository).save(reviewCaptor.capture());
        KnowledgeReviewRecord savedReview = reviewCaptor.getValue();
        assertThat(savedReview.reviewerIdentity()).isEqualTo("Chủ dự án — xác nhận trực tiếp của người dùng");
        assertThat(savedReview.reviewerRole()).isEqualTo("PROJECT_OWNER");
        assertThat(savedReview.decision()).isEqualTo(ReviewDecision.APPROVED);
        assertThat(savedReview.contentHash()).isEqualTo(hash);

        // Verify immutable audit
        ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).recordAudit(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("KNOWLEDGE_VERSION_IMPORTED");
        assertThat(auditCaptor.getValue().actorRole()).isEqualTo("ADMIN");
        assertThat(auditCaptor.getValue().metadataJson()).contains("WHO-PA-SB-2020-VI-01");
    }

    @Test
    void importBundle_idempotent_skipsExistingIdenticalHash() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_IMPORT")).thenReturn(true);

        String content = "Same content";
        String hash = sha256(content);

        ImportKnowledgeItem item = new ImportKnowledgeItem(
                "WHO-PA-SB-2020-VI-01", "WHO-PA-SB-2020", "Title", "WHO", null, null, null, "vi",
                "1.1.0", 1, hash, "path", content.length(), null,
                null, null, null, null, null, content
        );
        ImportCorpusBundle bundle = new ImportCorpusBundle(1, "F01_IMPORT", "bundle", List.of(item));

        UUID docId = UUID.randomUUID();
        when(reviewEvidenceRegistry.verifyAndResolve(null, item)).thenReturn(Optional.empty());
        when(documentRepository.findByDocumentCode("WHO-PA-SB-2020-VI-01")).thenReturn(Optional.of(
                KnowledgeDocument.builder().id(docId).documentCode("WHO-PA-SB-2020-VI-01").build()
        ));

        KnowledgeVersion existing = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeDocumentId(docId)
                .versionLabel("1.1.0")
                .contentHash(hash)
                .contentPath("path")
                .sourceBytes(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .status(KnowledgeStatus.DRAFT)
                .build();
        when(versionRepository.findByDocumentIdAndVersionLabel(docId, "1.1.0")).thenReturn(Optional.of(existing));

        ImportCorpusResult result = service.importBundle(ACTOR_ID, bundle);

        assertThat(result.importedCount()).isEqualTo(0);
        assertThat(result.skippedCount()).isEqualTo(1);
        verify(versionRepository, never()).save(any());
    }

    @Test
    void importBundle_conflictDifferentHash_throwsContentConflictException() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_IMPORT")).thenReturn(true);

        String incomingContent = "Altered incoming content";
        String incomingHash = sha256(incomingContent);

        ImportKnowledgeItem item = new ImportKnowledgeItem(
                "WHO-PA-SB-2020-VI-01", "WHO-PA-SB-2020", "Title", "WHO", null, null, null, "vi",
                "1.1.0", 1, incomingHash, "path", incomingContent.length(), null,
                null, null, null, null, null, incomingContent
        );
        ImportCorpusBundle bundle = new ImportCorpusBundle(1, "F01_IMPORT", "bundle", List.of(item));

        UUID docId = UUID.randomUUID();
        when(reviewEvidenceRegistry.verifyAndResolve(null, item)).thenReturn(Optional.empty());
        when(documentRepository.findByDocumentCode("WHO-PA-SB-2020-VI-01")).thenReturn(Optional.of(
                KnowledgeDocument.builder().id(docId).documentCode("WHO-PA-SB-2020-VI-01").build()
        ));

        KnowledgeVersion existing = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeDocumentId(docId)
                .versionLabel("1.1.0")
                .contentHash("original-hash-different")
                .status(KnowledgeStatus.DRAFT)
                .build();
        when(versionRepository.findByDocumentIdAndVersionLabel(docId, "1.1.0")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.importBundle(ACTOR_ID, bundle))
                .isInstanceOf(ContentConflictException.class)
                .hasMessageContaining("Conflict on WHO-PA-SB-2020-VI-01 version 1.1.0");
    }

    @Test
    void importBundle_missingPermission_throwsAccessDeniedException() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_IMPORT")).thenReturn(false);

        ImportCorpusBundle bundle = new ImportCorpusBundle(1, "F01_IMPORT", "b", List.of(
                new ImportKnowledgeItem("D1", "S1", "T", "A", null, null, null, "vi", "1.0", 1, "h", "p", 1, null, null, null, null, null, null, "c")
        ));

        assertThatThrownBy(() -> service.importBundle(ACTOR_ID, bundle))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User lacks required permission: KNOWLEDGE_IMPORT");
    }
}
