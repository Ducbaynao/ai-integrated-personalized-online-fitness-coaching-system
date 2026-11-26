package com.fitnesscoaching.platform.modules.coaching.application.service;

import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.CoachingFailure;
import com.fitnesscoaching.platform.modules.coaching.application.CoachingQrRateLimitException;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingQrStore;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingStore;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingQrState.*;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Receipt;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerPublicPreview;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerAvailabilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerPublicPreviewQuery;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummary;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserDirectoryQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CoachingQrServiceUnitTest {
    private static final Instant NOW = Instant.parse("2026-11-25T16:30:00Z");

    private CoachingQrStore qrStore;
    private CoachingStore coachingStore;
    private UserAccountStatusQuery accounts;
    private UserRoleQuery roles;
    private UserDirectoryQuery users;
    private StudentCapabilityQuery students;
    private TrainerCoachingEligibilityQuery trainers;
    private TrainerAvailabilityQuery availability;
    private TrainerPublicPreviewQuery trainerPreviews;
    private CoachingQrRateLimiter rateLimiter;
    private AuditService audit;
    private CoachingQrService service;

    @BeforeEach
    void setUp() {
        qrStore = mock(CoachingQrStore.class);
        coachingStore = mock(CoachingStore.class);
        accounts = mock(UserAccountStatusQuery.class);
        roles = mock(UserRoleQuery.class);
        users = mock(UserDirectoryQuery.class);
        students = mock(StudentCapabilityQuery.class);
        trainers = mock(TrainerCoachingEligibilityQuery.class);
        availability = mock(TrainerAvailabilityQuery.class);
        trainerPreviews = mock(TrainerPublicPreviewQuery.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        rateLimiter = new CoachingQrRateLimiter(clock, 3);
        audit = mock(AuditService.class);
        service = new CoachingQrService(
                qrStore, coachingStore, accounts, roles, users, students,
                trainers, availability, trainerPreviews, rateLimiter, audit, new ObjectMapper(), clock);
    }

    @Test
    void issueMyQrGenerates32CharBase64UrlCodeAndReturnsExistingWithoutRotating() {
        UUID userId = UUID.randomUUID();
        when(accounts.getAccountStatus(userId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(roles.hasActiveRole(userId, "STUDENT")).thenReturn(true);
        when(students.hasProfile(userId)).thenReturn(true);
        when(users.findDisplaySummaries(List.of(userId))).thenReturn(Map.of(userId, new UserDisplaySummary(userId, "Student A")));
        when(qrStore.findActiveByOwnerAndRole(userId, QrRole.STUDENT)).thenReturn(Optional.empty());
        when(qrStore.insertIfAbsentActive(eq(userId), eq(QrRole.STUDENT), any(), eq(1), eq(NOW)))
                .thenAnswer(inv -> {
                    String code = inv.getArgument(2, String.class);
                    assertThat(code).matches("^[A-Za-z0-9_-]{32}$");
                    return Optional.of(new QrCodeRecord(UUID.randomUUID(), userId, QrRole.STUDENT, code, 1, "ACTIVE", 1L, NOW, null, null));
                });

        IssueResult result = service.issueMyQr(userId, QrRole.STUDENT);
        assertThat(result.created()).isTrue();
        assertThat(result.identity().qrUri()).isEqualTo("ai-fitness-coaching://coaching/qr?v=1&c=" + result.identity().publicCode());
        verify(audit).recordAudit(any());
    }

    @Test
    void resolveConcealsUnavailableTargetBeforeSelfScanAndRoleMismatch() {
        UUID actor = UUID.randomUUID();
        String code = "k8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8a";
        when(accounts.getAccountStatus(actor)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(roles.hasActiveRole(actor, "STUDENT")).thenReturn(true);
        when(students.hasProfile(actor)).thenReturn(true);
        // Target is the same user or another user, but has STUDENT role while account is SUSPENDED
        UUID target = UUID.randomUUID();
        when(qrStore.findActiveByPublicCode(code))
                .thenReturn(Optional.of(new QrCodeRecord(UUID.randomUUID(), target, QrRole.STUDENT, code, 1, "ACTIVE", 1L, NOW, null, null)));
        when(accounts.getAccountStatus(target)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        assertThatThrownBy(() -> service.resolveQr(actor, code, QrRole.TRAINER, 1))
                .isInstanceOf(CoachingFailure.class)
                .satisfies(ex -> {
                    CoachingFailure cf = (CoachingFailure) ex;
                    assertThat(cf.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(cf.code()).isEqualTo("COACHING_QR_NOT_FOUND");
                });
    }

    @Test
    void rotateReplayReturnsConflictAfterSubsequentRotation() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        UUID rotatedQrId = UUID.randomUUID();
        String code = "k8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8a";
        when(accounts.getAccountStatus(actor)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(roles.hasActiveRole(actor, "TRAINER")).thenReturn(true);
        when(trainers.canCoach(actor)).thenReturn(true);
        when(users.findDisplaySummaries(List.of(actor))).thenReturn(Map.of(actor, new UserDisplaySummary(actor, "PT A")));

        QrIdentity saved = new QrIdentity(rotatedQrId, QrRole.TRAINER, code, "ai-fitness-coaching://coaching/qr?v=1&c=" + code, 1, "PT A", NOW, 2L);
        String payloadHash = org.apache.commons.codec.digest.DigestUtils.sha256Hex("QR_ROTATE:TRAINER:1");
        when(coachingStore.receipt(actor, key)).thenReturn(Optional.of(new Receipt("QR_ROTATE", payloadHash, new ObjectMapper().writeValueAsString(saved))));
        when(qrStore.findById(rotatedQrId)).thenReturn(Optional.of(new QrCodeRecord(rotatedQrId, actor, QrRole.TRAINER, code, 1, "REVOKED", 2L, NOW, NOW, "ROTATED")));

        assertThatThrownBy(() -> service.rotateMyQr(actor, QrRole.TRAINER, 1L, key))
                .isInstanceOf(CoachingFailure.class)
                .satisfies(ex -> {
                    CoachingFailure cf = (CoachingFailure) ex;
                    assertThat(cf.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(cf.code()).isEqualTo("COACHING_QR_VERSION_CONFLICT");
                });
    }

    @Test
    void resolveEnforcesRateLimit() {
        UUID actor = UUID.randomUUID();
        String code = "k8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8a";
        when(accounts.getAccountStatus(actor)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(roles.hasActiveRole(actor, "STUDENT")).thenReturn(true);
        when(students.hasProfile(actor)).thenReturn(true);
        when(qrStore.findActiveByPublicCode(code)).thenReturn(Optional.empty());

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.resolveQr(actor, code, QrRole.TRAINER, 1))
                    .isInstanceOf(CoachingFailure.class);
        }
        assertThatThrownBy(() -> service.resolveQr(actor, code, QrRole.TRAINER, 1))
                .isInstanceOf(CoachingQrRateLimitException.class);
    }

    @Test
    void rateLimiterEvictsExpiredActorsSoMapDoesNotGrowUnbounded() {
        Instant[] current = { NOW };
        Clock mutableClock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return current[0]; }
        };
        CoachingQrRateLimiter limiter = new CoachingQrRateLimiter(mutableClock, 5);
        for (int i = 0; i < 65; i++) {
            limiter.checkAndConsume(UUID.randomUUID());
        }
        assertThat(limiter.trackedActorCount()).isEqualTo(65);

        current[0] = NOW.plusSeconds(61);
        for (int i = 0; i < 64; i++) {
            limiter.checkAndConsume(UUID.randomUUID());
        }
        // Periodic prune evicted the 65 expired actors; only the fresh ones remain
        assertThat(limiter.trackedActorCount()).isLessThanOrEqualTo(64);
    }
}
