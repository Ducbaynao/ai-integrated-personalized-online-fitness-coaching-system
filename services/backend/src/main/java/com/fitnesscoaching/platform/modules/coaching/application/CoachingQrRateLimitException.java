package com.fitnesscoaching.platform.modules.coaching.application;

import java.time.Instant;

public class CoachingQrRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public CoachingQrRateLimitException(long retryAfterSeconds) {
        super("Rate limit exceeded");
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
