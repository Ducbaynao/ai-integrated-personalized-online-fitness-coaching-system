package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.coaching.application.CoachingFailure;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityReason;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.HistoricalAuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.Permission;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Outcome;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class CoachingSharingIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("coaching_sharing").withUsername("fitness_app").withPassword("testpass123");
    static { postgres.start(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired CoachingSharingUseCase sharing;
    @Autowired CoachingAuthorityQuery authority;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean AuditService audit;

    @BeforeEach
    void resetAudit() {
        reset(audit);
    }

    @Test
    void authorityIsDenyByDefaultHierarchicalAndSeparatesHistoricalWindow() {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome active = accepted(student, trainer);
        assertThat(current(trainer, student, DataAccessLevel.VIEW).reason())
                .isEqualTo(AuthorityReason.PERMISSION_NOT_GRANTED);

        Instant databaseTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant from = databaseTime.minus(10, ChronoUnit.DAYS);
        Instant until = databaseTime.minus(1, ChronoUnit.DAYS);
        sharing.grantOrReplace(student, active.relationship().id(), DataScope.FITNESS_GOAL,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, from, until, null,
                null, UUID.randomUUID());

        assertThat(current(trainer, student, DataAccessLevel.VIEW).allowed()).isTrue();
        assertThat(current(trainer, student, DataAccessLevel.CONTRIBUTE).reason())
                .isEqualTo(AuthorityReason.ACCESS_LEVEL_INSUFFICIENT);
        assertThat(historical(trainer, student, from.plus(1, ChronoUnit.DAYS)).allowed()).isTrue();
        assertThat(historical(trainer, student, until).reason())
                .isEqualTo(AuthorityReason.RESOURCE_OUTSIDE_HISTORY_WINDOW);

        jdbc.update("""
                UPDATE fitness.coaching_periods SET ended_at=clock_timestamp()+interval '1 day'
                WHERE id=?
                """, active.currentPeriod().id());
        assertThat(current(trainer, student, DataAccessLevel.VIEW).allowed()).isTrue();
        lifecycle.relationshipAction(student, active.relationship().id(), "PAUSE", 1,
                null, UUID.randomUUID());
        assertThat(current(trainer, student, DataAccessLevel.VIEW).reason())
                .isEqualTo(AuthorityReason.RELATIONSHIP_NOT_ACTIVE);
        assertThat(historical(trainer, student, from.plus(1, ChronoUnit.DAYS)).allowed()).isFalse();
    }

    @Test
    void replaceRevokeAndReplayPreserveHistoryAuditAndMonotonicVersion() {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome active = accepted(student, trainer);
        UUID grantKey = UUID.randomUUID();
        Permission first = sharing.grantOrReplace(student, active.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null,
                null, grantKey);
        assertThat(sharing.grantOrReplace(student, active.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null,
                null, grantKey)).isEqualTo(first);

        Permission replacement = sharing.grantOrReplace(student, active.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.MANAGE, null, null, null,
                0L, UUID.randomUUID());
        assertThat(replacement.version()).isEqualTo(1);
        Permission revoked = sharing.revoke(student, active.relationship().id(), replacement.id(),
                1, "student revoked", UUID.randomUUID());
        assertThat(revoked.version()).isEqualTo(2);
        assertThat(current(trainer, student, DataAccessLevel.VIEW).allowed()).isFalse();
        assertThat(sharing.list(student, active.relationship().id(), 0, 20)).hasSize(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.audit_logs
                WHERE actor_user_id=? AND target_type='DATA_SHARING_PERMISSION'
                """, Integer.class, student)).isEqualTo(3);
    }

    @Test
    void concurrentReplaceHasExactlyOneWinner() throws Exception {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome active = accepted(student, trainer);
        sharing.grantOrReplace(student, active.relationship().id(), DataScope.BODY_METRICS,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null,
                null, UUID.randomUUID());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var conflicts = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (DataAccessLevel level : new DataAccessLevel[]{DataAccessLevel.CONTRIBUTE, DataAccessLevel.MANAGE}) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        sharing.grantOrReplace(student, active.relationship().id(), DataScope.BODY_METRICS,
                                SharingDecision.ALLOW, level, null, null, null, 0L, UUID.randomUUID());
                        successes.incrementAndGet();
                    } catch (CoachingFailure failure) {
                        if (failure.code().equals("DATA_SHARING_PERMISSION_CONFLICT")) {
                            conflicts.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(successes.get()).isOne();
        assertThat(conflicts.get()).isOne();
    }

    @Test
    void authorityLocksKeepPauseFromCommittingBetweenCheckAndConsumerAction() throws Exception {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome active = accepted(student, trainer);
        sharing.grantOrReplace(student, active.relationship().id(), DataScope.FITNESS_GOAL,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null,
                null, UUID.randomUUID());
        var authorityChecked = new CountDownLatch(1);
        var releaseConsumer = new CountDownLatch(1);
        var pauseStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var consumer = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(current(trainer, student, DataAccessLevel.VIEW).allowed()).isTrue();
                authorityChecked.countDown();
                try {
                    assertThat(releaseConsumer.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return null;
            }));
            assertThat(authorityChecked.await(5, TimeUnit.SECONDS)).isTrue();
            var pause = pool.submit(() -> {
                pauseStarted.countDown();
                return lifecycle.relationshipAction(student, active.relationship().id(), "PAUSE",
                        1, null, UUID.randomUUID());
            });
            assertThat(pauseStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> pause.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseConsumer.countDown();
            consumer.get(5, TimeUnit.SECONDS);
            assertThat(pause.get(5, TimeUnit.SECONDS).relationship().status()).isEqualTo("PAUSED");
        }
    }

    @Test
    void auditFailureRollsBackGrantAndEndedTrainerCannotListHistory() {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome active = accepted(student, trainer);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordAudit(any(AuditRecord.class));
        assertThatThrownBy(() -> sharing.grantOrReplace(student, active.relationship().id(),
                DataScope.NUTRITION_LOGS, SharingDecision.ALLOW, DataAccessLevel.VIEW,
                null, null, null, null, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.data_sharing_permissions
                WHERE relationship_id=? AND data_scope='NUTRITION_LOGS'
                """, Integer.class, active.relationship().id())).isZero();
        reset(audit);
        lifecycle.relationshipAction(student, active.relationship().id(), "END", 1,
                "finished", UUID.randomUUID());
        assertThatThrownBy(() -> sharing.list(trainer, active.relationship().id(), 0, 20))
                .isInstanceOf(CoachingFailure.class).extracting("code")
                .isEqualTo("COACHING_RELATIONSHIP_NOT_FOUND");
    }

    private com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision current(
            UUID trainer, UUID student, DataAccessLevel level) {
        return authority.evaluateCurrent(new AuthorityRequest(
                trainer, student, DataScope.FITNESS_GOAL, level));
    }

    private com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision historical(
            UUID trainer, UUID student, Instant occurredAt) {
        return authority.evaluateHistorical(new HistoricalAuthorityRequest(
                trainer, student, DataScope.FITNESS_GOAL, DataAccessLevel.VIEW, occurredAt));
    }

    private Outcome accepted(UUID student, UUID trainer) {
        Outcome pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        return lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT",
                0, null, UUID.randomUUID());
    }

    private UUID person(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'test-hash','Test Person','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')
                """, id, id + "@example.com");
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?
                """, id, id, role);
        if (role.equals("STUDENT")) {
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        } else {
            jdbc.update("""
                    INSERT INTO fitness.trainer_profiles
                    (user_id,verification_status,verified_at,is_accepting_students,is_active)
                    VALUES (?,'VERIFIED',clock_timestamp(),true,true)
                    """, id);
        }
        return id;
    }
}
