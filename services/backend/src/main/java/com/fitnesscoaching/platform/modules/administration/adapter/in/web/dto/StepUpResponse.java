package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

import java.time.Instant;
import java.util.UUID;

public record StepUpResponse(
        UUID stepUpAuthenticationId,
        Instant expiresAt
) {
}
