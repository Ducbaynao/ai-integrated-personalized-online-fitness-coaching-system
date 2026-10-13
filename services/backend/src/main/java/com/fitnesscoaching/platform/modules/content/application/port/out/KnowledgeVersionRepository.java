package com.fitnesscoaching.platform.modules.content.application.port.out;

import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeVersionRepository {

    Optional<KnowledgeVersion> findById(UUID id);

    Optional<KnowledgeVersion> findByIdForUpdate(UUID id);

    Optional<KnowledgeVersion> findByDocumentIdAndVersionLabel(UUID documentId, String versionLabel);

    Optional<KnowledgeVersion> findByDocumentCodeAndVersionLabel(String documentCode, String versionLabel);

    KnowledgeVersion save(KnowledgeVersion version);

    int updateStatusAndPublishInfo(UUID versionId, KnowledgeStatus status, UUID publishedBy, Instant publishedAt, Instant activatedAt);

    int updateStatusAndArchiveInfo(UUID versionId, KnowledgeStatus status, Instant archivedAt);

    void updateReviewInfo(UUID versionId, UUID reviewedBy, Instant reviewedAt);

    List<KnowledgeVersion> findActiveBySourceIds(List<String> sourceIds, int limit);

    List<KnowledgeVersion> findByDocumentId(UUID documentId);
}
