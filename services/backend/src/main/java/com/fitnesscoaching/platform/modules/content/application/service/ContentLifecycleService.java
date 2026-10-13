package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ContentLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.ContentNotFoundException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.content.application.port.in.ContentLifecycleUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.out.AiIndexingNotificationPort;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeDocumentRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeProcessingRunRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeReviewRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeVersionRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeReviewRecord;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import com.fitnesscoaching.platform.modules.content.domain.ReviewDecision;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class ContentLifecycleService implements ContentLifecycleUseCase {

    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeReviewRepository reviewRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeProcessingRunRepository processingRunRepository;
    private final UserAccountStatusQuery accountStatusQuery;
    private final UserPermissionQuery permissionQuery;
    private final UserRoleQuery userRoleQuery;
    private final AuditService auditService;
    private final AiIndexingNotificationPort aiIndexingNotificationPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ContentLifecycleService(
            KnowledgeVersionRepository versionRepository,
            KnowledgeReviewRepository reviewRepository,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeProcessingRunRepository processingRunRepository,
            UserAccountStatusQuery accountStatusQuery,
            UserPermissionQuery permissionQuery,
            UserRoleQuery userRoleQuery,
            AuditService auditService,
            AiIndexingNotificationPort aiIndexingNotificationPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.versionRepository = versionRepository;
        this.reviewRepository = reviewRepository;
        this.documentRepository = documentRepository;
        this.processingRunRepository = processingRunRepository;
        this.accountStatusQuery = accountStatusQuery;
        this.permissionQuery = permissionQuery;
        this.userRoleQuery = userRoleQuery;
        this.auditService = auditService;
        this.aiIndexingNotificationPort = aiIndexingNotificationPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void reviewVersion(UUID actorId, UUID versionId, ReviewDecision decision, String notes) {
        validateActorAuthority(actorId, "KNOWLEDGE_REVIEW");

        // Row lock on version
        KnowledgeVersion version = versionRepository.findByIdForUpdate(versionId)
                .orElseThrow(() -> new ContentNotFoundException("Knowledge version not found: " + versionId));

        if (version.status() != KnowledgeStatus.DRAFT) {
            throw new ContentLifecycleConflictException("Cannot review knowledge version with status: " + version.status());
        }

        Instant now = Instant.now(clock);
        String actorRole = resolveActorRole(actorId);

        KnowledgeReviewRecord reviewRecord = new KnowledgeReviewRecord(
                UUID.randomUUID(),
                version.id(),
                actorId,
                "USER:" + actorId,
                actorRole,
                decision,
                version.contentHash(),
                notes,
                now
        );
        reviewRepository.save(reviewRecord);
        versionRepository.updateReviewInfo(version.id(), actorId, now);

        Map<String, Object> auditMetadata = new LinkedHashMap<>();
        auditMetadata.put("decision", decision.name());
        auditMetadata.put("notes", notes != null ? notes : "");
        auditMetadata.put("contentHash", version.contentHash());

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(actorId)
                .actorRole(actorRole)
                .action("KNOWLEDGE_VERSION_REVIEWED")
                .targetType("KNOWLEDGE_VERSION")
                .targetId(version.id())
                .metadataJson(toJson(auditMetadata))
                .occurredAt(now)
                .build());
    }

    @Override
    public void publishVersion(UUID actorId, UUID versionId) {
        validateActorAuthority(actorId, "KNOWLEDGE_PUBLISH");

        // Row lock on version to prevent concurrent modifications
        KnowledgeVersion version = versionRepository.findByIdForUpdate(versionId)
                .orElseThrow(() -> new ContentNotFoundException("Knowledge version not found: " + versionId));

        if (version.status() != KnowledgeStatus.DRAFT) {
            throw new ContentLifecycleConflictException("Cannot publish version with status " + version.status() +
                    ". Only DRAFT versions can be published.");
        }

        // GUARD 1: Strict fail-closed Technical Readiness check
        if (version.technicalReadiness() != TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED) {
            throw new ContentLifecycleConflictException("Knowledge version technical readiness must be TECHNICAL_VERIFICATION_PASSED to publish. Current readiness: " +
                    version.technicalReadiness() + (version.holdReason() != null ? " (Hold reason: " + version.holdReason() + ")" : ""));
        }

        // GUARD 2: Must have an effective APPROVED specialist review matching the current content hash
        if (!reviewRepository.hasEffectiveApprovedReview(version.id(), version.contentHash())) {
            throw new ContentLifecycleConflictException("Knowledge version requires an effective APPROVED review matching current content hash before activation");
        }

        Instant now = Instant.now(clock);
        int rowsUpdated = versionRepository.updateStatusAndPublishInfo(
                version.id(),
                KnowledgeStatus.ACTIVE,
                actorId,
                now,
                now
        );

        if (rowsUpdated == 0) {
            throw new ContentLifecycleConflictException("Concurrent modification: Version " + versionId + " is no longer in DRAFT status");
        }

        KnowledgeDocument document = documentRepository.findById(version.knowledgeDocumentId()).orElse(null);
        String docCode = document != null ? document.documentCode() : "";
        String sourceId = document != null ? document.sourceId() : "";

        // 3. Durable Indexing Outbox: Persist indexing run inside publish transaction with operation identity
        Map<String, Object> jobStats = new LinkedHashMap<>();
        jobStats.put("documentCode", docCode);
        jobStats.put("versionLabel", version.versionLabel());
        jobStats.put("sourceId", sourceId);
        String operationKey = "INDEX:" + version.id();
        processingRunRepository.enqueue(version.id(), "REINDEX", operationKey, toJson(jobStats), now);

        // 4. Immutable Audit
        Map<String, Object> beforeData = Map.of("status", "DRAFT");
        Map<String, Object> afterData = Map.of("status", "ACTIVE");
        Map<String, Object> auditMetadata = new LinkedHashMap<>();
        auditMetadata.put("versionLabel", version.versionLabel());
        auditMetadata.put("documentCode", docCode);
        auditMetadata.put("contentHash", version.contentHash());

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(actorId)
                .actorRole(resolveActorRole(actorId))
                .action("KNOWLEDGE_VERSION_PUBLISHED")
                .targetType("KNOWLEDGE_VERSION")
                .targetId(version.id())
                .beforeDataJson(toJson(beforeData))
                .afterDataJson(toJson(afterData))
                .metadataJson(toJson(auditMetadata))
                .occurredAt(now)
                .build());

        // Decoupled notification hook
        aiIndexingNotificationPort.notifyKnowledgePublished(version.id(), docCode, version.versionLabel(), sourceId);
    }

    @Override
    public void archiveVersion(UUID actorId, UUID versionId, String reason) {
        validateActorAuthority(actorId, "KNOWLEDGE_ARCHIVE");

        // Row lock on version
        KnowledgeVersion version = versionRepository.findByIdForUpdate(versionId)
                .orElseThrow(() -> new ContentNotFoundException("Knowledge version not found: " + versionId));

        if (version.status() != KnowledgeStatus.ACTIVE) {
            throw new ContentLifecycleConflictException("Only ACTIVE versions can be archived. Current status: " + version.status());
        }

        Instant now = Instant.now(clock);
        int rowsUpdated = versionRepository.updateStatusAndArchiveInfo(version.id(), KnowledgeStatus.ARCHIVED, now);

        if (rowsUpdated == 0) {
            throw new ContentLifecycleConflictException("Concurrent modification: Version " + versionId + " is no longer in ACTIVE status");
        }

        Map<String, Object> beforeData = Map.of("status", "ACTIVE");
        Map<String, Object> afterData = Map.of("status", "ARCHIVED");
        Map<String, Object> auditMetadata = new LinkedHashMap<>();
        auditMetadata.put("reason", reason != null ? reason : "Retired by administrator");
        auditMetadata.put("versionLabel", version.versionLabel());
        auditMetadata.put("contentHash", version.contentHash());

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(actorId)
                .actorRole(resolveActorRole(actorId))
                .action("KNOWLEDGE_VERSION_ARCHIVED")
                .targetType("KNOWLEDGE_VERSION")
                .targetId(version.id())
                .beforeDataJson(toJson(beforeData))
                .afterDataJson(toJson(afterData))
                .metadataJson(toJson(auditMetadata))
                .occurredAt(now)
                .build());
    }

    private void validateActorAuthority(UUID actorId, String requiredPermission) {
        if (actorId == null) {
            throw new AccessDeniedException("Actor ID is required");
        }
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId)
                .orElseThrow(() -> new AccountUnavailableException("Actor account does not exist"));
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Actor account is not active");
        }
        if (!permissionQuery.hasActivePermission(actorId, requiredPermission)) {
            throw new AccessDeniedException("User lacks required permission: " + requiredPermission);
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
}
