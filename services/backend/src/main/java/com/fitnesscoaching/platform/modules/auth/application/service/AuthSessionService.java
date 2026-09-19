package com.fitnesscoaching.platform.modules.auth.application.service;

import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.InvalidCredentialsException;
import com.fitnesscoaching.platform.common.exception.InvalidRefreshTokenException;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.audit.SecurityEventRecord;
import com.fitnesscoaching.platform.modules.auth.application.port.in.LoginCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.LoginUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RefreshSessionCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RefreshSessionUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.TokenPairResult;
import com.fitnesscoaching.platform.modules.auth.application.port.out.AccessTokenIssuer;
import com.fitnesscoaching.platform.modules.auth.application.port.out.RefreshTokenPort;
import com.fitnesscoaching.platform.modules.auth.application.port.out.UserAccountPort;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.user.application.port.in.CurrentUserQuery;
import com.fitnesscoaching.platform.modules.auth.domain.IssuedAccessToken;
import com.fitnesscoaching.platform.modules.auth.domain.RefreshTokenRecord;
import com.fitnesscoaching.platform.modules.auth.domain.TokenGenerator;
import com.fitnesscoaching.platform.modules.auth.domain.TokenHasher;
import com.fitnesscoaching.platform.modules.auth.domain.UserAccountRecord;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Service
public class AuthSessionService implements LoginUseCase, RefreshSessionUseCase {

    private static final String REUSE_DETECTED = "REUSE_DETECTED";

    private final UserAccountPort userAccountPort;
    private final CurrentUserQuery currentUserQuery;
    private final RefreshTokenPort refreshTokenPort;
    private final AccessTokenIssuer accessTokenIssuer;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final Clock clock;
    private final Duration refreshTokenTtl;
    private final String dummyPasswordHash;

    public AuthSessionService(
            UserAccountPort userAccountPort,
            CurrentUserQuery currentUserQuery,
            RefreshTokenPort refreshTokenPort,
            AccessTokenIssuer accessTokenIssuer,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            Clock clock,
            JwtProperties jwtProperties
    ) {
        this.userAccountPort = userAccountPort;
        this.currentUserQuery = currentUserQuery;
        this.refreshTokenPort = refreshTokenPort;
        this.accessTokenIssuer = accessTokenIssuer;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.clock = clock;
        this.refreshTokenTtl = jwtProperties.getRefreshTokenTtl();
        this.dummyPasswordHash = passwordEncoder.encode(TokenGenerator.generateSecureToken());
    }

