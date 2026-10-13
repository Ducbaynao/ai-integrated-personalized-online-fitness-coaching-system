package com.fitnesscoaching.platform.modules.content.application.port.in;

import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeReviewRecord;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminKnowledgeQueryUseCase {

    List<KnowledgeDocument> listDocuments(int offset, int limit);

    Optional<KnowledgeDocument> getDocument(UUID documentId);

    List<KnowledgeVersion> listVersions(UUID documentId);

    Optional<KnowledgeVersion> getVersion(UUID versionId);

    List<KnowledgeReviewRecord> listReviews(UUID versionId);
}
