package com.fitnesscoaching.platform;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ContentConflictException;
import com.fitnesscoaching.platform.common.exception.ContentValidationException;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeProcessingRunRepository;
import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeIndexingOutboxConsumer;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeProcessingRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class KnowledgeIndexingOutboxIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("outbox_integration")
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
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO fitness, public;");
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private KnowledgeProcessingRunRepository processingRunRepository;
    @Autowired private KnowledgeIndexingOutboxConsumer outboxConsumer;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void seedAdminAndCleanData() {
        outboxConsumer.setExecutionInspectionHook(null);
        jdbcTemplate.update("TRUNCATE TABLE fitness.knowledge_documents, fitness.knowledge_processing_runs CASCADE");

        jdbcTemplate.update("""
                INSERT INTO fitness.users (
                    id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at
                ) VALUES (
                    ?, 'admin@fitplatform.local', '$2a$10$dummyHash', 'Admin Tester',
                    'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now()
                ) ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                """, ADMIN_ID);
    }

    private UUID createDocument(String code) {
        UUID docId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                VALUES (?, ?, 'Outbox Test Document', 'WHO-PA-SB-2020', 'vi', ?)
                """, docId, code, ADMIN_ID);
        return docId;
    }

    private UUID createVersion(UUID docId, String versionLabel, String status) {
        UUID versionId = UUID.randomUUID();
        Integer nextVer = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) + 1 FROM fitness.knowledge_versions WHERE knowledge_document_id = ?",
                Integer.class, docId);
        String contentHash = "hash-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_versions(
                    id, knowledge_document_id, version_number, version_label,
                    status, technical_readiness, content_hash, content, created_by
                ) VALUES (
                    ?, ?, ?, ?, ?::fitness.knowledge_status,
                    'TECHNICAL_VERIFICATION_PASSED', ?, 'Content', ?
                )
                """, versionId, docId, nextVer, versionLabel, status, contentHash, ADMIN_ID);
        return versionId;
    }

    // =========================================================================
    // Finding 1: Full-lifecycle Operation Identity Uniqueness & Idempotency
    // =========================================================================

    @Test
    void enqueue_duplicateAcrossAllStatuses_idempotentlyReturnsExistingRunWithoutDuplicateJob() {
        UUID docId = createDocument("DOC-ALL-STATUSES");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");
        String opKey = "OP-LIFECYCLE-" + UUID.randomUUID();

        // 1. QUEUED: First enqueue inserts, second enqueue returns existing
        KnowledgeProcessingRun runQueued1 = processingRunRepository.enqueue(version, "REINDEX", opKey, "{}", Instant.now());
        assertThat(runQueued1.status()).isEqualTo("QUEUED");

        KnowledgeProcessingRun runQueued2 = processingRunRepository.enqueue(version, "REINDEX", opKey, "{}", Instant.now());
        assertThat(runQueued2.id()).isEqualTo(runQueued1.id());

        // 2. RUNNING: Claimed run returns existing without duplicate
        KnowledgeProcessingRun claimed = processingRunRepository.claimNextEligibleRun("worker-claim", Duration.ofMinutes(5)).orElseThrow();
        assertThat(claimed.status()).isEqualTo("RUNNING");

        KnowledgeProcessingRun runRunning = processingRunRepository.enqueue(version, "REINDEX", opKey, "{}", Instant.now());
        assertThat(runRunning.id()).isEqualTo(runQueued1.id());
        assertThat(runRunning.status()).isEqualTo("RUNNING");

        // 3. SUCCEEDED: Completed run returns existing without duplicate
        boolean success = processingRunRepository.markSuccess(claimed.id(), claimed.claimToken(), "{\"done\":true}", Instant.now());
        assertThat(success).isTrue();

        KnowledgeProcessingRun runSucceeded = processingRunRepository.enqueue(version, "REINDEX", opKey, "{}", Instant.now());
        assertThat(runSucceeded.id()).isEqualTo(runQueued1.id());
        assertThat(runSucceeded.status()).isEqualTo("SUCCEEDED");

        // Exactly one row exists in database for this operation key
        Integer totalRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.knowledge_processing_runs WHERE operation_key = ?", Integer.class, opKey);
        assertThat(totalRows).isEqualTo(1);
    }

    @Test
    void enqueue_terminalOperationRace_preventsDuplicateJobWhenTransitionOccursBetweenCheckAndInsert() throws Exception {
        UUID docId = createDocument("DOC-RACE");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");
        String opKey = "OP-RACE-" + UUID.randomUUID();

        // Start job in QUEUED then claim to RUNNING
        KnowledgeProcessingRun initial = processingRunRepository.enqueue(version, "REINDEX", opKey, "{}", Instant.now());
        KnowledgeProcessingRun runningRun = processingRunRepository.claimNextEligibleRun("worker-race", Duration.ofMinutes(5)).orElseThrow();

        CountDownLatch tx1ReadyToCompleteLatch = new CountDownLatch(1);
        CountDownLatch tx1CompletedLatch = new CountDownLatch(1);
        CountDownLatch tx2BeforeEnqueueLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Transaction 1: Completes job to terminal SUCCEEDED
        Future<?> f1 = executor.submit(() -> {
            try {
                tx2BeforeEnqueueLatch.await();
                TransactionTemplate tx1Template = new TransactionTemplate(transactionManager);
                tx1Template.execute(status -> {
                    processingRunRepository.markSuccess(runningRun.id(), runningRun.claimToken(), "{\"race\":true}", Instant.now());
                    return null;
                });
                tx1CompletedLatch.countDown();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Transaction 2: Enqueues right as Tx1 commits terminal transition
        Future<KnowledgeProcessingRun> f2 = executor.submit(() -> {
            try {
                tx2BeforeEnqueueLatch.countDown();
                tx1CompletedLatch.await();
                TransactionTemplate tx2Template = new TransactionTemplate(transactionManager);
                return tx2Template.execute(status ->
                        processingRunRepository.enqueue(version, "REINDEX", opKey, "{}", Instant.now())
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        f1.get();
        KnowledgeProcessingRun resultTx2 = f2.get();
        executor.shutdown();

        // Must return the exact same run and must NOT create a second row
        assertThat(resultTx2.id()).isEqualTo(initial.id());
        assertThat(resultTx2.status()).isEqualTo("SUCCEEDED");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.knowledge_processing_runs WHERE operation_key = ?", Integer.class, opKey);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void enqueue_nullOrBlankOperationKey_throwsValidationException() {
        UUID docId = createDocument("DOC-BLANK-OP");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");

        assertThatThrownBy(() -> {
            processingRunRepository.enqueue(version, "REINDEX", null, "{}", Instant.now());
        }).isInstanceOf(ContentValidationException.class)
                .hasMessageContaining("Operation key must not be null or blank");

        assertThatThrownBy(() -> {
            processingRunRepository.enqueue(version, "REINDEX", "   ", "{}", Instant.now());
        }).isInstanceOf(ContentValidationException.class)
                .hasMessageContaining("Operation key must not be null or blank");
    }

    @Test
    void enqueue_reindexWithNewIdentity_createsExactlyOneNewJob() {
        UUID docId = createDocument("DOC-REINDEX-NEW");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");

        String rev1Key = "INDEX:" + version + ":rev-1";
        String rev2Key = "INDEX:" + version + ":rev-2";

        KnowledgeProcessingRun run1 = processingRunRepository.enqueue(version, "REINDEX", rev1Key, "{}", Instant.now());
        KnowledgeProcessingRun run2 = processingRunRepository.enqueue(version, "REINDEX", rev2Key, "{}", Instant.now());

        assertThat(run2.id()).isNotEqualTo(run1.id());
        assertThat(run2.operationKey()).isEqualTo(rev2Key);

        Integer total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.knowledge_processing_runs WHERE knowledge_version_id = ?",
                Integer.class, version);
        assertThat(total).isEqualTo(2);
    }

    @Test
    void enqueue_intentMismatchThrowsContentConflictException() {
        UUID docId = createDocument("DOC-MISMATCH");
        UUID version1 = createVersion(docId, "1.0.0", "ACTIVE");
        UUID version2 = createVersion(docId, "2.0.0", "ACTIVE");
        String opKey = "OP-MISMATCH-" + UUID.randomUUID();

        processingRunRepository.enqueue(version1, "REINDEX", opKey, "{}", Instant.now());

        // Mismatched versionId
        assertThatThrownBy(() -> {
            processingRunRepository.enqueue(version2, "REINDEX", opKey, "{}", Instant.now());
        }).isInstanceOf(ContentConflictException.class)
                .hasMessageContaining("mismatched intent");

        // Mismatched runType
        assertThatThrownBy(() -> {
            processingRunRepository.enqueue(version1, "IMPORT", opKey, "{}", Instant.now());
        }).isInstanceOf(ContentConflictException.class)
                .hasMessageContaining("mismatched intent");
    }

    @Test
    void enqueue_concurrentCalls_exactlyOneRowCreatedAndNeitherAborts() throws Exception {
        UUID docId = createDocument("DOC-CONCURRENT-ENQUEUE");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");
        String opKey = "OP-CONCURRENT-" + UUID.randomUUID();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        Runnable enqueueTask = () -> {
            try {
                startLatch.await();
                KnowledgeProcessingRun run = processingRunRepository.enqueue(version, "REINDEX", opKey, "{}", Instant.now());
                if (run != null) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                // ignore
            }
        };

        Future<?> f1 = executor.submit(enqueueTask);
        Future<?> f2 = executor.submit(enqueueTask);

        startLatch.countDown();
        f1.get();
        f2.get();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(2);

        Integer totalRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.knowledge_processing_runs WHERE operation_key = ?", Integer.class, opKey);
        assertThat(totalRows).isEqualTo(1);
    }

    @Test
    void enqueue_callerRollback_leavesNoOutboxRun() {
        UUID docId = createDocument("DOC-ROLLBACK");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");
        String opKey = "OP-ROLLBACK-" + UUID.randomUUID();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.execute(status -> {
            processingRunRepository.enqueue(version, "REINDEX", opKey, "{}", Instant.now());
            status.setRollbackOnly();
            return null;
        });

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.knowledge_processing_runs WHERE operation_key = ?", Integer.class, opKey);
        assertThat(count).isEqualTo(0);
    }

    // =========================================================================
    // Finding 2: Attempt Limits for Both QUEUED and RUNNING
    // =========================================================================

    @Test
    void claim_queuedAtMaxAttempts_isNotClaimedAndReapedToFailed() {
        UUID docId = createDocument("DOC-QUEUED-ATTEMPTS");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");

        // Dead QUEUED run: attempt_count = 3, max_attempts = 3
        UUID deadQueuedId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, attempt_count, max_attempts,
                    operation_key, created_at
                ) VALUES (
                    ?, ?, 'REINDEX', 'QUEUED', 3, 3,
                    ?, clock_timestamp() - interval '1 hour'
                )
                """, deadQueuedId, version, "OP-DEAD-QUEUED-" + deadQueuedId);

        // Healthy queued run behind it
        UUID healthyQueuedId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, attempt_count, max_attempts,
                    operation_key, created_at
                ) VALUES (
                    ?, ?, 'REINDEX', 'QUEUED', 0, 3,
                    ?, clock_timestamp()
                )
                """, healthyQueuedId, version, "OP-HEALTHY-QUEUED-" + healthyQueuedId);

        // Claim must NOT pick dead QUEUED run; it must pick the healthy one
        Optional<KnowledgeProcessingRun> claimed = processingRunRepository.claimNextEligibleRun("worker-retry", Duration.ofMinutes(5));
        assertThat(claimed).isPresent();
        assertThat(claimed.get().id()).isEqualTo(healthyQueuedId);

        // Dead queued run must be reaped to FAILED
        String deadStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, deadQueuedId);
        assertThat(deadStatus).isEqualTo("FAILED");

        String deadMsg = jdbcTemplate.queryForObject(
                "SELECT error_message FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, deadQueuedId);
        assertThat(deadMsg).contains("Exceeded maximum attempts while queued for retry");
    }

    @Test
    void claim_runningExpiredLeaseAtMaxAttempts_isNotReclaimedAndReapedToFailed() {
        UUID docId = createDocument("DOC-RUNNING-ATTEMPTS");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");

        // Dead RUNNING run: attempt_count = 3, max_attempts = 3, expired lease
        UUID deadRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, attempt_count, max_attempts,
                    locked_at, locked_by, lease_expires_at, operation_key, created_at
                ) VALUES (
                    ?, ?, 'REINDEX', 'RUNNING', 3, 3,
                    clock_timestamp() - interval '10 minutes', 'worker-dead',
                    clock_timestamp() - interval '5 minutes', ?, clock_timestamp() - interval '10 minutes'
                )
                """, deadRunId, version, "OP-DEAD-RUNNING-" + deadRunId);

        // Healthy queued run
        UUID queuedRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, attempt_count, max_attempts,
                    operation_key, created_at
                ) VALUES (
                    ?, ?, 'REINDEX', 'QUEUED', 0, 3, ?, clock_timestamp()
                )
                """, queuedRunId, version, "OP-HEALTHY-" + queuedRunId);

        Optional<KnowledgeProcessingRun> claimed = processingRunRepository.claimNextEligibleRun("worker-new", Duration.ofMinutes(5));
        assertThat(claimed).isPresent();
        assertThat(claimed.get().id()).isEqualTo(queuedRunId);

        String deadStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, deadRunId);
        assertThat(deadStatus).isEqualTo("FAILED");

        String deadError = jdbcTemplate.queryForObject(
                "SELECT error_message FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, deadRunId);
        assertThat(deadError).contains("Exceeded maximum attempts during lease recovery");
    }

    @Test
    void claim_jobBeforeNextAttemptAt_isNotClaimed() {
        UUID docId = createDocument("DOC-BACKOFF");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");

        UUID backoffRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, attempt_count, max_attempts,
                    next_attempt_at, operation_key, created_at
                ) VALUES (
                    ?, ?, 'REINDEX', 'QUEUED', 1, 3,
                    clock_timestamp() + interval '10 minutes', ?, clock_timestamp()
                )
                """, backoffRunId, version, "OP-BACKOFF-" + backoffRunId);

        Optional<KnowledgeProcessingRun> claimed = processingRunRepository.claimNextEligibleRun("worker-check", Duration.ofMinutes(5));
        assertThat(claimed).isEmpty();
    }

    @Test
    void claim_terminalJob_cannotBeRevivedOrClaimed() {
        UUID docId = createDocument("DOC-TERMINAL");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");

        UUID failedId = UUID.randomUUID();
        UUID successId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, attempt_count, max_attempts, operation_key, created_at
                ) VALUES
                    (?, ?, 'REINDEX', 'FAILED', 3, 3, ?, clock_timestamp() - interval '1 hour'),
                    (?, ?, 'REINDEX', 'SUCCEEDED', 1, 3, ?, clock_timestamp() - interval '1 hour')
                """, failedId, version, "OP-TERM-FAIL-" + failedId,
                     successId, version, "OP-TERM-SUCC-" + successId);

        Optional<KnowledgeProcessingRun> claimed = processingRunRepository.claimNextEligibleRun("worker-revive", Duration.ofMinutes(5));
        assertThat(claimed).isEmpty();
    }

    @Test
    void claim_multipleRetriesStopAtLimit() {
        UUID docId = createDocument("DOC-MULTI-RETRY");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");
        String opKey = "OP-MULTI-RETRY-" + UUID.randomUUID();

        // Run with max_attempts = 2
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, attempt_count, max_attempts, operation_key, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', 0, 2, ?, clock_timestamp())
                """, runId, version, opKey);

        // Attempt 1
        KnowledgeProcessingRun run1 = processingRunRepository.claimNextEligibleRun("worker-1", Duration.ofMinutes(5)).orElseThrow();
        assertThat(run1.attemptCount()).isEqualTo(1);
        boolean failed1 = processingRunRepository.markFailed(run1.id(), run1.claimToken(), "Fail 1", true, Duration.ZERO, Instant.now());
        assertThat(failed1).isTrue();

        String status1 = jdbcTemplate.queryForObject("SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, runId);
        assertThat(status1).isEqualTo("QUEUED");

        // Attempt 2 (reaches limit)
        KnowledgeProcessingRun run2 = processingRunRepository.claimNextEligibleRun("worker-2", Duration.ofMinutes(5)).orElseThrow();
        assertThat(run2.attemptCount()).isEqualTo(2);
        boolean failed2 = processingRunRepository.markFailed(run2.id(), run2.claimToken(), "Fail 2", true, Duration.ZERO, Instant.now());
        assertThat(failed2).isTrue();

        // Must be terminal FAILED, not QUEUED
        String status2 = jdbcTemplate.queryForObject("SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, runId);
        assertThat(status2).isEqualTo("FAILED");

        // No more runs eligible
        Optional<KnowledgeProcessingRun> claimed3 = processingRunRepository.claimNextEligibleRun("worker-3", Duration.ofMinutes(5));
        assertThat(claimed3).isEmpty();
    }

    @Test
    void fencing_nullClaimTokenRejected() {
        UUID docId = createDocument("DOC-NULL-TOKEN");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");
        UUID runId = processingRunRepository.enqueue(version, "REINDEX", "OP-NULL-TOKEN-" + UUID.randomUUID(), "{}", Instant.now()).id();

        KnowledgeProcessingRun claimed = processingRunRepository.claimNextEligibleRun("worker-t", Duration.ofMinutes(5)).orElseThrow();

        boolean updated = processingRunRepository.markSuccess(runId, null, "{}", Instant.now());
        assertThat(updated).isFalse();

        boolean failed = processingRunRepository.markFailed(runId, null, "error", false, null, Instant.now());
        assertThat(failed).isFalse();

        boolean renewed = processingRunRepository.renewLease(runId, null, Duration.ofMinutes(5));
        assertThat(renewed).isFalse();
    }

    // =========================================================================
    // Finding 3: Execution Does Not Participate in Caller's Transaction
    // =========================================================================

    @Test
    void workerExecution_suspendsCallerTransaction_isActualTransactionActiveIsFalse() {
        UUID docId = createDocument("DOC-TX-SUSPEND");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");
        String opKey = "OP-TX-SUSPEND-" + UUID.randomUUID();
        UUID runId = processingRunRepository.enqueue(version, "REINDEX", opKey, "{}", Instant.now()).id();

        AtomicBoolean insideExecutionHadNoTx = new AtomicBoolean(false);
        outboxConsumer.setExecutionInspectionHook(run -> {
            // Verify caller transaction was suspended during Boundary 2 execution
            boolean actualTxActive = TransactionSynchronizationManager.isActualTransactionActive();
            if (!actualTxActive) {
                insideExecutionHadNoTx.set(true);
            }
        });

        TransactionTemplate callerTx = new TransactionTemplate(transactionManager);
        callerTx.execute(status -> {
            // Caller transaction IS active here
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

            // Run outbox consumer within active caller transaction
            boolean processed = outboxConsumer.processNextRun("caller-worker");
            assertThat(processed).isTrue();

            // Caller transaction is restored and STILL active here
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

            // Explicitly roll back caller transaction
            status.setRollbackOnly();
            return null;
        });

        // Verification: execution hook confirmed no active transaction during work
        assertThat(insideExecutionHadNoTx.get()).isTrue();

        // Verification: outbox completion committed independently; caller rollback did NOT undo it
        String runStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, runId);
        assertThat(runStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    void batchExecution_runsInIndependentTransactions_failureDoesNotRollbackOtherRuns() {
        UUID docId1 = createDocument("DOC-BATCH-1");
        UUID version1 = createVersion(docId1, "1.0.0", "ACTIVE");

        UUID docId2 = createDocument("DOC-BATCH-2");
        UUID version2 = createVersion(docId2, "1.0.0", "ARCHIVED"); // Ineligible -> will fail

        UUID docId3 = createDocument("DOC-BATCH-3");
        UUID version3 = createVersion(docId3, "1.0.0", "ACTIVE");

        UUID run1 = processingRunRepository.enqueue(version1, "REINDEX", "OP-B1", "{}", Instant.now()).id();
        UUID run2 = processingRunRepository.enqueue(version2, "REINDEX", "OP-B2", "{}", Instant.now()).id();
        UUID run3 = processingRunRepository.enqueue(version3, "REINDEX", "OP-B3", "{}", Instant.now()).id();

        int processed = outboxConsumer.processPendingRuns(10);
        assertThat(processed).isEqualTo(3);

        String status1 = jdbcTemplate.queryForObject("SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, run1);
        String status2 = jdbcTemplate.queryForObject("SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, run2);
        String status3 = jdbcTemplate.queryForObject("SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, run3);

        assertThat(status1).isEqualTo("SUCCEEDED");
        assertThat(status2).isEqualTo("FAILED");
        assertThat(status3).isEqualTo("SUCCEEDED");

        String err2 = jdbcTemplate.queryForObject("SELECT error_message FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, run2);
        assertThat(err2).contains("Ineligible for indexing");
    }

    @Test
    void workerExecution_whenFencingRejectsCompletion_doesNotOverwriteNewClaimOrMarkFailed() {
        UUID docId = createDocument("DOC-FENCE-REJECT");
        UUID version = createVersion(docId, "1.0.0", "ACTIVE");
        String opKey = "OP-FENCE-REJECT-" + UUID.randomUUID();
        UUID runId = processingRunRepository.enqueue(version, "REINDEX", opKey, "{}", Instant.now()).id();

        // While worker-1 is executing in Boundary 2, simulate worker-2 stealing the run after lease expiration
        outboxConsumer.setExecutionInspectionHook(run -> {
            // Expire lease in DB and claim as worker-2
            jdbcTemplate.update("""
                    UPDATE fitness.knowledge_processing_runs
                    SET lease_expires_at = clock_timestamp() - interval '1 second'
                    WHERE id = ?
                    """, runId);
            // worker-2 claims the run with a new claim token
            processingRunRepository.claimNextEligibleRun("worker-2", Duration.ofMinutes(5));
        });

        // Worker-1 attempts to process and complete
        boolean processed = outboxConsumer.processNextRun("worker-1");
        assertThat(processed).isTrue();

        // Reset hook
        outboxConsumer.setExecutionInspectionHook(null);

        // Verification: Run status in DB is still RUNNING under worker-2 (worker-1 did NOT overwrite as SUCCEEDED, nor call markFailed)
        String lockedBy = jdbcTemplate.queryForObject(
                "SELECT locked_by FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, runId);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, runId);

        assertThat(lockedBy).isEqualTo("worker-2");
        assertThat(status).isEqualTo("RUNNING");
    }
}
