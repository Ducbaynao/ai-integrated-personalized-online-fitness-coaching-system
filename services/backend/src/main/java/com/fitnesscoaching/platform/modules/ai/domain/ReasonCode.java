package com.fitnesscoaching.platform.modules.ai.domain;

/**
 * Machine-readable reason or error code distinguishing domain outcome from system state.
 */
public enum ReasonCode {
    NONE,
    MISSING_REQUIRED_CONTEXT,
    NO_RELEVANT_EVIDENCE,
    RULE_BLOCKED,
    VALIDATION_FAILED,
    MODEL_ERROR,
    PROVIDER_ERROR,
    TIMEOUT,
    PROCESSING_ERROR
}
