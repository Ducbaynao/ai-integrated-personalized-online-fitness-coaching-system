package com.fitnesscoaching.platform.modules.administration.application.model;

import java.time.Instant;
import java.util.UUID;

public record StepUpResult(
        UUID stepUpAuthenticationId,
        Instant expiresAt
) {
}
