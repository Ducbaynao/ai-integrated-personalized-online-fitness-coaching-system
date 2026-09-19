package com.fitnesscoaching.platform.modules.auth.application.port.in;

public record RegisterUserCommand(
        String email,
        String password,
        String displayName,
        String preferredLocale,
        String timezone
) {
    @Override
    public String toString() {
        return "RegisterUserCommand[" +
                "email=" + email +
                ", password=" + (password == null ? "null" : "[REDACTED]") +
                ", displayName=" + displayName +
                ", preferredLocale=" + preferredLocale +
                ", timezone=" + timezone +
                ']';
    }
}
