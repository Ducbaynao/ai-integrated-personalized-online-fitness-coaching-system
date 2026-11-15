package com.fitnesscoaching.platform.modules.administration.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.InvalidCredentialsException;
import com.fitnesscoaching.platform.modules.administration.adapter.out.persistence.AdminUserPersistenceAdapter;
import com.fitnesscoaching.platform.modules.administration.application.model.StepUpResult;
import com.fitnesscoaching.platform.modules.administration.application.port.in.AdminStepUpUseCase;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class AdminStepUpService implements AdminStepUpUseCase {

    private static final Duration STEP_UP_TTL = Duration.ofMinutes(15);

    private final AdminUserPersistenceAdapter persistence;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AdminStepUpService(
            AdminUserPersistenceAdapter persistence,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.persistence = persistence;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    public StepUpResult verifyAndIssueStepUp(UUID adminUserId, String actionCode, String password) {
        verifyActiveAdmin(adminUserId);

        if (actionCode == null || actionCode.isBlank()) {
            throw new ApplicationValidationException("Action code is required for step-up authentication");
        }
        if (password == null || password.isBlank()) {
            throw new InvalidCredentialsException("Password is required for step-up verification");
        }

        String passwordHash = persistence.getUserPasswordHash(adminUserId)
                .orElseThrow(() -> new InvalidCredentialsException("User credentials not found"));

        if (!passwordEncoder.matches(password, passwordHash)) {
            throw new InvalidCredentialsException("Invalid password for step-up authentication");
        }

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(STEP_UP_TTL);

        UUID stepUpId = persistence.createVerifiedStepUp(adminUserId, actionCode.trim(), now, expiresAt);
        return new StepUpResult(stepUpId, expiresAt);
    }

    public UUID resolveOrVerifyStepUp(UUID adminUserId, String actionCode, String password, UUID providedStepUpId) {
        Instant now = Instant.now(clock);

        if (password != null && !password.isBlank()) {
            String passwordHash = persistence.getUserPasswordHash(adminUserId)
                    .orElseThrow(() -> new InvalidCredentialsException("User credentials not found"));
            if (!passwordEncoder.matches(password, passwordHash)) {
                throw new InvalidCredentialsException("Invalid administrator password for step-up authentication");
            }
            return persistence.createVerifiedStepUp(adminUserId, actionCode, now, now.plus(STEP_UP_TTL));
        }

        if (providedStepUpId != null) {
            if (persistence.isStepUpValid(providedStepUpId, adminUserId, actionCode, now)) {
                return providedStepUpId;
            }
            throw new AccessDeniedException("The provided step-up authentication has expired or is invalid");
        }

        throw new AccessDeniedException("Step-up authentication is required for sensitive administrative action '" + actionCode + "'");
    }

    private void verifyActiveAdmin(UUID adminUserId) {
        if (adminUserId == null) {
            throw new AccessDeniedException("Admin user ID is required");
        }
        AccountStatus status = userAccountStatusQuery.getAccountStatus(adminUserId)
                .orElseThrow(() -> new AccessDeniedException("User does not exist"));

        if (status != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Administrator account is not active");
        }

        if (!userRoleQuery.hasActiveRole(adminUserId, "ADMIN")) {
            throw new AccessDeniedException("User does not possess an active ADMIN role");
        }
    }
}
