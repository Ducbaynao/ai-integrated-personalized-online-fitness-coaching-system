package com.fitnesscoaching.platform.modules.auth.application.port.in;

import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;

import java.time.Instant;

public record TokenPairResult(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        CurrentUserView user
) {
}
