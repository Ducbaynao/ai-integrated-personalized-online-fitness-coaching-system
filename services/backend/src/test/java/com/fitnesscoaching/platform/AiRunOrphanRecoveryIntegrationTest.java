package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiOrphanRecoveryProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.application.service.AiRunOrphanRecoveryService;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AiRunOrphanRecoveryIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_orphan_recovery_integration")
            .withUsername("fitness_app")
            .withPassword("testpass123");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private AiRunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("SET search_path TO fitness, public");
        jdbcTemplate.execute("DELETE FROM fitness.ai_runs");
        // Ensure user exists
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, 'student.recovery@example.com', 'dummy_hash', 'Test Student', 'ACTIVE', now(), now())
                ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                """, USER_ID);
    }

    @Test
    @DisplayName("Stale RUNNING run older than threshold is reconciled to FAILED with TIMEOUT")
    void staleRunningRun_isReconciledToFailedWithTimeout() {
        Instant now = Instant.now();
        Instant staleStartedAt = now.minusSeconds(300); // 5 minutes ago

        AiRun run = runRepository.create(AiRun.builder()
                .requestedBy(USER_ID)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .status(AiRunStatus.RUNNING)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .inputPayload("{\"question\": \"Test stale run\"}")
                .queuedAt(staleStartedAt.minusSeconds(5))
                .startedAt(staleStartedAt)
                .createdAt(staleStartedAt.minusSeconds(5))
                .build());

        Clock testClock = Clock.fixed(now, ZoneId.of("UTC"));
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryService recoveryService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);

        int count = recoveryService.executeRecoveryBatch();
        assertThat(count).isEqualTo(1);

        AiRun updated = runRepository.findById(run.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(AiRunStatus.FAILED);
        assertThat(updated.errorCode()).isEqualTo("TIMEOUT");
        assertThat(updated.errorMessage()).contains("Execution timed out due to system interruption or backend restart.");
        assertThat(updated.completedAt()).isNotNull();
        assertThat(updated.inputTokenCount()).isNull();
        assertThat(updated.outputTokenCount()).isNull();
    }

    @Test
    @DisplayName("Stale QUEUED run older than threshold is reconciled to FAILED with TIMEOUT")
    void staleQueuedRun_isReconciledToFailedWithTimeout() {
        Instant now = Instant.now();
        Instant staleQueuedAt = now.minusSeconds(200); // > 120s threshold

        AiRun run = runRepository.create(AiRun.builder()
                .requestedBy(USER_ID)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .status(AiRunStatus.QUEUED)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .inputPayload("{\"question\": \"Test stale queued run\"}")
                .queuedAt(staleQueuedAt)
                .createdAt(staleQueuedAt)
                .build());

        Clock testClock = Clock.fixed(now, ZoneId.of("UTC"));
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryService recoveryService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);

        int count = recoveryService.executeRecoveryBatch();
        assertThat(count).isEqualTo(1);

        AiRun updated = runRepository.findById(run.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(AiRunStatus.FAILED);
        assertThat(updated.errorCode()).isEqualTo("TIMEOUT");
        assertThat(updated.completedAt()).isNotNull();
    }

    @Test
    @DisplayName("Active RUNNING run within threshold is untouched")
    void activeRunningRun_withinThreshold_isUntouched() {
        Instant now = Instant.now();
        Instant recentStartedAt = now.minusSeconds(30); // 30s ago, well within 120s SLA

        AiRun run = runRepository.create(AiRun.builder()
                .requestedBy(USER_ID)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .status(AiRunStatus.RUNNING)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .inputPayload("{\"question\": \"Active in-flight query\"}")
                .queuedAt(recentStartedAt.minusSeconds(1))
                .startedAt(recentStartedAt)
                .createdAt(recentStartedAt.minusSeconds(1))
                .build());

        Clock testClock = Clock.fixed(now, ZoneId.of("UTC"));
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryService recoveryService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);

        int count = recoveryService.executeRecoveryBatch();
        assertThat(count).isZero();

        AiRun current = runRepository.findById(run.id()).orElseThrow();
        assertThat(current.status()).isEqualTo(AiRunStatus.RUNNING);
        assertThat(current.errorCode()).isNull();
        assertThat(current.completedAt()).isNull();
    }

    @Test
    @DisplayName("Terminal run (SUCCEEDED) created long ago is NEVER altered by cleaner")
    void terminalRun_isNeverAlteredByCleaner() {
        Instant now = Instant.now();
        Instant oldTime = now.minusSeconds(1000);

        AiRun run = runRepository.create(AiRun.builder()
                .requestedBy(USER_ID)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .status(AiRunStatus.SUCCEEDED)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .inputPayload("{\"question\": \"Old succeeded query\"}")
                .outputPayload("{\"answer\": \"Old correct answer\"}")
                .queuedAt(oldTime)
                .startedAt(oldTime)
                .completedAt(oldTime.plusSeconds(3))
                .createdAt(oldTime)
                .build());

        Clock testClock = Clock.fixed(now, ZoneId.of("UTC"));
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryService recoveryService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);

        int count = recoveryService.executeRecoveryBatch();
        assertThat(count).isZero();

        AiRun current = runRepository.findById(run.id()).orElseThrow();
        assertThat(current.status()).isEqualTo(AiRunStatus.SUCCEEDED);
        assertThat(current.outputPayload()).isEqualTo("{\"answer\": \"Old correct answer\"}");
        assertThat(current.errorCode()).isNull();
    }

    @Test
    @DisplayName("Race condition: Run completed right before reconcile update cannot be overwritten")
    void raceCondition_runCompletedBeforeReconcile_cannotBeOverwritten() {
        Instant now = Instant.now();
        Instant staleTime = now.minusSeconds(200);

        AiRun run = runRepository.create(AiRun.builder()
                .requestedBy(USER_ID)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .status(AiRunStatus.RUNNING)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .inputPayload("{\"question\": \"Racing query\"}")
                .queuedAt(staleTime)
                .startedAt(staleTime)
                .createdAt(staleTime)
                .build());

        // Fast-path worker completes the run just before reconcile runs
        boolean completed = runRepository.completeRun(
                run.id(),
                AiRunStatus.SUCCEEDED,
                "{\"answer\": \"Valid answer delivered right on time\"}",
                null,
                null,
                100,
                50,
                3000,
                now
        );
        assertThat(completed).isTrue();

        // Cleaner tries to reconcile the run
        boolean reconciled = runRepository.reconcileStaleRun(
                run.id(),
                "TIMEOUT",
                "Execution timed out due to system interruption or backend restart.",
                now
        );

        // Reconcile must return false and fail closed
        assertThat(reconciled).isFalse();

        // Database record remains SUCCEEDED with valid output payload
        AiRun current = runRepository.findById(run.id()).orElseThrow();
        assertThat(current.status()).isEqualTo(AiRunStatus.SUCCEEDED);
        assertThat(current.outputPayload()).contains("Valid answer delivered right on time");
        assertThat(current.inputTokenCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("Concurrent workers process stale runs without conflict using FOR UPDATE SKIP LOCKED")
    void concurrentRecoveryWorkers_processWithoutConflict() throws Exception {
        Instant now = Instant.now();
        Instant staleTime = now.minusSeconds(300);

        // Insert 10 stale runs
        for (int i = 0; i < 10; i++) {
            runRepository.create(AiRun.builder()
                    .requestedBy(USER_ID)
                    .requestType("F01_GENERAL_KNOWLEDGE")
                    .status(AiRunStatus.RUNNING)
                    .purpose("BUSINESS_ASSISTANCE")
                    .evaluationOnly(false)
                    .inputPayload("{\"question\": \"Concurrent batch " + i + "\"}")
                    .queuedAt(staleTime)
                    .startedAt(staleTime)
                    .createdAt(staleTime)
                    .build());
        }

        Clock testClock = Clock.fixed(now, ZoneId.of("UTC"));
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryService recoveryService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Integer> task1 = recoveryService::executeRecoveryBatch;
        Callable<Integer> task2 = recoveryService::executeRecoveryBatch;

        List<Future<Integer>> results = executor.invokeAll(List.of(task1, task2));
        int totalReconciled = 0;
        for (Future<Integer> f : results) {
            totalReconciled += f.get();
        }
        executor.shutdown();

        // All 10 runs were reconciled, no deadlocks, no exceptions
        assertThat(totalReconciled).isEqualTo(10);
    }
}
