package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.scheduled.AiRunOrphanRecoveryScheduler;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiOrphanRecoveryProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiRunF01UseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CompleteF01AiRunCommand;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private AiRunF01UseCase aiRunUseCase;

    @Autowired
    private AiRunOrphanRecoveryService recoveryService;

    @Autowired
    private AiRunOrphanRecoveryScheduler recoveryScheduler;

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
    @DisplayName("Scheduler invocation passes through Spring transactional proxy to executeRecoveryBatch")
    void schedulerInvocation_passesThroughTransactionalProxy() {
        Instant now = Instant.now();
        Instant staleStartedAt = now.minusSeconds(300);

        AiRun run = runRepository.create(AiRun.builder()
                .requestedBy(USER_ID)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .status(AiRunStatus.RUNNING)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .inputPayload("{\"question\": \"Test scheduler proxy\"}")
                .queuedAt(staleStartedAt.minusSeconds(5))
                .startedAt(staleStartedAt)
                .createdAt(staleStartedAt.minusSeconds(5))
                .build());

        // Invoke via the scheduler component as triggered by @Scheduled in production
        int count = recoveryScheduler.runScheduledRecovery();
        assertThat(count).isGreaterThanOrEqualTo(1);

        AiRun updated = runRepository.findById(run.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(AiRunStatus.FAILED);
        assertThat(updated.errorCode()).isEqualTo("TIMEOUT");
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
        AiRunOrphanRecoveryService isolatedService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);

        int count = isolatedService.executeRecoveryBatch();
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
        AiRunOrphanRecoveryService isolatedService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);

        int count = isolatedService.executeRecoveryBatch();
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
        AiRunOrphanRecoveryService isolatedService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);

        int count = isolatedService.executeRecoveryBatch();
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
        AiRunOrphanRecoveryService isolatedService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);

        int count = isolatedService.executeRecoveryBatch();
        assertThat(count).isZero();

        AiRun current = runRepository.findById(run.id()).orElseThrow();
        assertThat(current.status()).isEqualTo(AiRunStatus.SUCCEEDED);
        assertThat(current.outputPayload()).isEqualTo("{\"answer\": \"Old correct answer\"}");
        assertThat(current.errorCode()).isNull();
    }

    @Test
    @DisplayName("Race condition (Direction 1: Worker completes first) - cleaner cannot overwrite terminal result")
    void raceCondition_workerCompletesFirst_cleanerDoesNotOverwrite() {
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
    @DisplayName("Race condition (Direction 2: Recovery completes first) - late completion throws Conflict and preserves FAILED")
    void raceCondition_recoveryCompletesFirst_lateCompletionThrowsConflictAndPreservesFailed() {
        Instant now = Instant.now();
        Instant staleTime = now.minusSeconds(300);

        // 1. Request AI started and entered RUNNING
        AiRun run = runRepository.create(AiRun.builder()
                .requestedBy(USER_ID)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .status(AiRunStatus.RUNNING)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .inputPayload("{\"question\": \"Query whose provider is slow\"}")
                .queuedAt(staleTime.minusSeconds(2))
                .startedAt(staleTime)
                .createdAt(staleTime.minusSeconds(2))
                .build());

        // 2. Recovery sweeps and reconciles run to FAILED with TIMEOUT
        Clock testClock = Clock.fixed(now, ZoneId.of("UTC"));
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryService isolatedService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);
        int reconciled = isolatedService.executeRecoveryBatch();
        assertThat(reconciled).isEqualTo(1);

        AiRun reconciledRun = runRepository.findById(run.id()).orElseThrow();
        assertThat(reconciledRun.status()).isEqualTo(AiRunStatus.FAILED);
        assertThat(reconciledRun.errorCode()).isEqualTo("TIMEOUT");

        // 3. Provider/Orchestrator late completion arrives
        CompleteF01AiRunCommand lateCommand = new CompleteF01AiRunCommand(
                run.id(),
                AiRunStatus.SUCCEEDED,
                "{\"answer\": \"Late arriving answer from provider\"}",
                null,
                null,
                120,
                65,
                185,
                35000,
                now
        );

        // 4. Verification: completeF01Run throws AiRunLifecycleConflictException
        assertThatThrownBy(() -> aiRunUseCase.completeF01Run(lateCommand))
                .isInstanceOf(AiRunLifecycleConflictException.class)
                .hasMessageContaining("already completed with status FAILED");

        // 5. Verification: Database terminal state remains FAILED and was NOT overwritten
        AiRun finalRun = runRepository.findById(run.id()).orElseThrow();
        assertThat(finalRun.status()).isEqualTo(AiRunStatus.FAILED);
        assertThat(finalRun.errorCode()).isEqualTo("TIMEOUT");
        assertThat(finalRun.outputPayload()).isNull(); // Late answer was NOT written
        assertThat(finalRun.inputTokenCount()).isNull(); // Late tokens were NOT applied
        assertThat(finalRun.outputTokenCount()).isNull();
    }

    @Test
    @DisplayName("Unknown token usage and cost: Preserved as NULL and never fabricated as zero")
    void unknownUsageAndCost_remainsNullAndNeverFabricatedAsZero() {
        Instant now = Instant.now();
        Instant staleTime = now.minusSeconds(250);

        AiRun run = runRepository.create(AiRun.builder()
                .requestedBy(USER_ID)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .status(AiRunStatus.RUNNING)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .inputPayload("{\"question\": \"Orphan with unknown tokens\"}")
                .queuedAt(staleTime)
                .startedAt(staleTime)
                .createdAt(staleTime)
                .build());

        // Initial in-flight tokens are unknown (NULL)
        assertThat(run.inputTokenCount()).isNull();
        assertThat(run.outputTokenCount()).isNull();

        Clock testClock = Clock.fixed(now, ZoneId.of("UTC"));
        AiOrphanRecoveryProperties properties = new AiOrphanRecoveryProperties(true, 120, 60000, 10000, 50);
        AiRunOrphanRecoveryService isolatedService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);
        isolatedService.executeRecoveryBatch();

        // After reconciliation, tokens must strictly remain NULL (unknown, never fabricated 0)
        AiRun recovered = runRepository.findById(run.id()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(AiRunStatus.FAILED);
        assertThat(recovered.inputTokenCount()).isNull();
        assertThat(recovered.outputTokenCount()).isNull();
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
        AiRunOrphanRecoveryService isolatedService = new AiRunOrphanRecoveryService(runRepository, properties, testClock);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Integer> task1 = isolatedService::executeRecoveryBatch;
        Callable<Integer> task2 = isolatedService::executeRecoveryBatch;

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
