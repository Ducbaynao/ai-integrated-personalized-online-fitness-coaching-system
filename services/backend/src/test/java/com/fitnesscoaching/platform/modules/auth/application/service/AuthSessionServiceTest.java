package com.fitnesscoaching.platform.modules.auth.application.service;

import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.InvalidCredentialsException;
import com.fitnesscoaching.platform.common.exception.InvalidRefreshTokenException;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.audit.SecurityEventRecord;
import com.fitnesscoaching.platform.modules.auth.application.port.in.LoginCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RefreshSessionCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.TokenPairResult;
import com.fitnesscoaching.platform.modules.auth.application.port.out.AccessTokenIssuer;
import com.fitnesscoaching.platform.modules.auth.application.port.out.RefreshTokenPort;
import com.fitnesscoaching.platform.modules.auth.application.port.out.UserAccountPort;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.auth.domain.IssuedAccessToken;
import com.fitnesscoaching.platform.modules.auth.domain.RefreshTokenRecord;
import com.fitnesscoaching.platform.modules.auth.domain.TokenHasher;
import com.fitnesscoaching.platform.modules.auth.domain.UserAccountRecord;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.user.application.model.UserCapabilitiesView;
import com.fitnesscoaching.platform.modules.user.application.model.UserSettingsView;
import com.fitnesscoaching.platform.modules.user.application.port.in.CurrentUserQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final Duration REFRESH_TTL = Duration.ofDays(30);

    @Mock private UserAccountPort userAccountPort;
    @Mock private CurrentUserQuery currentUserQuery;
    @Mock private RefreshTokenPort refreshTokenPort;
    @Mock private AccessTokenIssuer accessTokenIssuer;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;

    private AuthSessionService service;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("fitness-coaching-backend");
        jwtProperties.setSecret("test-secret-at-least-32-bytes-long-1234567890");
        jwtProperties.setAccessTokenTtl(Duration.ofMinutes(15));
        jwtProperties.setRefreshTokenTtl(REFRESH_TTL);

        service = new AuthSessionService(
                userAccountPort,
                currentUserQuery,
                refreshTokenPort,
                accessTokenIssuer,
                passwordEncoder,
                auditService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                jwtProperties
        );
    }

    @Test
    @DisplayName("Login issues tokens, stores only refresh hash, updates last login, and uses CurrentUserView")
    void loginIssuesTokensStoresOnlyRefreshHashAndUpdatesLastLogin() {
        UserAccountRecord user = activeUser();
        CurrentUserView projection = userProjection(user.id(), List.of("STUDENT"));
        when(userAccountPort.findByEmailIgnoreCase("athlete@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("StrongPassword123!", user.passwordHash())).thenReturn(true);
        when(currentUserQuery.findCurrentUserById(user.id())).thenReturn(Optional.of(projection));
        when(accessTokenIssuer.issue(projection, NOW))
                .thenReturn(new IssuedAccessToken("signed-access-token", NOW.plusSeconds(900)));
        when(refreshTokenPort.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TokenPairResult result = service.login(
                new LoginCommand("  Athlete@Example.com ", "StrongPassword123!", " Pixel 9 "));

        assertThat(result.accessToken()).isEqualTo("signed-access-token");
        assertThat(result.refreshToken()).hasSizeGreaterThan(20);
        assertThat(result.refreshTokenExpiresAt()).isEqualTo(NOW.plus(REFRESH_TTL));
        assertThat(result.user().roles()).containsExactly("STUDENT");

        ArgumentCaptor<RefreshTokenRecord> storedToken = ArgumentCaptor.forClass(RefreshTokenRecord.class);
        verify(refreshTokenPort).saveAndFlush(storedToken.capture());
        assertThat(storedToken.getValue().tokenHash())
                .isEqualTo(TokenHasher.sha256Hex(result.refreshToken()))
                .doesNotContain(result.refreshToken());
        assertThat(storedToken.getValue().deviceName()).isEqualTo("Pixel 9");
        verify(userAccountPort).updateLastLogin(user.id(), NOW);
    }

    @Test
    @DisplayName("Unknown email and wrong password share the same failure and emit LOGIN_FAILURE without exposing secret")
    void unknownEmailAndWrongPasswordShareTheSameFailure() {
        when(userAccountPort.findByEmailIgnoreCase("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(
                new LoginCommand("unknown@example.com", "WrongPassword123!", null)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(accessTokenIssuer, never()).issue((CurrentUserView) any(), any());
        verify(refreshTokenPort, never()).saveAndFlush(any());

        ArgumentCaptor<SecurityEventRecord> captor = ArgumentCaptor.forClass(SecurityEventRecord.class);
        verify(auditService).recordSecurityEvent(captor.capture());
        SecurityEventRecord event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("LOGIN_FAILURE");
        assertThat(event.severity()).isEqualTo("MEDIUM");
        assertThat(event.detailsJson()).doesNotContain("WrongPassword123!");
    }

    @Test
    @DisplayName("Inactive account cannot login even with correct password")
    void inactiveAccountCannotLoginEvenWithCorrectPassword() {
        UserAccountRecord pending = userWithStatus(AccountStatus.PENDING_VERIFICATION);
        when(userAccountPort.findByEmailIgnoreCase(pending.email())).thenReturn(Optional.of(pending));
        when(passwordEncoder.matches("StrongPassword123!", pending.passwordHash())).thenReturn(true);

        assertThatThrownBy(() -> service.login(
                new LoginCommand(pending.email(), "StrongPassword123!", null)))
                .isInstanceOf(AccountUnavailableException.class);

        verify(refreshTokenPort, never()).saveAndFlush(any());

        ArgumentCaptor<SecurityEventRecord> captor = ArgumentCaptor.forClass(SecurityEventRecord.class);
        verify(auditService).recordSecurityEvent(captor.capture());
        SecurityEventRecord event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("LOGIN_ACCOUNT_UNAVAILABLE");
    }

    @Test
    @DisplayName("Refresh atomically revokes old token and links replacement")
    void refreshAtomicallyRevokesOldTokenAndLinksReplacement() {
        UserAccountRecord user = activeUser();
        CurrentUserView projection = userProjection(user.id(), List.of("STUDENT"));
        String rawToken = "existing-refresh-token-value-with-enough-entropy";
        UUID existingId = UUID.randomUUID();
        RefreshTokenRecord existing = new RefreshTokenRecord(
                existingId, user.id(), TokenHasher.sha256Hex(rawToken), "Pixel 9",
                NOW.minusSeconds(60), NOW.plusSeconds(3600), null, null, null);
        when(refreshTokenPort.findByTokenHash(existing.tokenHash())).thenReturn(Optional.of(existing));
        when(userAccountPort.findById(user.id())).thenReturn(Optional.of(user));
        when(refreshTokenPort.revokeForRotation(existingId, NOW)).thenReturn(1);
        when(currentUserQuery.findCurrentUserById(user.id())).thenReturn(Optional.of(projection));
        when(accessTokenIssuer.issue(projection, NOW))
                .thenReturn(new IssuedAccessToken("new-access-token", NOW.plusSeconds(900)));
        when(refreshTokenPort.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TokenPairResult result = service.refresh(new RefreshSessionCommand(rawToken, null));

        verify(refreshTokenPort).revokeForRotation(existingId, NOW);
        ArgumentCaptor<RefreshTokenRecord> replacement = ArgumentCaptor.forClass(RefreshTokenRecord.class);
        verify(refreshTokenPort).saveAndFlush(replacement.capture());
        assertThat(replacement.getValue().rotatedFromId()).isEqualTo(existingId);
        assertThat(replacement.getValue().deviceName()).isEqualTo("Pixel 9");
        assertThat(result.user().roles()).containsExactly("STUDENT");
    }

    @Test
    @DisplayName("Reuse of revoked refresh token revokes all active sessions and emits REFRESH_TOKEN_REUSE")
    void reuseOfRevokedRefreshTokenRevokesAllActiveSessions() {
        UserAccountRecord user = activeUser();
        String rawToken = "already-rotated-refresh-token-value";
        RefreshTokenRecord reused = new RefreshTokenRecord(
                UUID.randomUUID(), user.id(), TokenHasher.sha256Hex(rawToken), null,
                NOW.minusSeconds(120), NOW.plusSeconds(3600), null, NOW.minusSeconds(30), "ROTATED");
        when(refreshTokenPort.findByTokenHash(reused.tokenHash())).thenReturn(Optional.of(reused));

        assertThatThrownBy(() -> service.refresh(new RefreshSessionCommand(rawToken, null)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenPort).revokeActiveForUser(user.id(), NOW, "REUSE_DETECTED");
        verify(accessTokenIssuer, never()).issue((CurrentUserView) any(), any());

        ArgumentCaptor<SecurityEventRecord> captor = ArgumentCaptor.forClass(SecurityEventRecord.class);
        verify(auditService).recordSecurityEvent(captor.capture());
        SecurityEventRecord event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("REFRESH_TOKEN_REUSE");
        assertThat(event.severity()).isEqualTo("CRITICAL");
        assertThat(event.userId()).isEqualTo(user.id());
    }

    @Test
    @DisplayName("Losing a concurrent rotation revokes the token family and emits REFRESH_TOKEN_REUSE")
    void losingAConcurrentRotationRevokesTheTokenFamily() {
        UserAccountRecord user = activeUser();
        String rawToken = "concurrently-used-refresh-token-value";
        RefreshTokenRecord existing = new RefreshTokenRecord(
                UUID.randomUUID(), user.id(), TokenHasher.sha256Hex(rawToken), null,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), null, null, null);
        when(refreshTokenPort.findByTokenHash(existing.tokenHash())).thenReturn(Optional.of(existing));
        when(userAccountPort.findById(user.id())).thenReturn(Optional.of(user));
        when(refreshTokenPort.revokeForRotation(existing.id(), NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.refresh(new RefreshSessionCommand(rawToken, null)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenPort).revokeActiveForUser(user.id(), NOW, "REUSE_DETECTED");
        verify(refreshTokenPort, never()).saveAndFlush(any());

        ArgumentCaptor<SecurityEventRecord> captor = ArgumentCaptor.forClass(SecurityEventRecord.class);
        verify(auditService).recordSecurityEvent(captor.capture());
        SecurityEventRecord event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("REFRESH_TOKEN_REUSE");
        assertThat(event.severity()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("JwtProperties fail-fast startup validation tests")
    void jwtPropertiesValidationFailsFast() {
        JwtProperties props = new JwtProperties();

        // 1. Blank issuer
        props.setIssuer("   ");
        props.setSecret("secret-key-at-least-32-bytes-long-12345");
        props.setAccessTokenTtl(Duration.ofMinutes(15));
        props.setRefreshTokenTtl(Duration.ofDays(30));
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.jwt.issuer must not be blank");

        // 2. Secret < 32 bytes
        props.setIssuer("fitness-coaching-backend");
        props.setSecret("too-short");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.jwt.secret must contain at least 32 UTF-8 bytes");

        // 3. Non-positive access-token-ttl
        props.setSecret("secret-key-at-least-32-bytes-long-12345");
        props.setAccessTokenTtl(Duration.ZERO);
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.jwt.access-token-ttl must be positive");

        // 4. Non-positive refresh-token-ttl
        props.setAccessTokenTtl(Duration.ofMinutes(15));
        props.setRefreshTokenTtl(Duration.ofSeconds(-1));
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.jwt.refresh-token-ttl must be positive");

        // 5. Valid config passes
        props.setRefreshTokenTtl(Duration.ofDays(30));
        props.validate();
    }

    private UserAccountRecord activeUser() {
        return userWithStatus(AccountStatus.ACTIVE);
    }

    private UserAccountRecord userWithStatus(AccountStatus status) {
        UUID id = UUID.randomUUID();
        return new UserAccountRecord(
                id,
                "athlete@example.com",
                "$2a$10$stored-password-hash",
                "Athlete",
                status,
                "vi-VN",
                "Asia/Ho_Chi_Minh",
                status == AccountStatus.ACTIVE ? NOW.minusSeconds(3600) : null,
                NOW.minusSeconds(7200),
                NOW.minusSeconds(3600)
        );
    }

    private CurrentUserView userProjection(UUID userId, List<String> roles) {
        return new CurrentUserView(
                userId,
                "athlete@example.com",
                "Athlete",
                AccountStatus.ACTIVE,
                "vi-VN",
                "Asia/Ho_Chi_Minh",
                NOW.minusSeconds(3600),
                NOW.minusSeconds(7200),
                null,
                roles,
                new UserCapabilitiesView(true, false, false),
                UserSettingsView.defaults()
        );
    }
}
