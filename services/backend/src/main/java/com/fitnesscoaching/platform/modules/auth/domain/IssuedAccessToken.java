package com.fitnesscoaching.platform.modules.auth.domain;

import java.time.Instant;

public record IssuedAccessToken(String value, Instant expiresAt) {
}
