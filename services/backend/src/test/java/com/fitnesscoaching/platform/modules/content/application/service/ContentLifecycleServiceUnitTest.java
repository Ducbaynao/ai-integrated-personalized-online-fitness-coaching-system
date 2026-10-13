package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ContentLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.ContentNotFoundException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentLifecycleServiceUnitTest {

    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOC_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VERSION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-10-11T14:00:00Z");

    @Mock private KnowledgeVersionRepository versionRepository;
    @Mock private KnowledgeReviewRepository reviewRepository;
    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private KnowledgeProcessingRunRepository processingRunRepository;
    @Mock private UserAccountStatusQuery accountStatusQuery;
    @Mock private UserPermissionQuery permissionQuery;
    @Mock private UserRoleQuery userRoleQuery;
    @Mock private AuditService auditService;
    @Mock private AiIndexingNotificationPort aiIndexingNotificationPort;

    private ObjectMapper objectMapper;
    private ContentLifecycleService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        objectMapper = new ObjectMapper();

        service = new ContentLifecycleService(
                versionRepository,
                reviewRepository,
                documentRepository,
                processingRunRepository,
                accountStatusQuery,
                permissionQuery,
                userRoleQuery,
                auditService,
                aiIndexingNotificationPort,
                objectMapper,
                clock
        );
    }

    @Test
    void publish_fails_whenTechnicalReadinessDraftStaged() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_PUBLISH")).thenReturn(true);

        KnowledgeVersion draftStagedVersion = KnowledgeVersion.builder()
                .id(VERSION_ID)
                .knowledgeDocumentId(DOC_ID)
                .versionLabel("1.0.0")
                .status(KnowledgeStatus.DRAFT)
                .technicalReadiness(TechnicalReadiness.DRAFT_STAGED)
                .contentHash("hash-123")
                .build();
        when(versionRepository.findByIdForUpdate(VERSION_ID)).thenReturn(Optional.of(draftStagedVersion));

        assertThatThrownBy(() -> service.publishVersion(ACTOR_ID, VERSION_ID))
                .isInstanceOf(ContentLifecycleConflictException.class)
                .hasMessageContaining("must be TECHNICAL_VERIFICATION_PASSED to publish")
                .hasMessageContaining("DRAFT_STAGED");

        verify(versionRepository, never()).updateStatusAndPublishInfo(any(), any(), any(), any(), any());
        verify(processingRunRepository, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void publish_fails_whenTechnicalReadinessHeldAsDraft() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_PUBLISH")).thenReturn(true);

        KnowledgeVersion heldVersion = KnowledgeVersion.builder()
                .id(VERSION_ID)
                .knowledgeDocumentId(DOC_ID)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.DRAFT)
                .technicalReadiness(TechnicalReadiness.HELD_AS_DRAFT)
                .holdReason("Awaiting clinical specialist review")
                .contentHash("hash-niddk")
                .build();
        when(versionRepository.findByIdForUpdate(VERSION_ID)).thenReturn(Optional.of(heldVersion));

        assertThatThrownBy(() -> service.publishVersion(ACTOR_ID, VERSION_ID))
                .isInstanceOf(ContentLifecycleConflictException.class)
                .hasMessageContaining("must be TECHNICAL_VERIFICATION_PASSED to publish")
                .hasMessageContaining("HELD_AS_DRAFT");

        verify(versionRepository, never()).updateStatusAndPublishInfo(any(), any(), any(), any(), any());
    }

    @Test
    void publish_fails_whenLatestReviewIsRejectedOrHashMismatched() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_PUBLISH")).thenReturn(true);

        KnowledgeVersion draftVersion = KnowledgeVersion.builder()
                .id(VERSION_ID)
                .knowledgeDocumentId(DOC_ID)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.DRAFT)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .contentHash("current-hash-abc")
                .build();
        when(versionRepository.findByIdForUpdate(VERSION_ID)).thenReturn(Optional.of(draftVersion));
        when(reviewRepository.hasEffectiveApprovedReview(VERSION_ID, "current-hash-abc")).thenReturn(false);

        assertThatThrownBy(() -> service.publishVersion(ACTOR_ID, VERSION_ID))
                .isInstanceOf(ContentLifecycleConflictException.class)
                .hasMessageContaining("requires an effective APPROVED review");

        verify(versionRepository, never()).updateStatusAndPublishInfo(any(), any(), any(), any(), any());
    }

    @Test
    void publish_success_withEffectiveApprovedReview_enqueuesDurableJobAndRecordsAudit() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_PUBLISH")).thenReturn(true);
        when(userRoleQuery.hasActiveRole(ACTOR_ID, "ADMIN")).thenReturn(true);

        KnowledgeVersion draftVersion = KnowledgeVersion.builder()
                .id(VERSION_ID)
                .knowledgeDocumentId(DOC_ID)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.DRAFT)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .contentHash("verified-hash-123")
                .build();
        when(versionRepository.findByIdForUpdate(VERSION_ID)).thenReturn(Optional.of(draftVersion));
        when(reviewRepository.hasEffectiveApprovedReview(VERSION_ID, "verified-hash-123")).thenReturn(true);
        when(versionRepository.updateStatusAndPublishInfo(VERSION_ID, KnowledgeStatus.ACTIVE, ACTOR_ID, NOW, NOW)).thenReturn(1);

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(DOC_ID)
                .documentCode("WHO-PA-SB-2020-VI-01")
                .sourceId("WHO-PA-SB-2020")
                .build();
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));

        service.publishVersion(ACTOR_ID, VERSION_ID);

        // Verify status update
        verify(versionRepository).updateStatusAndPublishInfo(VERSION_ID, KnowledgeStatus.ACTIVE, ACTOR_ID, NOW, NOW);

        // Verify durable outbox enqueue into PostgreSQL
        verify(processingRunRepository).enqueue(eq(VERSION_ID), eq("REINDEX"), eq("INDEX:" + VERSION_ID), any(), eq(NOW));

        // Verify audit with resolved role
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).recordAudit(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("KNOWLEDGE_VERSION_PUBLISHED");
        assertThat(captor.getValue().actorRole()).isEqualTo("ADMIN");
        assertThat(captor.getValue().metadataJson()).contains("WHO-PA-SB-2020-VI-01");

        // Verify notification
        verify(aiIndexingNotificationPort).notifyKnowledgePublished(VERSION_ID, "WHO-PA-SB-2020-VI-01", "1.1.0", "WHO-PA-SB-2020");
    }

    @Test
    void publish_concurrentModification_throwsContentLifecycleConflictException() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_PUBLISH")).thenReturn(true);

        KnowledgeVersion draftVersion = KnowledgeVersion.builder()
                .id(VERSION_ID)
                .knowledgeDocumentId(DOC_ID)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.DRAFT)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .contentHash("hash-123")
                .build();
        when(versionRepository.findByIdForUpdate(VERSION_ID)).thenReturn(Optional.of(draftVersion));
        when(reviewRepository.hasEffectiveApprovedReview(VERSION_ID, "hash-123")).thenReturn(true);
        // Returns 0 rows updated because another transaction already changed it
        when(versionRepository.updateStatusAndPublishInfo(VERSION_ID, KnowledgeStatus.ACTIVE, ACTOR_ID, NOW, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.publishVersion(ACTOR_ID, VERSION_ID))
                .isInstanceOf(ContentLifecycleConflictException.class)
                .hasMessageContaining("Concurrent modification");

        verify(processingRunRepository, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void archive_concurrentModification_throwsContentLifecycleConflictException() {
        when(accountStatusQuery.getAccountStatus(ACTOR_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(permissionQuery.hasActivePermission(ACTOR_ID, "KNOWLEDGE_ARCHIVE")).thenReturn(true);

        KnowledgeVersion activeVersion = KnowledgeVersion.builder()
                .id(VERSION_ID)
                .knowledgeDocumentId(DOC_ID)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.ACTIVE)
                .build();
        when(versionRepository.findByIdForUpdate(VERSION_ID)).thenReturn(Optional.of(activeVersion));
        // Returns 0 rows updated because another transaction already archived it
        when(versionRepository.updateStatusAndArchiveInfo(VERSION_ID, KnowledgeStatus.ARCHIVED, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.archiveVersion(ACTOR_ID, VERSION_ID, "Reason"))
                .isInstanceOf(ContentLifecycleConflictException.class)
                .hasMessageContaining("Concurrent modification");
    }
}
