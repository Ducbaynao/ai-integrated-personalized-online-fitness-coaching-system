package com.fitnesscoaching.platform.modules.auth.application.port.in;

public record LoginCommand(String email, String password, String deviceName) {
    @Override
    public String toString() {
        return "LoginCommand[" +
                "email=" + email +
                ", password=" + (password == null ? "null" : "[REDACTED]") +
                ", deviceName=" + deviceName +
                ']';
    }
}
