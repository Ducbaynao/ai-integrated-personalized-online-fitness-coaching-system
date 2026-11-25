package com.fitnesscoaching.platform.modules.ai.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Minimal authorized context envelope constructed by Spring Boot and dispatched
 * to the internal FastAPI AI service for F01 knowledge inquiries (general and personalized).
 * Conforms to contracts/ai-schemas/f01-internal-context-envelope.schema.json.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record F01InternalContextEnvelope(
        UUID requestId,
        String requestType,
        String schemaVersion,
        UUID correlationId,
        ActorContext actor,
        String purpose,
        List<String> allowedActions,
        String asOf,
        String question,
        String language,
        ExerciseContext exerciseContext,
        KnowledgeScopeContext knowledgeScope,
        PersonalContext personalContext
) {
    public static final String F01_REQUEST_TYPE = "F01_GENERAL_KNOWLEDGE";
    public static final String SCHEMA_VERSION = "1.0.0";
    public static final String GENERAL_PURPOSE = "GENERAL_FITNESS_EXPLANATION";
    public static final List<String> ALLOWED_ACTIONS = List.of("READ_INFORMATION");

    public F01InternalContextEnvelope(
            UUID requestId,
            String requestType,
            String schemaVersion,
            UUID correlationId,
            ActorContext actor,
            String purpose,
            List<String> allowedActions,
            String asOf,
            String question,
            String language,
            ExerciseContext exerciseContext,
            KnowledgeScopeContext knowledgeScope
    ) {
        this(requestId, requestType, schemaVersion, correlationId, actor, purpose, allowedActions, asOf, question, language, exerciseContext, knowledgeScope, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActorContext(
            UUID userId,
            String role
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExerciseContext(
            UUID exerciseId,
            String exerciseName,
            String movementPattern,
            String equipmentRequired
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KnowledgeScopeContext(
            String status,
            List<String> allowedSourceIds
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PersonalContext(
            UUID studentId,
            String coachingMode,
            String trainingExperience,
            ActiveGoalContext activeGoal,
            List<String> injuriesOrLimitations,
            List<String> availableEquipment,
            String currentActivePlanName
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActiveGoalContext(
            String goalType,
            String targetSummary,
            Integer version
    ) {
    }
}
