package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ContentConflictException;
import com.fitnesscoaching.platform.common.exception.ContentValidationException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.content.application.port.in.ImportKnowledgeCorpusUseCase;
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
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ContentImportService implements ImportKnowledgeCorpusUseCase {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeReviewRepository reviewRepository;
    private final SpecialistReviewEvidenceRegistry reviewEvidenceRegistry;
    private final UserAccountStatusQuery accountStatusQuery;
    private final UserPermissionQuery permissionQuery;
    private final UserRoleQuery userRoleQuery;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ContentImportService(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeVersionRepository versionRepository,
            KnowledgeReviewRepository reviewRepository,
            SpecialistReviewEvidenceRegistry reviewEvidenceRegistry,
            UserAccountStatusQuery accountStatusQuery,
            UserPermissionQuery permissionQuery,
            UserRoleQuery userRoleQuery,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.reviewRepository = reviewRepository;
        this.reviewEvidenceRegistry = reviewEvidenceRegistry;
        this.accountStatusQuery = accountStatusQuery;
        this.permissionQuery = permissionQuery;
        this.userRoleQuery = userRoleQuery;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public ImportCorpusResult importBundle(UUID actorId, ImportCorpusBundle bundle) {
        validateActorAuthority(actorId);

        if (bundle == null || bundle.items() == null || bundle.items().isEmpty()) {
            throw new ContentValidationException("Import corpus bundle cannot be null or empty");
        }

        Instant now = Instant.now(clock);
        List<ImportCorpusResult.ImportedItemSummary> importedSummaries = new ArrayList<>();
        List<String> skippedItems = new ArrayList<>();

        for (ImportKnowledgeItem item : bundle.items()) {
            validateItem(item);

            // Compute and verify SHA-256 hash of content
            verifyContentHash(item);

            // 1. Resolve and strictly verify specialist review evidence from server-trusted registry
            Optional<SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence> verifiedEvidenceOpt =
                    reviewEvidenceRegistry.verifyAndResolve(item.reviewEvidenceId(), item);

            TechnicalReadiness finalReadiness;
            String finalHoldReason;
            String finalReviewEvidenceId;
            String finalBaseDocId;
            String finalBaseVer;
            String finalBaseHash;

            int actualBytes = item.content().getBytes(StandardCharsets.UTF_8).length;

            if (verifiedEvidenceOpt.isPresent()) {
                SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence verified = verifiedEvidenceOpt.get();
                finalReadiness = verified.technicalReadiness();
                finalHoldReason = verified.holdReason();
                finalReviewEvidenceId = verified.reviewEvidenceId();
                // If client omitted derivative lineage, populate it strictly from verified evidence
                finalBaseDocId = item.lineageBaseDocumentId() != null ? item.lineageBaseDocumentId() : verified.baseDocumentId();
                finalBaseVer = item.lineageBaseVersion() != null ? item.lineageBaseVersion() : verified.baseVersion();
                finalBaseHash = item.lineageBaseHash() != null ? item.lineageBaseHash() : verified.baseSha256();
            } else {
                finalReadiness = TechnicalReadiness.DRAFT_STAGED;
                finalHoldReason = item.holdReason();
                finalReviewEvidenceId = null;
                finalBaseDocId = item.lineageBaseDocumentId();
                finalBaseVer = item.lineageBaseVersion();
                finalBaseHash = item.lineageBaseHash();
            }

            // 2. Find or create document
            KnowledgeDocument document = findOrCreateDocument(item, actorId, now);

            // 3. Check existing version with strict provenance comparison
            Optional<KnowledgeVersion> existingVersionOpt = versionRepository.findByDocumentIdAndVersionLabel(
                    document.id(), item.versionLabel()
            );

            if (existingVersionOpt.isPresent()) {
                KnowledgeVersion existingVersion = existingVersionOpt.get();
                if (existingVersion.contentHash().equalsIgnoreCase(item.contentHash())) {
                    // Same content hash: verify that incoming provenance does not conflict with existing version
                    boolean pathMatches = java.util.Objects.equals(existingVersion.contentPath(), item.contentPath());
                    boolean bytesMatches = existingVersion.sourceBytes() == null || existingVersion.sourceBytes() == actualBytes;
                    boolean evidenceMatches = java.util.Objects.equals(existingVersion.reviewEvidenceId(), finalReviewEvidenceId);
                    boolean baseDocMatches = java.util.Objects.equals(existingVersion.lineageBaseDocumentId(), finalBaseDocId);
                    boolean baseVerMatches = java.util.Objects.equals(existingVersion.lineageBaseVersion(), finalBaseVer);
                    boolean baseHashMatches = (existingVersion.lineageBaseHash() == null && finalBaseHash == null) ||
                            (existingVersion.lineageBaseHash() != null && existingVersion.lineageBaseHash().equalsIgnoreCase(finalBaseHash));

                    if (!pathMatches || !bytesMatches || !evidenceMatches || !baseDocMatches || !baseVerMatches || !baseHashMatches) {
                        throw new ContentConflictException("Incoming provenance for " + item.documentCode() + " version " +
                                item.versionLabel() + " conflicts with existing version provenance");
                    }

                    // Truly idempotent: identical content and identical provenance -> skip
                    skippedItems.add(item.documentCode() + ":" + item.versionLabel());
                    continue;
                } else {
                    // Conflict: same version label with DIFFERENT content hash -> reject
                    throw new ContentConflictException("Conflict on " + item.documentCode() + " version " +
                            item.versionLabel() + ": existing hash " + existingVersion.contentHash() +
                            " does not match incoming hash " + item.contentHash());
                }
            }

            // 4. Create new DRAFT version
            KnowledgeVersion newVersion = KnowledgeVersion.builder()
                    .id(UUID.randomUUID())
                    .knowledgeDocumentId(document.id())
                    .versionNumber(item.versionNumber() > 0 ? item.versionNumber() : 1)
                    .versionLabel(item.versionLabel())
                    .status(KnowledgeStatus.DRAFT)
                    .technicalReadiness(finalReadiness)
                    .holdReason(finalHoldReason)
                    .contentHash(item.contentHash())
                    .contentPath(item.contentPath())
                    .sourceBytes(actualBytes)
                    .reviewEvidenceId(finalReviewEvidenceId)
                    .lineageBaseDocumentId(finalBaseDocId)
                    .lineageBaseVersion(finalBaseVer)
                    .lineageBaseHash(finalBaseHash)
                    .content(item.content())
                    .createdBy(actorId)
                    .createdAt(now)
                    .build();

            KnowledgeVersion savedVersion;
            try {
                savedVersion = versionRepository.save(newVersion);
            } catch (DuplicateKeyException dke) {
                // Concurrent insert of the exact same version: re-check for idempotency or conflict
                Optional<KnowledgeVersion> concurrentOpt = versionRepository.findByDocumentIdAndVersionLabel(
                        document.id(), item.versionLabel()
                );
                if (concurrentOpt.isPresent()) {
                    KnowledgeVersion concurrent = concurrentOpt.get();
                    if (concurrent.contentHash().equalsIgnoreCase(item.contentHash())) {
                        skippedItems.add(item.documentCode() + ":" + item.versionLabel());
                        continue;
                    } else {
                        throw new ContentConflictException("Conflict on " + item.documentCode() + " version " +
                                item.versionLabel() + ": existing hash " + concurrent.contentHash() +
                                " does not match incoming hash " + item.contentHash());
                    }
                }
                throw dke;
            }

            // 5. If specialist review evidence was verified, create KnowledgeReviewRecord retaining authentic reviewer identity
            if (verifiedEvidenceOpt.isPresent()) {
                SpecialistReviewEvidenceRegistry.VerifiedArticleEvidence verified = verifiedEvidenceOpt.get();
                KnowledgeReviewRecord reviewRecord = new KnowledgeReviewRecord(
                        UUID.randomUUID(),
                        savedVersion.id(),
                        actorId, // Satisfies DB foreign key constraint to users
                        verified.reviewerIdentity(), // Authentic original reviewer identity (e.g. Chủ dự án)
                        verified.reviewerRole(),     // PROJECT_OWNER
                        verified.decision(),         // APPROVED
                        verified.contentSha256(),    // Approved content hash
                        "Specialist review evidence verified: " + verified.reviewEvidenceId() +
                                " (Reviewer: " + verified.reviewerIdentity() + " [" + verified.reviewerRole() + "])",
                        now
                );
                reviewRepository.save(reviewRecord);
                versionRepository.updateReviewInfo(savedVersion.id(), actorId, now);
            }

            // 6. Immutable Audit using proper JSON serializer
            Map<String, Object> auditMetadata = new LinkedHashMap<>();
            auditMetadata.put("documentCode", item.documentCode());
            auditMetadata.put("versionLabel", item.versionLabel());
            auditMetadata.put("contentHash", item.contentHash());
            auditMetadata.put("sourceId", item.sourceId());
            auditMetadata.put("technicalReadiness", savedVersion.technicalReadiness().name());
            auditMetadata.put("reviewEvidenceId", savedVersion.reviewEvidenceId());

            auditService.recordAudit(AuditRecord.builder()
                    .actorUserId(actorId)
                    .actorRole(resolveActorRole(actorId))
                    .action("KNOWLEDGE_VERSION_IMPORTED")
                    .targetType("KNOWLEDGE_VERSION")
                    .targetId(savedVersion.id())
                    .metadataJson(toJson(auditMetadata))
                    .occurredAt(now)
                    .build());

            importedSummaries.add(new ImportCorpusResult.ImportedItemSummary(
                    document.id(),
                    savedVersion.id(),
                    document.documentCode(),
                    savedVersion.versionLabel(),
                    savedVersion.status(),
                    savedVersion.technicalReadiness()
            ));
        }

        return new ImportCorpusResult(
                bundle.items().size(),
                importedSummaries.size(),
                skippedItems.size(),
                importedSummaries,
                skippedItems
        );
    }

    private void validateActorAuthority(UUID actorId) {
        if (actorId == null) {
            throw new AccessDeniedException("Actor ID is required for knowledge import");
        }
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId)
                .orElseThrow(() -> new AccountUnavailableException("Actor account does not exist"));
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Actor account is not active");
        }
        if (!permissionQuery.hasActivePermission(actorId, "KNOWLEDGE_IMPORT")) {
            throw new AccessDeniedException("User lacks required permission: KNOWLEDGE_IMPORT");
        }
    }

    private String resolveActorRole(UUID actorId) {
        if (actorId == null) return "SYSTEM";
        if (userRoleQuery.hasActiveRole(actorId, "ADMIN")) return "ADMIN";
        if (userRoleQuery.hasActiveRole(actorId, "TRAINER")) return "TRAINER";
        if (userRoleQuery.hasActiveRole(actorId, "STUDENT")) return "STUDENT";
        return "USER";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize audit payload to JSON", e);
        }
    }

    private void validateItem(ImportKnowledgeItem item) {
        if (item.documentCode() == null || item.documentCode().isBlank()) {
            throw new ContentValidationException("documentCode is required for knowledge item");
        }
        if (item.sourceId() == null || item.sourceId().isBlank()) {
            throw new ContentValidationException("sourceId is required for knowledge item");
        }
        if (item.title() == null || item.title().isBlank()) {
            throw new ContentValidationException("title is required for knowledge item");
        }
        if (item.versionLabel() == null || item.versionLabel().isBlank()) {
            throw new ContentValidationException("versionLabel is required for knowledge item");
        }
        if (item.contentHash() == null || item.contentHash().isBlank()) {
            throw new ContentValidationException("contentHash is required for knowledge item");
        }
        if (item.content() == null) {
            throw new ContentValidationException("content cannot be null for knowledge item");
        }
    }

    private void verifyContentHash(ImportKnowledgeItem item) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(item.content().getBytes(StandardCharsets.UTF_8));
            String computedHex = HexFormat.of().formatHex(hashBytes);
            if (!computedHex.equalsIgnoreCase(item.contentHash())) {
                throw new ContentValidationException("Content SHA-256 mismatch for document " +
                        item.documentCode() + ": expected " + item.contentHash() + " but computed " + computedHex);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private KnowledgeDocument findOrCreateDocument(ImportKnowledgeItem item, UUID actorId, Instant now) {
        return documentRepository.findByDocumentCode(item.documentCode())
                .orElseGet(() -> {
                    KnowledgeDocument newDoc = KnowledgeDocument.builder()
                            .id(UUID.randomUUID())
                            .documentCode(item.documentCode())
                            .title(item.title())
                            .sourceId(item.sourceId())
                            .sourceName(item.sourceName())
                            .sourceUrl(item.sourceUrl())
                            .author(item.author())
                            .documentType(item.documentType() != null ? item.documentType() : "ARTICLE")
                            .language(item.language() != null ? item.language() : "vi")
                            .createdBy(actorId)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                    return documentRepository.save(newDoc);
                });
    }
}
