package com.fitnesscoaching.platform.modules.ai.domain;

/**
 * Database-level lifecycle status of an AI Run, mapping exactly to fitness.ai_run_status enum.
 */
public enum AiRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    REJECTED_BY_VALIDATOR,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == REJECTED_BY_VALIDATOR || this == CANCELLED;
    }
}
