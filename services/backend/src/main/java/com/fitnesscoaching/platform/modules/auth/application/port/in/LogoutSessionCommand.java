package com.fitnesscoaching.platform.modules.auth.application.port.in;

import java.util.Objects;
import java.util.UUID;

public record LogoutSessionCommand(
        UUID userId,
        String refreshToken
) {
    public LogoutSessionCommand {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
        refreshToken = refreshToken.trim();
    }

    @Override
    public String toString() {
        return "LogoutSessionCommand[" +
                "userId=" + userId +
                ", refreshToken=" + (refreshToken == null ? "null" : "[REDACTED]") +
                ']';
    }
}
