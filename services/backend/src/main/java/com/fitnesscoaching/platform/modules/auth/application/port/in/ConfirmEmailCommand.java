package com.fitnesscoaching.platform.modules.auth.application.port.in;

public record ConfirmEmailCommand(String token) {
    @Override
    public String toString() {
        return "ConfirmEmailCommand[" +
                "token=" + (token == null ? "null" : "[REDACTED]") +
                ']';
    }
}
