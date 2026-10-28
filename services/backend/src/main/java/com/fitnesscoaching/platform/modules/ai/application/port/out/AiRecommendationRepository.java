package com.fitnesscoaching.platform.modules.ai.application.port.out;

import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationApplication;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AiRecommendationRepository {
    AiRecommendation save(AiRecommendation recommendation);

    Optional<AiRecommendation> findById(UUID id);

    Optional<AiRecommendation> findByIdForUpdate(UUID id);

    void updateDecision(
            UUID id,
            AiRecommendationStatus status,
            UUID decidedBy,
            Instant decidedAt,
            String decisionNote,
            String targetEntityType,
            UUID targetEntityId
    );

    AiRecommendationApplication saveApplication(AiRecommendationApplication application);

    Optional<AiRecommendationApplication> findApplicationByRecommendationId(UUID recommendationId);
}
