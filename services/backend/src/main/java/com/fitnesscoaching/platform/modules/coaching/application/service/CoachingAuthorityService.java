package com.fitnesscoaching.platform.modules.coaching.application.service;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingSharingStore;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.*;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class CoachingAuthorityService implements CoachingAuthorityQuery {
    private final CoachingSharingStore store;
    private final UserAccountStatusQuery accounts;
    private final UserRoleQuery roles;
    private final TrainerCoachingEligibilityQuery trainers;
    private final Clock clock;

    public CoachingAuthorityService(CoachingSharingStore store, UserAccountStatusQuery accounts,
                                    UserRoleQuery roles, TrainerCoachingEligibilityQuery trainers,
                                    Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.roles = roles;
        this.trainers = trainers;
        this.clock = clock;
    }

    @Override
    public AuthorityDecision evaluateCurrent(AuthorityRequest request) {
        if (request == null || request.actorId() == null || request.studentId() == null
                || request.dataScope() == null || request.requiredLevel() == null
                || request.actorId().equals(request.studentId())) {
            return AuthorityDecision.denied(AuthorityReason.INVALID_REQUEST);
        }
        store.lockAuthorityBoundary(request.studentId(), request.actorId());
        AccountStatus status = accounts.getAccountStatus(request.actorId()).orElse(null);
        if (status == null) {
            return AuthorityDecision.denied(AuthorityReason.USER_NOT_FOUND);
        }
        if (status != AccountStatus.ACTIVE) {
            return AuthorityDecision.denied(AuthorityReason.ACCOUNT_UNAVAILABLE);
        }
        if (!roles.hasActiveRole(request.actorId(), "TRAINER")) {
            return AuthorityDecision.denied(AuthorityReason.TRAINER_CAPABILITY_UNAVAILABLE);
        }
        if (!trainers.getCoachingEligibility(request.actorId()).eligible()) {
            return AuthorityDecision.denied(AuthorityReason.TRAINER_NOT_ELIGIBLE);
        }

        AuthorityContext context = store.authorityContext(
                request.actorId(), request.studentId(), request.dataScope(), Instant.now(clock)).orElse(null);
        if (context == null) {
            return AuthorityDecision.denied(AuthorityReason.RELATIONSHIP_NOT_ACTIVE);
        }
        if (context.periodId() == null) {
            return new AuthorityDecision(false, AuthorityReason.PERIOD_NOT_EFFECTIVE,
                    context.relationshipId(), context.relationshipVersion(), null,
                    null, -1, null, null, null);
        }
        Permission permission = context.permission();
        if (permission == null || permission.decision() != SharingDecision.ALLOW) {
            return new AuthorityDecision(false, AuthorityReason.PERMISSION_NOT_GRANTED,
                    context.relationshipId(), context.relationshipVersion(), context.periodId(),
                    permission == null ? null : permission.id(),
                    permission == null ? -1 : permission.version(),
                    permission == null ? null : permission.accessLevel(),
                    permission == null ? null : permission.historyFrom(),
                    permission == null ? null : permission.historyUntil());
        }
        if (!permission.accessLevel().satisfies(request.requiredLevel())) {
            return decision(false, AuthorityReason.ACCESS_LEVEL_INSUFFICIENT, context, permission);
        }
        return decision(true, AuthorityReason.ALLOWED, context, permission);
    }

    @Override
    public AuthorityDecision evaluateHistorical(HistoricalAuthorityRequest request) {
        if (request == null || request.resourceOccurredAt() == null) {
            return AuthorityDecision.denied(AuthorityReason.INVALID_REQUEST);
        }
        AuthorityDecision current = evaluateCurrent(new AuthorityRequest(
                request.actorId(), request.studentId(), request.dataScope(), request.requiredLevel()));
        if (!current.allowed()) {
            return current;
        }
        if (current.historyFrom() == null) {
            return copyDenied(current, AuthorityReason.HISTORY_WINDOW_NOT_GRANTED);
        }
        boolean inWindow = !request.resourceOccurredAt().isBefore(current.historyFrom())
                && (current.historyUntil() == null || request.resourceOccurredAt().isBefore(current.historyUntil()));
        return inWindow ? current : copyDenied(current, AuthorityReason.RESOURCE_OUTSIDE_HISTORY_WINDOW);
    }

    private static AuthorityDecision decision(boolean allowed, AuthorityReason reason,
                                              AuthorityContext context, Permission permission) {
        return new AuthorityDecision(allowed, reason, context.relationshipId(), context.relationshipVersion(),
                context.periodId(), permission.id(), permission.version(), permission.accessLevel(),
                permission.historyFrom(), permission.historyUntil());
    }

    private static AuthorityDecision copyDenied(AuthorityDecision current, AuthorityReason reason) {
        return new AuthorityDecision(false, reason, current.relationshipId(), current.relationshipVersion(),
                current.periodId(), current.permissionId(), current.permissionVersion(), current.grantedLevel(),
                current.historyFrom(), current.historyUntil());
    }
}