    @Override
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountUnavailableException.class})
    public TokenPairResult login(LoginCommand command) {
        Instant now = clock.instant();
        String normalizedEmail = command.email().trim().toLowerCase(Locale.ROOT);
        UserAccountRecord user = userAccountPort.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        String passwordHash = user != null && user.passwordHash() != null
                ? user.passwordHash()
                : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(command.password(), passwordHash);

        if (user == null || user.passwordHash() == null || !passwordMatches) {
            recordLoginFailure(user == null ? null : user.id(), now);
            throw new InvalidCredentialsException();
        }
        if (user.status() != AccountStatus.ACTIVE) {
            recordSecurityEvent(user.id(), "LOGIN_ACCOUNT_UNAVAILABLE", "MEDIUM", now);
            throw new AccountUnavailableException();
        }

        CurrentUserView projection = currentUserQuery.findCurrentUserById(user.id())
                .orElseThrow(() -> new IllegalStateException("User projection not found for active user: " + user.id()));

        TokenPairResult result = issueTokenPair(projection, normalizeDeviceName(command.deviceName()), null, now);
        userAccountPort.updateLastLogin(user.id(), now);
        recordSecurityEvent(user.id(), "LOGIN_SUCCESS", "INFO", now);
        recordAudit(user.id(), "SESSION_CREATED", "USER", now);
        return result;
    }

    @Override
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenPairResult refresh(RefreshSessionCommand command) {
        Instant now = clock.instant();
        String tokenHash = TokenHasher.sha256Hex(command.refreshToken().trim());
        RefreshTokenRecord existing = refreshTokenPort.findByTokenHash(tokenHash).orElse(null);

        if (existing == null) {
            recordSecurityEvent(null, "REFRESH_TOKEN_INVALID", "MEDIUM", now);
            throw new InvalidRefreshTokenException();
        }
        /*
         * REPLAY DETECTION POLICY (Intentional Security Boundary):
         * When an already-revoked refresh token is presented, or when an atomic rotation race condition occurs,
         * the entire session family for the user across all devices is immediately revoked (reason: REUSE_DETECTED).
         * This intentional fail-safe boundary prevents adversary persistence if an issued token was leaked or stolen.
         */
        if (existing.revokedAt() != null) {
            refreshTokenPort.revokeActiveForUser(existing.userId(), now, REUSE_DETECTED);
            recordSecurityEvent(existing.userId(), "REFRESH_TOKEN_REUSE", "CRITICAL", now);
            throw new InvalidRefreshTokenException();
        }
        if (!existing.expiresAt().isAfter(now)) {
            recordSecurityEvent(existing.userId(), "REFRESH_TOKEN_EXPIRED", "INFO", now);
            throw new InvalidRefreshTokenException();
        }

        UserAccountRecord user = userAccountPort.findById(existing.userId()).orElse(null);
        if (user == null || user.status() != AccountStatus.ACTIVE) {
            refreshTokenPort.revokeActiveForUser(existing.userId(), now, "ACCOUNT_UNAVAILABLE");
            recordSecurityEvent(existing.userId(), "REFRESH_ACCOUNT_UNAVAILABLE", "MEDIUM", now);
            throw new InvalidRefreshTokenException();
        }

        int revoked = refreshTokenPort.revokeForRotation(existing.id(), now);
        if (revoked == 0) {
            // Concurrent race condition or already rotated: treat as replay attack and revoke all active tokens.
            refreshTokenPort.revokeActiveForUser(existing.userId(), now, REUSE_DETECTED);
            recordSecurityEvent(existing.userId(), "REFRESH_TOKEN_REUSE", "CRITICAL", now);
            throw new InvalidRefreshTokenException();
        }

        CurrentUserView projection = currentUserQuery.findCurrentUserById(existing.userId())
                .orElseThrow(() -> new IllegalStateException("User projection not found for active user: " + existing.userId()));

        String deviceName = StringUtils.hasText(command.deviceName())
                ? normalizeDeviceName(command.deviceName())
                : existing.deviceName();
        TokenPairResult result = issueTokenPair(projection, deviceName, existing.id(), now);
        recordSecurityEvent(user.id(), "REFRESH_TOKEN_ROTATED", "INFO", now);
        recordAudit(user.id(), "SESSION_REFRESHED", "USER", now);
        return result;
    }

    private TokenPairResult issueTokenPair(
            CurrentUserView user,
            String deviceName,
            java.util.UUID rotatedFromId,
            Instant now
    ) {
        IssuedAccessToken accessToken = accessTokenIssuer.issue(user, now);
        String rawRefreshToken = TokenGenerator.generateSecureToken();
        Instant refreshExpiresAt = now.plus(refreshTokenTtl);
        refreshTokenPort.saveAndFlush(RefreshTokenRecord.issue(
                user.id(), TokenHasher.sha256Hex(rawRefreshToken), deviceName,
                now, refreshExpiresAt, rotatedFromId));
        return new TokenPairResult(
                accessToken.value(), accessToken.expiresAt(), rawRefreshToken,
                refreshExpiresAt, user);
    }

    private String normalizeDeviceName(String deviceName) {
        return StringUtils.hasText(deviceName) ? deviceName.trim() : null;
    }

    private void recordLoginFailure(java.util.UUID userId, Instant now) {
        recordSecurityEvent(userId, "LOGIN_FAILURE", "MEDIUM", now);
    }

    private void recordSecurityEvent(java.util.UUID userId, String type, String severity, Instant now) {
        auditService.recordSecurityEvent(SecurityEventRecord.builder()
                .userId(userId)
                .eventType(type)
                .severity(severity)
                .detailsJson("{}")
                .occurredAt(now)
                .build());
    }

    private void recordAudit(java.util.UUID userId, String action, String targetType, Instant now) {
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(userId)
                .actorRole("USER")
                .action(action)
                .targetType(targetType)
                .targetId(userId)
                .requestId(RequestIdHolder.getAsUuid())
                .metadataJson("{}")
                .occurredAt(now)
                .build());
    }
}
