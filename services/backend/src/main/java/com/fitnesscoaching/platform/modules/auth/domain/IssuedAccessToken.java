package com.fitnesscoaching.platform.modules.auth.domain;

import java.time.Instant;

public record IssuedAccessToken(String value, Instant expiresAt) {
    @Override
    public String toString() {
        return "IssuedAccessToken[" +
                "value=" + (value == null ? "null" : "[REDACTED]") +
                ", expiresAt=" + expiresAt +
                ']';
    }
}
