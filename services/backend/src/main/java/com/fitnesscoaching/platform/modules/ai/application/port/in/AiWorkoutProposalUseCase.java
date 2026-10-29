package com.fitnesscoaching.platform.modules.ai.application.port.in;

import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface AiWorkoutProposalUseCase {

    WorkoutProposalResult generateProposal(GenerateProposalCommand command);

    AiRecommendation getRecommendation(UUID actorId, UUID recommendationId);

    UpdateDraftResult updateDraft(UpdateDraftCommand command);

    AcceptRecommendationResult acceptRecommendation(AcceptRecommendationCommand command);

    RejectRecommendationResult rejectRecommendation(RejectRecommendationCommand command);

    record GenerateProposalCommand(
            UUID actorId,
            UUID studentId,
            String commandKey,
            Map<String, Object> preferences
    ) {}

    record WorkoutProposalResult(
            UUID recommendationId,
            UUID runId,
            AiRecommendationStatus status,
            AiDecisionAuthority decisionAuthority,
            String title,
            String explanation,
            Instant expiresAt,
            Instant createdAt,
            String structuredPayload
    ) {}

    record UpdateDraftCommand(
            UUID actorId,
            UUID recommendationId,
            int expectedRevision,
            String title,
            String explanation,
            String structuredPayload
    ) {}

    record UpdateDraftResult(
            UUID recommendationId,
            int revision,
            boolean isCustomized,
            AiRecommendationStatus status,
            String title,
            String explanation,
            String structuredPayload,
            String originalPayload,
            Instant updatedAt
    ) {}

    record AcceptRecommendationCommand(
            UUID actorId,
            UUID recommendationId,
            String commandKey,
            String decisionNote,
            Integer expectedRevision
    ) {
        public AcceptRecommendationCommand(UUID actorId, UUID recommendationId, String commandKey, String decisionNote) {
            this(actorId, recommendationId, commandKey, decisionNote, null);
        }
    }

    record AcceptRecommendationResult(
            UUID recommendationId,
            AiRecommendationStatus status,
            UUID appliedBy,
            String resultingEntityType,
            UUID resultingEntityId,
            Instant appliedAt,
            boolean replayed
    ) {}

    record RejectRecommendationCommand(
            UUID actorId,
            UUID recommendationId,
            String reason
    ) {}

    record RejectRecommendationResult(
            UUID recommendationId,
            AiRecommendationStatus status,
            UUID decidedBy,
            Instant decidedAt,
            String decisionNote
    ) {}
}
