package com.fitnesscoaching.platform.modules.auth.application.port.in;

public record RefreshSessionCommand(String refreshToken, String deviceName) {
    @Override
    public String toString() {
        return "RefreshSessionCommand[" +
                "refreshToken=" + (refreshToken == null ? "null" : "[REDACTED]") +
                ", deviceName=" + deviceName +
                ']';
    }
}
