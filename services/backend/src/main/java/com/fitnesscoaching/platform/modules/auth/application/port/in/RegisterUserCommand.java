package com.fitnesscoaching.platform.modules.auth.application.port.in;

public record RegisterUserCommand(
        String email,
        String password,
        String displayName,
        String preferredLocale,
        String timezone
) {
}
