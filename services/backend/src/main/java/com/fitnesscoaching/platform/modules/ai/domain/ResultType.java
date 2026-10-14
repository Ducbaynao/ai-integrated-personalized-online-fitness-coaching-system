package com.fitnesscoaching.platform.modules.ai.domain;

/**
 * Contract-level result classification for F01 and Phase 2 AI responses.
 * Distinct from PROPOSAL and ESTIMATE.
 */
public enum ResultType {
    INFORMATION,
    INSUFFICIENT_DATA,
    BLOCKED
}
