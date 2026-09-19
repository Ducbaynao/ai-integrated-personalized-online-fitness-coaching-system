package com.fitnesscoaching.platform.modules.auth.application.port.in;

public record RefreshSessionCommand(String refreshToken, String deviceName) {
}
