package com.fitnesscoaching.platform.modules.coaching.application.service;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingSharingStore;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.*;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.trainer.domain.CoachingEligibility;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoachingAuthorityServiceUnitTest {
    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @Mock CoachingSharingStore store;
    @Mock UserAccountStatusQuery accounts;
    @Mock UserRoleQuery roles;
    @Mock TrainerCoachingEligibilityQuery trainers;

    private CoachingAuthorityService service;
    private UUID trainerId;
    private UUID studentId;
    private UUID relationshipId;
    private UUID periodId;

    @BeforeEach
    void setUp() {
        service = new CoachingAuthorityService(store, accounts, roles, trainers,
                Clock.fixed(NOW, ZoneOffset.UTC));
        trainerId = UUID.randomUUID();
        studentId = UUID.randomUUID();
        relationshipId = UUID.randomUUID();
        periodId = UUID.randomUUID();
    }

    @Test
    void denyByDefaultWhenNoActiveRelationshipExists() {
        eligibleTrainer();
        when(store.authorityContext(any(), any(), any(), any())).thenReturn(Optional.empty());

        AuthorityDecision decision = service.evaluateCurrent(request(DataAccessLevel.VIEW));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AuthorityReason.RELATIONSHIP_NOT_ACTIVE);
    }

    @Test
    void accessLevelsAreOrderedButViewDoesNotSatisfyContribute() {
        eligibleTrainer();
        when(store.authorityContext(any(), any(), any(), any()))
                .thenReturn(Optional.of(context(permission(DataAccessLevel.VIEW, null, null))));

        assertThat(service.evaluateCurrent(request(DataAccessLevel.VIEW)).allowed()).isTrue();
        AuthorityDecision contribute = service.evaluateCurrent(request(DataAccessLevel.CONTRIBUTE));
        assertThat(contribute.allowed()).isFalse();
        assertThat(contribute.reason()).isEqualTo(AuthorityReason.ACCESS_LEVEL_INSUFFICIENT);
    }

    @Test
    void manageSatisfiesAllLowerLevels() {
        eligibleTrainer();
        when(store.authorityContext(any(), any(), any(), any()))
                .thenReturn(Optional.of(context(permission(DataAccessLevel.MANAGE, null, null))));

        assertThat(service.evaluateCurrent(request(DataAccessLevel.VIEW)).allowed()).isTrue();
        assertThat(service.evaluateCurrent(request(DataAccessLevel.CONTRIBUTE)).allowed()).isTrue();
        assertThat(service.evaluateCurrent(request(DataAccessLevel.MANAGE)).allowed()).isTrue();
    }

    @Test
    void explicitDenyOverridesAnOtherwiseValidAuthorityChain() {
        eligibleTrainer();
        Permission denied = new Permission(UUID.randomUUID(), relationshipId, studentId, trainerId,
                DataScope.WORKOUT_HISTORY, SharingDecision.DENY, DataAccessLevel.MANAGE,
                NOW.minusSeconds(3600), null, NOW.minusSeconds(600), null, studentId,
                null, null, 0, NOW.minusSeconds(600));
        when(store.authorityContext(any(), any(), any(), any()))
                .thenReturn(Optional.of(context(denied)));

        AuthorityDecision decision = service.evaluateCurrent(request(DataAccessLevel.VIEW));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AuthorityReason.PERMISSION_NOT_GRANTED);
    }

    @Test
    void historicalWindowIsEvaluatedOnlyAfterCurrentAuthority() {
        eligibleTrainer();
        Permission permission = permission(DataAccessLevel.VIEW,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"));
        when(store.authorityContext(any(), any(), any(), any()))
                .thenReturn(Optional.of(context(permission)));

        AuthorityDecision inside = service.evaluateHistorical(historical("2026-03-01T00:00:00Z"));
        AuthorityDecision upperBoundary = service.evaluateHistorical(historical("2026-06-01T00:00:00Z"));

        assertThat(inside.allowed()).isTrue();
        assertThat(upperBoundary.allowed()).isFalse();
        assertThat(upperBoundary.reason()).isEqualTo(AuthorityReason.RESOURCE_OUTSIDE_HISTORY_WINDOW);
    }

    @Test
    void noExplicitHistoryFromDeniesHistoricalRecord() {
        eligibleTrainer();
        when(store.authorityContext(any(), any(), any(), any()))
                .thenReturn(Optional.of(context(permission(DataAccessLevel.VIEW, null, null))));

        AuthorityDecision decision = service.evaluateHistorical(historical("2026-03-01T00:00:00Z"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AuthorityReason.HISTORY_WINDOW_NOT_GRANTED);
    }

    private void eligibleTrainer() {
        when(accounts.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(roles.hasActiveRole(trainerId, "TRAINER")).thenReturn(true);
        when(trainers.getCoachingEligibility(trainerId)).thenReturn(new CoachingEligibility(true, List.of()));
    }

    private AuthorityRequest request(DataAccessLevel level) {
        return new AuthorityRequest(trainerId, studentId, DataScope.WORKOUT_HISTORY, level);
    }

    private HistoricalAuthorityRequest historical(String occurredAt) {
        return new HistoricalAuthorityRequest(trainerId, studentId, DataScope.WORKOUT_HISTORY,
                DataAccessLevel.VIEW, Instant.parse(occurredAt));
    }

    private Permission permission(DataAccessLevel level, Instant historyFrom, Instant historyUntil) {
        return new Permission(UUID.randomUUID(), relationshipId, studentId, trainerId,
                DataScope.WORKOUT_HISTORY, SharingDecision.ALLOW, level, historyFrom, historyUntil,
                NOW.minusSeconds(600), null, studentId, null, null, 0, NOW.minusSeconds(600));
    }

    private AuthorityContext context(Permission permission) {
        return new AuthorityContext(relationshipId, 1, periodId, permission);
    }
}
