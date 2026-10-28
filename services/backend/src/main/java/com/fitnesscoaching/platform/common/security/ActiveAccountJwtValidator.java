package com.fitnesscoaching.platform.common.security;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** Validates current Account-owned state on every bearer-token decode, without a status cache. */
public final class ActiveAccountJwtValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2TokenValidatorResult UNAVAILABLE = OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Account is not active", null));

    private final UserAccountStatusQuery accounts;

    public ActiveAccountJwtValidator(UserAccountStatusQuery accounts) {
        this.accounts = accounts;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        UUID subject;
        try {
            subject = UUID.fromString(token.getSubject());
        } catch (IllegalArgumentException | NullPointerException invalidSubject) {
            return UNAVAILABLE;
        }
        return accounts.getAccountStatus(subject).orElse(null) == AccountStatus.ACTIVE
                ? OAuth2TokenValidatorResult.success() : UNAVAILABLE;
    }
}
