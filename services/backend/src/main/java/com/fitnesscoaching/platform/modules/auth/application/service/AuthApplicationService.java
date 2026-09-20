package com.fitnesscoaching.platform.modules.auth.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.EmailAlreadyRegisteredException;
import com.fitnesscoaching.platform.common.exception.InvalidOrExpiredTokenException;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.audit.SecurityEventRecord;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserResult;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.out.OneTimeTokenPort;
import com.fitnesscoaching.platform.modules.auth.application.port.out.UserAccountPort;
import com.fitnesscoaching.platform.modules.auth.application.port.out.VerificationEmailPort;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.auth.domain.OneTimeTokenRecord;
import com.fitnesscoaching.platform.modules.auth.domain.TokenGenerator;
import com.fitnesscoaching.platform.modules.auth.domain.TokenHasher;
import com.fitnesscoaching.platform.modules.auth.domain.UserAccountRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Service
@Transactional
public class AuthApplicationService implements RegisterUserUseCase, ConfirmEmailUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);
    private static final Duration TOKEN_EXPIRY_DURATION = Duration.ofHours(24);
    private static final String DEFAULT_TOKEN_ERROR_MESSAGE = "The verification token is invalid, expired, or has already been used.";

    private final UserAccountPort userAccountPort;
    private final OneTimeTokenPort oneTimeTokenPort;
    private final PasswordEncoder passwordEncoder;
    private final VerificationEmailPort verificationEmailPort;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter emailDeliveryFailureCounter;

    public AuthApplicationService(
            UserAccountPort userAccountPort,
            OneTimeTokenPort oneTimeTokenPort,
            PasswordEncoder passwordEncoder,
            VerificationEmailPort verificationEmailPort,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.userAccountPort = userAccountPort;
        this.oneTimeTokenPort = oneTimeTokenPort;
        this.passwordEncoder = passwordEncoder;
        this.verificationEmailPort = verificationEmailPort;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.emailDeliveryFailureCounter = Counter.builder("auth.email.verification.delivery.failures")
                .description("Count of failed post-commit email verification deliveries")
                .register(meterRegistry);
    }

    @Override
    public RegisterUserResult register(RegisterUserCommand command) {
        String normalizedEmail = command.email().trim().toLowerCase(Locale.ROOT);

        if (userAccountPort.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException("Email is already registered.");
        }

        String passwordHash = passwordEncoder.encode(command.password());
        Instant now = clock.instant();

        String preferredLocale = StringUtils.hasText(command.preferredLocale())
                ? command.preferredLocale().trim()
                : "vi-VN";
        String timezone = StringUtils.hasText(command.timezone())
                ? command.timezone().trim()
                : "Asia/Ho_Chi_Minh";

        UserAccountRecord pendingUser = UserAccountRecord.newPendingUser(
                normalizedEmail,
                passwordHash,
                command.displayName().trim(),
                preferredLocale,
                timezone,
                now
        );

        UserAccountRecord savedUser;
        try {
            savedUser = userAccountPort.saveAndFlush(pendingUser);
        } catch (DataIntegrityViolationException e) {
            log.warn("Database constraint conflict during user registration for email: {}", maskEmail(normalizedEmail));
            throw new EmailAlreadyRegisteredException("Email is already registered.");
        }

        // Generate and persist hashed one-time verification token
        String rawToken = TokenGenerator.generateSecureToken();
        String tokenHash = TokenHasher.sha256Hex(rawToken);
        Instant expiresAt = now.plus(TOKEN_EXPIRY_DURATION);

        OneTimeTokenRecord tokenRecord = OneTimeTokenRecord.newVerificationToken(
                savedUser.id(),
                tokenHash,
                expiresAt,
                now
        );
        oneTimeTokenPort.saveAndFlush(tokenRecord);

        // Mandatory Audit & Security logging (structured JSON, non-swallowed exceptions)
        String auditMetadataJson = serializeJson(Map.of("email", savedUser.email()));

        auditService.recordSecurityEvent(SecurityEventRecord.builder()
                .userId(savedUser.id())
                .eventType("USER_REGISTRATION")
                .severity("INFO")
                .detailsJson(auditMetadataJson)
                .occurredAt(now)
                .build());

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(savedUser.id())
                .actorRole("USER")
                .action("USER_REGISTERED")
                .targetType("USER")
                .targetId(savedUser.id())
                .requestId(RequestIdHolder.getAsUuid())
                .metadataJson(auditMetadataJson)
                .occurredAt(now)
                .build());

        // Dispatch verification email ONLY after the transaction commits successfully
        dispatchVerificationEmailAfterCommit(savedUser.email(), rawToken);

        return new RegisterUserResult(
                savedUser.id(),
                savedUser.email(),
                savedUser.displayName(),
                savedUser.status(),
                savedUser.preferredLocale(),
                savedUser.timezone(),
                savedUser.emailVerifiedAt(),
                savedUser.createdAt()
        );
    }

    @Override
    public void confirmEmail(ConfirmEmailCommand command) {
        String tokenHash = TokenHasher.sha256Hex(command.token().trim());
        Instant now = clock.instant();

        OneTimeTokenRecord token = oneTimeTokenPort
                .findByTokenHashAndPurpose(tokenHash, OneTimeTokenRecord.PURPOSE_EMAIL_VERIFICATION)
                .orElseThrow(() -> new InvalidOrExpiredTokenException(DEFAULT_TOKEN_ERROR_MESSAGE));

        // Reject if already consumed
        if (token.consumedAt() != null) {
            throw new InvalidOrExpiredTokenException(DEFAULT_TOKEN_ERROR_MESSAGE);
        }

        // Treat token expiring exactly at now (or before) as expired
        if (!token.expiresAt().isAfter(now)) {
            throw new InvalidOrExpiredTokenException(DEFAULT_TOKEN_ERROR_MESSAGE);
        }

        // Atomic token consumption requiring consumedAt IS NULL AND expiresAt > now
        int consumedRows = oneTimeTokenPort.consumeToken(token.id(), now);
        if (consumedRows == 0) {
            throw new InvalidOrExpiredTokenException(DEFAULT_TOKEN_ERROR_MESSAGE);
        }

        // Conditional atomic account activation: only transitions from PENDING_VERIFICATION to ACTIVE.
        // Never reactivates SUSPENDED, DISABLED, DELETION_PENDING, or DELETED accounts.
        int activatedRows = userAccountPort.activatePendingUser(token.userId(), now);
        if (activatedRows == 0) {
            log.warn("Email confirmation rejected: account {} is not in PENDING_VERIFICATION status", token.userId());
            throw new InvalidOrExpiredTokenException(DEFAULT_TOKEN_ERROR_MESSAGE);
        }

        // Mandatory Audit & Security logging (structured JSON)
        String verificationDetailsJson = serializeJson(Map.of("verifiedAt", now.toString()));

        auditService.recordSecurityEvent(SecurityEventRecord.builder()
                .userId(token.userId())
                .eventType("EMAIL_VERIFICATION_SUCCESS")
                .severity("INFO")
                .detailsJson(verificationDetailsJson)
                .occurredAt(now)
                .build());

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(token.userId())
                .actorRole("USER")
                .action("EMAIL_VERIFIED")
                .targetType("USER")
                .targetId(token.userId())
                .requestId(RequestIdHolder.getAsUuid())
                .metadataJson(verificationDetailsJson)
                .occurredAt(now)
                .build());
    }

    private void dispatchVerificationEmailAfterCommit(String recipientEmail, String rawToken) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        verificationEmailPort.sendVerificationEmail(recipientEmail, rawToken);
                    } catch (Exception e) {
                        emailDeliveryFailureCounter.increment();
                        log.warn("POST-COMMIT EMAIL DELIVERY FAILED for recipient: {}. " +
                                "External durable outbox delivery, background retry worker, and resend " +
                                "are explicitly deferred to Milestone M1B/M2. M1A is not production-email-ready.",
                                maskEmail(recipientEmail), e);
                    }
                }
            });
        } else {
            try {
                verificationEmailPort.sendVerificationEmail(recipientEmail, rawToken);
            } catch (Exception e) {
                emailDeliveryFailureCounter.increment();
                log.warn("EMAIL DELIVERY FAILED for recipient: {}. " +
                        "External durable outbox delivery, background retry worker, and resend " +
                        "are explicitly deferred to Milestone M1B/M2. M1A is not production-email-ready.",
                        maskEmail(recipientEmail), e);
            }
        }
    }

    private String serializeJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            log.error("Failed to serialize audit metadata to JSON", e);
            throw new IllegalStateException("Failed to serialize audit metadata to JSON", e);
        }
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        String name = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (name.length() <= 2) {
            return name.charAt(0) + "***" + domain;
        }
        return name.charAt(0) + "***" + name.charAt(name.length() - 1) + domain;
    }
}
