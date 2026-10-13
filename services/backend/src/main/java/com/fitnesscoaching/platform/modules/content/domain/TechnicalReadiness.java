package com.fitnesscoaching.platform.modules.content.domain;

/**
 * Technical readiness and hold state of a knowledge version.
 * Distinct from the publication lifecycle (KnowledgeStatus) and specialist review (ReviewDecision).
 */
public enum TechnicalReadiness {
    DRAFT_STAGED,
    TECHNICAL_VERIFICATION_PASSED,
    HELD_AS_DRAFT
}
