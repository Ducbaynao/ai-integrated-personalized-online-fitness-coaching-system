package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

import java.time.Instant;

public record TokenPairResponse(
        String tokenType,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        SessionUserDto user
) {
    @Override
    public String toString() {
        return "TokenPairResponse[" +
                "tokenType=" + tokenType +
                ", accessToken=" + (accessToken == null ? "null" : "[REDACTED]") +
                ", accessTokenExpiresAt=" + accessTokenExpiresAt +
                ", refreshToken=" + (refreshToken == null ? "null" : "[REDACTED]") +
                ", refreshTokenExpiresAt=" + refreshTokenExpiresAt +
                ", user=" + user +
                ']';
    }
}
