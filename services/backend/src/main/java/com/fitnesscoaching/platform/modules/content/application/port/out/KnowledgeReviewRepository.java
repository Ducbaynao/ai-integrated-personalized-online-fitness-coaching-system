package com.fitnesscoaching.platform.modules.content.application.port.out;

import com.fitnesscoaching.platform.modules.content.domain.KnowledgeReviewRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeReviewRepository {

    KnowledgeReviewRecord save(KnowledgeReviewRecord record);

    List<KnowledgeReviewRecord> findByVersionId(UUID versionId);

    Optional<KnowledgeReviewRecord> findLatestReview(UUID versionId);

    boolean hasEffectiveApprovedReview(UUID versionId, String expectedContentHash);

    boolean hasApprovedReview(UUID versionId);
}
