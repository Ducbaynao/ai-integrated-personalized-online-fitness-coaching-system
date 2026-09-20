package com.fitnesscoaching.platform.modules.auth.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.EmailAlreadyRegisteredException;
import com.fitnesscoaching.platform.common.exception.InvalidOrExpiredTokenException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.audit.SecurityEventRecord;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserResult;
import com.fitnesscoaching.platform.modules.auth.application.port.out.OneTimeTokenPort;
import com.fitnesscoaching.platform.modules.auth.application.port.out.UserAccountPort;
import com.fitnesscoaching.platform.modules.auth.application.port.out.VerificationEmailPort;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.auth.domain.OneTimeTokenRecord;
import com.fitnesscoaching.platform.modules.auth.domain.TokenHasher;
import com.fitnesscoaching.platform.modules.auth.domain.UserAccountRecord;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthApplicationServiceUnitTest {

    @Mock
    private UserAccountPort userAccountPort;

    @Mock
    private OneTimeTokenPort oneTimeTokenPort;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private VerificationEmailPort verificationEmailPort;

    @Mock
    private AuditService auditService;

    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private Instant now;
    private MeterRegistry meterRegistry;
    private AuthApplicationService authService;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-09-19T10:00:00Z");
        fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        authService = new AuthApplicationService(
                userAccountPort,
                oneTimeTokenPort,
                passwordEncoder,
                verificationEmailPort,
                auditService,
                objectMapper,
                fixedClock,
                meterRegistry
        );
    }

    @Test
    @DisplayName("Register user normalizes email, hashes password, stores hashed token, and records audit")
    void registerSuccess() {
        RegisterUserCommand command = new RegisterUserCommand(
                "  User.Test@Example.COM  ",
                "SecureP@ss123",
                "Test Athlete",
                "vi-VN",
                "Asia/Ho_Chi_Minh"
        );

        when(userAccountPort.existsByEmailIgnoreCase("user.test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("SecureP@ss123")).thenReturn("$2a$12$hashedPasswordValue");

        UUID generatedUserId = UUID.randomUUID();
        when(userAccountPort.saveAndFlush(any(UserAccountRecord.class))).thenAnswer(invocation -> {
            UserAccountRecord u = invocation.getArgument(0);
            return new UserAccountRecord(
                    generatedUserId,
                    u.email(),
                    u.passwordHash(),
                    u.displayName(),
                    u.status(),
                    u.preferredLocale(),
                    u.timezone(),
                    u.emailVerifiedAt(),
                    u.createdAt(),
                    u.updatedAt()
            );
        });

        RegisterUserResult result = authService.register(command);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(generatedUserId);
        assertThat(result.email()).isEqualTo("user.test@example.com");
        assertThat(result.displayName()).isEqualTo("Test Athlete");
        assertThat(result.status()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
        assertThat(result.emailVerifiedAt()).isNull();

        // Verify token generated, hashed, and persisted via domain record
        ArgumentCaptor<OneTimeTokenRecord> tokenCaptor = ArgumentCaptor.forClass(OneTimeTokenRecord.class);
        verify(oneTimeTokenPort).saveAndFlush(tokenCaptor.capture());
        OneTimeTokenRecord persistedToken = tokenCaptor.getValue();
        assertThat(persistedToken.userId()).isEqualTo(generatedUserId);
        assertThat(persistedToken.tokenType()).isEqualTo(OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION);
        assertThat(persistedToken.tokenHash()).matches("^[a-f0-9]{64}$");
        assertThat(persistedToken.expiresAt()).isEqualTo(now.plus(Duration.ofHours(24)));

        // Verify audit and security logging were called with structured JSON
        verify(auditService).recordSecurityEvent(any(SecurityEventRecord.class));
        verify(auditService).recordAudit(any(AuditRecord.class));

        // When not in an active transaction, verification email is dispatched immediately
        verify(verificationEmailPort).sendVerificationEmail(eq("user.test@example.com"), anyString());
    }

    @Test
    @DisplayName("Email delivery exception increments failure metric without throwing")
    void registerEmailDeliveryFailureIncrementsCounter() {
        RegisterUserCommand command = new RegisterUserCommand(
                "delivery.fail@example.com",
                "SecureP@ss123",
                "Delivery Athlete",
                null,
                null
        );

        when(userAccountPort.existsByEmailIgnoreCase("delivery.fail@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userAccountPort.saveAndFlush(any(UserAccountRecord.class))).thenAnswer(invocation -> {
            UserAccountRecord u = invocation.getArgument(0);
            return new UserAccountRecord(
                    UUID.randomUUID(),
                    u.email(),
                    u.passwordHash(),
                    u.displayName(),
                    u.status(),
                    u.preferredLocale(),
                    u.timezone(),
                    u.emailVerifiedAt(),
                    u.createdAt(),
                    u.updatedAt()
            );
        });

        doThrow(new RuntimeException("Simulated SMTP timeout"))
                .when(verificationEmailPort).sendVerificationEmail(anyString(), anyString());

        RegisterUserResult result = authService.register(command);
        assertThat(result).isNotNull();

        double count = meterRegistry.counter("auth.email.verification.delivery.failures").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Register user rejects duplicate email case-insensitively")
    void registerDuplicateEmailThrowsException() {
        RegisterUserCommand command = new RegisterUserCommand(
                "USER.TEST@EXAMPLE.COM",
                "SecureP@ss123",
                "Duplicate Athlete",
                null,
                null
        );

        when(userAccountPort.existsByEmailIgnoreCase("user.test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(command))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessage("Email is already registered.");

        verify(userAccountPort, never()).saveAndFlush(any());
        verify(verificationEmailPort, never()).sendVerificationEmail(any(), any());
    }

    @Test
    @DisplayName("Register user maps DataIntegrityViolationException to EmailAlreadyRegisteredException")
    void registerDatabaseConflictThrowsEmailAlreadyRegistered() {
        RegisterUserCommand command = new RegisterUserCommand(
                "race@example.com",
                "SecureP@ss123",
                "Race Athlete",
                null,
                null
        );

        when(userAccountPort.existsByEmailIgnoreCase("race@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userAccountPort.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> authService.register(command))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessage("Email is already registered.");
    }

    @Test
    @DisplayName("Audit failure during registration propagates exception to ensure rollback")
    void auditFailureDuringRegistrationThrowsException() {
        RegisterUserCommand command = new RegisterUserCommand(
                "audit.fail@example.com",
                "SecureP@ss123",
                "Audit Fail Athlete",
                null,
                null
        );

        when(userAccountPort.existsByEmailIgnoreCase("audit.fail@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userAccountPort.saveAndFlush(any(UserAccountRecord.class))).thenAnswer(invocation -> {
            UserAccountRecord u = invocation.getArgument(0);
            return new UserAccountRecord(
                    UUID.randomUUID(),
                    u.email(),
                    u.passwordHash(),
                    u.displayName(),
                    u.status(),
                    u.preferredLocale(),
                    u.timezone(),
                    u.emailVerifiedAt(),
                    u.createdAt(),
                    u.updatedAt()
            );
        });

        doThrow(new DataAccessException("Audit DB connection failed") {})
                .when(auditService).recordAudit(any(AuditRecord.class));

        assertThatThrownBy(() -> authService.register(command))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("Confirm email succeeds for pending verification user")
    void confirmEmailSuccess() {
        String rawToken = "valid-secret-token-12345678901234567890";
        String tokenHash = TokenHasher.sha256Hex(rawToken);
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();

        OneTimeTokenRecord token = new OneTimeTokenRecord(
                tokenId,
                userId,
                OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION,
                tokenHash,
                now.plus(Duration.ofHours(1)),
                null,
                now.minus(Duration.ofHours(1))
        );

        when(oneTimeTokenPort.findByTokenHashAndPurpose(tokenHash, OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION))
                .thenReturn(Optional.of(token));
        when(oneTimeTokenPort.consumeToken(tokenId, now)).thenReturn(1);
        when(userAccountPort.activatePendingUser(userId, now)).thenReturn(1);

        authService.confirmEmail(new ConfirmEmailCommand(rawToken));

        verify(oneTimeTokenPort).consumeToken(tokenId, now);
        verify(userAccountPort).activatePendingUser(userId, now);
        verify(auditService).recordSecurityEvent(any(SecurityEventRecord.class));
        verify(auditService).recordAudit(any(AuditRecord.class));
    }

    @Test
    @DisplayName("Confirm email rejects non-existent token with indistinguishable error")
    void confirmEmailNonExistentTokenThrows() {
        when(oneTimeTokenPort.findByTokenHashAndPurpose(anyString(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.confirmEmail(new ConfirmEmailCommand("non-existent-token-12345678901234567890")))
                .isInstanceOf(InvalidOrExpiredTokenException.class)
                .hasMessage("The verification token is invalid, expired, or has already been used.");
    }

    @Test
    @DisplayName("Confirm email rejects already consumed token")
    void confirmEmailAlreadyConsumedTokenThrows() {
        String rawToken = "already-consumed-token-12345678901234567890";
        String tokenHash = TokenHasher.sha256Hex(rawToken);

        OneTimeTokenRecord token = new OneTimeTokenRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION,
                tokenHash,
                now.plus(Duration.ofHours(1)),
                now.minus(Duration.ofMinutes(10)),
                now.minus(Duration.ofHours(1))
        );

        when(oneTimeTokenPort.findByTokenHashAndPurpose(tokenHash, OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.confirmEmail(new ConfirmEmailCommand(rawToken)))
                .isInstanceOf(InvalidOrExpiredTokenException.class)
                .hasMessage("The verification token is invalid, expired, or has already been used.");

        verify(oneTimeTokenPort, never()).consumeToken(any(), any());
    }

    @Test
    @DisplayName("Confirm email boundary: token expiring exactly at current time is treated as expired")
    void confirmEmailTokenExpiringExactlyAtNowThrows() {
        String rawToken = "exact-expiry-token-12345678901234567890";
        String tokenHash = TokenHasher.sha256Hex(rawToken);

        // expiresAt equals exactly 'now'
        OneTimeTokenRecord token = new OneTimeTokenRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION,
                tokenHash,
                now,
                null,
                now.minus(Duration.ofHours(24))
        );

        when(oneTimeTokenPort.findByTokenHashAndPurpose(tokenHash, OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.confirmEmail(new ConfirmEmailCommand(rawToken)))
                .isInstanceOf(InvalidOrExpiredTokenException.class)
                .hasMessage("The verification token is invalid, expired, or has already been used.");

        verify(oneTimeTokenPort, never()).consumeToken(any(), any());
    }

    @Test
    @DisplayName("Confirm email rejects expired token")
    void confirmEmailExpiredTokenThrows() {
        String rawToken = "expired-token-12345678901234567890";
        String tokenHash = TokenHasher.sha256Hex(rawToken);

        OneTimeTokenRecord token = new OneTimeTokenRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION,
                tokenHash,
                now.minus(Duration.ofSeconds(1)),
                null,
                now.minus(Duration.ofHours(24))
        );

        when(oneTimeTokenPort.findByTokenHashAndPurpose(tokenHash, OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.confirmEmail(new ConfirmEmailCommand(rawToken)))
                .isInstanceOf(InvalidOrExpiredTokenException.class)
                .hasMessage("The verification token is invalid, expired, or has already been used.");

        verify(oneTimeTokenPort, never()).consumeToken(any(), any());
    }

    @Test
    @DisplayName("Confirm email rejects when atomic consume updates 0 rows (concurrency race lost)")
    void confirmEmailAtomicConsumeLostThrows() {
        String rawToken = "race-token-12345678901234567890";
        String tokenHash = TokenHasher.sha256Hex(rawToken);
        UUID tokenId = UUID.randomUUID();

        OneTimeTokenRecord token = new OneTimeTokenRecord(
                tokenId,
                UUID.randomUUID(),
                OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION,
                tokenHash,
                now.plus(Duration.ofHours(1)),
                null,
                now.minus(Duration.ofHours(1))
        );

        when(oneTimeTokenPort.findByTokenHashAndPurpose(tokenHash, OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION))
                .thenReturn(Optional.of(token));
        when(oneTimeTokenPort.consumeToken(tokenId, now)).thenReturn(0);

        assertThatThrownBy(() -> authService.confirmEmail(new ConfirmEmailCommand(rawToken)))
                .isInstanceOf(InvalidOrExpiredTokenException.class)
                .hasMessage("The verification token is invalid, expired, or has already been used.");

        verify(userAccountPort, never()).activatePendingUser(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "DISABLED", "DELETION_PENDING", "DELETED"})
    @DisplayName("Confirm email refuses to reactivate unavailable account states")
    void confirmEmailRefusesToReactivateUnavailableAccounts(AccountStatus unavailableStatus) {
        String rawToken = "token-for-unavailable-12345678901234567890";
        String tokenHash = TokenHasher.sha256Hex(rawToken);
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();

        OneTimeTokenRecord token = new OneTimeTokenRecord(
                tokenId,
                userId,
                OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION,
                tokenHash,
                now.plus(Duration.ofHours(1)),
                null,
                now.minus(Duration.ofHours(1))
        );

        when(oneTimeTokenPort.findByTokenHashAndPurpose(tokenHash, OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION))
                .thenReturn(Optional.of(token));
        when(oneTimeTokenPort.consumeToken(tokenId, now)).thenReturn(1);
        when(userAccountPort.activatePendingUser(userId, now)).thenReturn(0);

        assertThatThrownBy(() -> authService.confirmEmail(new ConfirmEmailCommand(rawToken)))
                .isInstanceOf(InvalidOrExpiredTokenException.class)
                .hasMessage("The verification token is invalid, expired, or has already been used.");

        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("Audit failure during confirmation propagates exception to trigger rollback")
    void auditFailureDuringConfirmationThrows() {
        String rawToken = "audit-fail-token-12345678901234567890";
        String tokenHash = TokenHasher.sha256Hex(rawToken);
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();

        OneTimeTokenRecord token = new OneTimeTokenRecord(
                tokenId,
                userId,
                OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION,
                tokenHash,
                now.plus(Duration.ofHours(1)),
                null,
                now.minus(Duration.ofHours(1))
        );

        when(oneTimeTokenPort.findByTokenHashAndPurpose(tokenHash, OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION))
                .thenReturn(Optional.of(token));
        when(oneTimeTokenPort.consumeToken(tokenId, now)).thenReturn(1);
        when(userAccountPort.activatePendingUser(userId, now)).thenReturn(1);

        doThrow(new DataAccessException("Security events DB connection failed") {})
                .when(auditService).recordSecurityEvent(any(SecurityEventRecord.class));

        assertThatThrownBy(() -> authService.confirmEmail(new ConfirmEmailCommand(rawToken)))
                .isInstanceOf(DataAccessException.class);
    }
}
