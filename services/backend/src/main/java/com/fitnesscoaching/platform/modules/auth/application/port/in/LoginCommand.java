package com.fitnesscoaching.platform.modules.auth.application.port.in;

public record LoginCommand(String email, String password, String deviceName) {
}
