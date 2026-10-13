package com.fitnesscoaching.platform.modules.content.domain;

/**
 * Publication lifecycle status for knowledge versions.
 * In accordance with PostgreSQL knowledge_status enum and Phase 2 invariants.
 */
public enum KnowledgeStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
