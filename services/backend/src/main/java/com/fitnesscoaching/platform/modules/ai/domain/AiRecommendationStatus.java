package com.fitnesscoaching.platform.modules.ai.domain;

public enum AiRecommendationStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    DISMISSED,
    APPLIED,
    SUPERSEDED,
    EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
