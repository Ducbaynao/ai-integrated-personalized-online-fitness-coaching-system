package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseCatalogDetail;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Server-owned builder for F01 Internal Context Envelope.
 * Guarantees that envelope identity, purpose, whitelist actions, and knowledge scope
 * are constructed strictly from authoritative server-side domain state, never client input.
 * Excludes personal coaching context and proposal mutations.
 */
@Component
public class F01InternalEnvelopeBuilder {

    // Server-approved canonical knowledge sources for F01 general fitness knowledge
    public static final List<String> CANONICAL_APPROVED_SOURCES =
            com.fitnesscoaching.platform.modules.content.domain.KnowledgeSourceIdentity.CANONICAL_APPROVED_SOURCES_LIST;

    public F01InternalContextEnvelope build(
            UUID runId,
            UUID actorId,
            String actorRole,
            String question,
            String language,
            UUID correlationId,
            ExerciseCatalogDetail exerciseDetail
    ) {
        if (runId == null) {
            throw new AiRunValidationException("runId is required to construct envelope");
        }
        if (actorId == null) {
            throw new AiRunValidationException("actorId is required to construct envelope");
        }
        if (!"STUDENT".equals(actorRole) && !"TRAINER".equals(actorRole)) {
            throw new AiRunValidationException("actorRole must be either STUDENT or TRAINER");
        }
        if (question == null || question.isBlank()) {
            throw new AiRunValidationException("question is required to construct envelope");
        }

        String trimmedQuestion = question.trim();
        String selectedLanguage = (language != null && !language.isBlank()) ? language.trim().toLowerCase() : "vi";
        UUID effectiveCorrelationId = correlationId != null ? correlationId : UUID.randomUUID();
        String asOf = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

        F01InternalContextEnvelope.ActorContext actorContext =
                new F01InternalContextEnvelope.ActorContext(actorId, actorRole);

        F01InternalContextEnvelope.ExerciseContext exerciseContext = null;
        if (exerciseDetail != null) {
            exerciseContext = new F01InternalContextEnvelope.ExerciseContext(
                    exerciseDetail.id(),
                    exerciseDetail.name(),
                    exerciseDetail.movementPattern(),
                    null
            );
        }

        F01InternalContextEnvelope.KnowledgeScopeContext knowledgeScope =
                new F01InternalContextEnvelope.KnowledgeScopeContext("ACTIVE", CANONICAL_APPROVED_SOURCES);

        return new F01InternalContextEnvelope(
                runId,
                F01InternalContextEnvelope.F01_REQUEST_TYPE,
                F01InternalContextEnvelope.SCHEMA_VERSION,
                effectiveCorrelationId,
                actorContext,
                F01InternalContextEnvelope.GENERAL_PURPOSE,
                F01InternalContextEnvelope.ALLOWED_ACTIONS,
                asOf,
                trimmedQuestion,
                selectedLanguage,
                exerciseContext,
                knowledgeScope
        );
    }
}
