package com.fitnesscoaching.platform.modules.ai.domain;

/**
 * Training continuity states for AI proposal context as defined in the Phase 2 Feature Data and Authority Matrix.
 * Distinguishes confirmed beginners from prolonged inactivity (>= 100 days), unverified history, and consistent trainees.
 */
public enum TrainingContinuityState {
    CONFIRMED_NEW,
    RECENT_CONSISTENT,
    PROLONGED_INACTIVITY,
    UNKNOWN_HISTORY,
    RESTRICTED_HISTORY
}
