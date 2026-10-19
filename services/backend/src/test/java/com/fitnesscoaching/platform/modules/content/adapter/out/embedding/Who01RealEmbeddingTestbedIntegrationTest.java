package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.content.application.port.in.ImportKnowledgeCorpusUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import com.fitnesscoaching.platform.modules.content.application.service.ContentLifecycleService;
import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeChunker;
import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeIndexingOutboxConsumer;
import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeRetrievalService;
import com.fitnesscoaching.platform.modules.content.domain.ImportCorpusBundle;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolated Testbed Integration Test for WHO-01.
 * Targets the local isolated testbed database at localhost:5433 (digital-fitness-postgres).
 *
 * <p>Execution Guard:
 * This class requires {@code RUN_LIVE_OPENAI_TESTS=true} via {@link EnabledIfEnvironmentVariable}.
 * By applying this condition at class level, JUnit Jupiter disables the container before
 * Spring TestContext framework attempts to boot {@link SpringBootTest} or connect to
 * {@code localhost:5433}. This ensures 100% CI isolation while preserving local testbed execution.
 *
 * <p>Offline chunking verification has also been extracted to {@link Who01KnowledgeChunkingOfflineTest}
 * which runs unconditionally in standard CI builds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_OPENAI_TESTS", matches = "(?i)true")
@DisplayName("WHO-01 Real Embedding Testbed Integration Test")
class Who01RealEmbeddingTestbedIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(Who01RealEmbeddingTestbedIntegrationTest.class);

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String DOCUMENT_CODE = "WHO-PA-SB-2020-VI-01";
    private static final String VERSION_LABEL = "1.1.0";
    private static final String SOURCE_ID = "WHO-PA-SB-2020";

    private static String resolveDbPassword() {
        String pw = System.getenv("DATABASE_PASSWORD");
        if (pw != null && !pw.isBlank()) return pw.trim();
        pw = System.getenv("POSTGRES_PASSWORD");
        if (pw != null && !pw.isBlank()) return pw.trim();
        List<Path> envFiles = List.of(
                Path.of(".env"),
                Path.of("..", "..", ".env")
        );
        for (Path p : envFiles) {
            if (Files.exists(p)) {
                try {
                    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("DATABASE_PASSWORD=")) {
                            return trimmed.substring("DATABASE_PASSWORD=".length()).trim();
                        } else if (trimmed.startsWith("POSTGRES_PASSWORD=")) {
                            return trimmed.substring("POSTGRES_PASSWORD=".length()).trim();
                        }
                    }
                } catch (IOException ignored) {}
            }
        }
        return "123456";
    }

    private static String readEnvProperty(String keyName) {
        String val = System.getenv(keyName);
        if (val != null && !val.isBlank()) return val.trim();
        List<Path> secretFiles = List.of(
                Path.of(".env.secrets"),
                Path.of("..", "..", ".env.secrets"),
                Path.of(".env.local"),
                Path.of("..", "..", ".env.local"),
                Path.of(".env"),
                Path.of("..", "..", ".env")
        );
        for (Path p : secretFiles) {
            if (Files.exists(p)) {
                try {
                    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith(keyName + "=")) {
                            String v = trimmed.substring((keyName + "=").length()).trim();
                            if (!v.isBlank()) return v;
                        }
                    }
                } catch (IOException ignored) {}
            }
        }
        return null;
    }

    private static String readSecretKey() {
        String key = readEnvProperty("AI_EMBEDDING_API_KEY");
        if (key != null && !key.isBlank()) return key;
        return readEnvProperty("OPENAI_API_KEY");
    }

    private static boolean isLiveExplicitlyRequestedAndConfigured() {
        String flag = readEnvProperty("RUN_LIVE_OPENAI_TESTS");
        if (flag == null || !flag.equalsIgnoreCase("true")) {
            return false;
        }
        String key = readSecretKey();
        return key != null && !key.isBlank();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String dbPassword = resolveDbPassword();
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5433/digital_fitness");
        registry.add("spring.datasource.username", () -> "fitness_app");
        registry.add("spring.datasource.password", () -> dbPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO fitness, public;");

        if (isLiveExplicitlyRequestedAndConfigured()) {
            registry.add("ai.embedding.provider", () -> "openai");
            registry.add("ai.embedding.openai.api-key", () -> readSecretKey());
            registry.add("ai.embedding.openai.base-url", () -> "https://api.openai.com");
        } else {
            registry.add("ai.embedding.provider", () -> "simulated");
        }
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ImportKnowledgeCorpusUseCase importCorpusUseCase;
    @Autowired private ContentLifecycleService lifecycleService;
    @Autowired private KnowledgeIndexingOutboxConsumer outboxConsumer;
    @Autowired private KnowledgeRetrievalService retrievalService;
    @Autowired private KnowledgeChunker chunker;
    @Autowired private EmbeddingModelPort embeddingModelPort;
    @Autowired private ObjectMapper objectMapper;
    @Autowired(required = false) private ScheduledTaskHolder scheduledTaskHolder;

    private static Path resolveFile(String relative) {
        List<Path> candidates = List.of(
                Path.of("..", "..", relative),
                Path.of(relative),
                Path.of("data", "knowledge", relative),
                Path.of("..", "..", "data", "knowledge", relative)
        );
        for (Path c : candidates) {
            if (Files.exists(c)) return c;
        }
        throw new IllegalStateException("Required file not found on disk: " + relative);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Test 1 (Offline): Verify WHO-01 source file, hash, and deterministic chunking")
    void testWho01OfflineChunkingAndReadiness() throws IOException {
        Path contentPath = resolveFile("derivatives/who-2020/01-scope.vi.md");
        String content = Files.readString(contentPath, StandardCharsets.UTF_8);
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String contentHash = sha256Hex(contentBytes);

        assertThat(contentHash).isEqualTo("5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840");

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(UUID.randomUUID())
                .documentCode(DOCUMENT_CODE)
                .sourceId(SOURCE_ID)
                .title("Phạm vi và cách sử dụng WHO 2020")
                .build();

        KnowledgeVersion ver = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeDocumentId(doc.id())
                .versionLabel(VERSION_LABEL)
                .versionNumber(1)
                .status(KnowledgeStatus.DRAFT)
                .content(content)
                .contentHash(contentHash)
                .contentPath("derivatives/who-2020/01-scope.vi.md")
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .lineageBaseDocumentId(DOCUMENT_CODE)
                .lineageBaseVersion("1.0.0")
                .lineageBaseHash("83f7921e04986d1d3843ceb5d07d7911aac0c93dd81b87ab6f4659fae42277c1")
                .build();

        Instant now = Instant.now();
        List<KnowledgeChunk> chunks = chunker.chunk(ver, doc, now);

        assertThat(chunks).isNotEmpty();
        log.info("WHO-01 Chunking verified offline: chunk_count={}, bytes={}, hash={}",
                chunks.size(), contentBytes.length, contentHash);

        for (KnowledgeChunk c : chunks) {
            log.info("Chunk {}: headingPath='{}', tokenCount={}, length={}",
                    c.chunkIndex(), c.headingPath(), c.tokenCount(), c.content().length());
            assertThat(c.content()).isNotBlank();
            assertThat(c.chunkIndex()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("Test 2 (Controlled Live): Index WHO-01 with OpenAI embeddings and verify retrieval")
    void testWho01LiveIndexingAndRetrievalWithOpenAi() throws Exception {
        Assumptions.assumeTrue(isLiveExplicitlyRequestedAndConfigured(),
                "SKIPPED: Live OpenAI test requires BOTH RUN_LIVE_OPENAI_TESTS=true AND a valid API key. " +
                "Offline safety preserved: no external API called and no database mutations executed.");

        log.info("Starting controlled WHO-01 indexing & retrieval test with real OpenAI embeddings...");

        // 1. Verify target database is isolated local testbed before any writes
        Integer serverPort = jdbcTemplate.queryForObject("SELECT inet_server_port()", Integer.class);
        String currentDb = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
        log.info("Verifying target database: port={}, database={}, schema={}", serverPort, currentDb, currentSchema);
        assertThat(currentDb).as("Target database must be digital_fitness").isEqualTo("digital_fitness");
        assertThat(currentSchema).as("Target schema must be fitness").isEqualTo("fitness");

        // 2. Independently verify that automatic worker and scheduler are stopped / disabled
        // A. Verify no scheduled tasks in Spring context execute outbox worker or consumer
        if (scheduledTaskHolder != null) {
            Set<ScheduledTask> scheduledTasks = scheduledTaskHolder.getScheduledTasks();
            for (ScheduledTask task : scheduledTasks) {
                String taskDesc = task.toString();
                assertThat(taskDesc)
                        .as("Outbox worker must NOT be registered as a scheduled task in Spring context")
                        .doesNotContain("KnowledgeIndexingOutbox")
                        .doesNotContain("processPendingRuns");
            }
            log.info("Verified Spring ScheduledTasks (count={}): no automatic outbox task is registered.", scheduledTasks.size());
        }

        // B. Verify no external process is querying knowledge_processing_runs on the database
        Integer externalWorkerQueries = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = 'digital_fitness'
                  AND pid <> pg_backend_pid()
                  AND application_name NOT LIKE '%PostgreSQL JDBC Driver%'
                  AND query ILIKE '%knowledge_processing_runs%'
                """, Integer.class);
        assertThat(externalWorkerQueries).as("No external worker process may be querying knowledge_processing_runs").isZero();

        // C. Verify no running jobs or active leases exist
        Integer runningJobs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.knowledge_processing_runs WHERE status = 'RUNNING'", Integer.class);
        assertThat(runningJobs).as("No jobs may have RUNNING status").isZero();

        Integer activelyLeasedRuns = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM fitness.knowledge_processing_runs
                WHERE status = 'RUNNING' AND lease_expires_at > clock_timestamp()
                """, Integer.class);
        assertThat(activelyLeasedRuns).as("No active worker lease may exist").isZero();

        // 3. Inspect existing WHO-01 state without destructive DELETE operations
        UUID docId = null;
        UUID versionId = null;
        List<UUID> existingDocIds = jdbcTemplate.query(
                "SELECT id FROM fitness.knowledge_documents WHERE document_code = ?",
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                DOCUMENT_CODE);

        if (!existingDocIds.isEmpty()) {
            docId = existingDocIds.get(0);
            List<UUID> existingVerIds = jdbcTemplate.query(
                    "SELECT id FROM fitness.knowledge_versions WHERE knowledge_document_id = ? AND version_label = ?",
                    (rs, rowNum) -> (UUID) rs.getObject("id"),
                    docId, VERSION_LABEL);
            if (!existingVerIds.isEmpty()) {
                versionId = existingVerIds.get(0);
            }
        }

        // 4. Queue eligibility check: fail-fast on foreign jobs, distinguish legitimate WHO-01 retry job
        List<Map<String, Object>> preExistingJobs = jdbcTemplate.queryForList("""
                SELECT id, knowledge_version_id, run_type, status, attempt_count, max_attempts, next_attempt_at, lease_expires_at
                FROM fitness.knowledge_processing_runs
                WHERE (status = 'QUEUED')
                   OR (status = 'RUNNING')
                """);

        final UUID targetVersionId = versionId;
        List<Map<String, Object>> foreignJobs = preExistingJobs.stream()
                .filter(job -> targetVersionId == null || !targetVersionId.equals(job.get("knowledge_version_id")))
                .toList();
        if (!foreignJobs.isEmpty()) {
            throw new IllegalStateException(
                    "Fail-fast: Pre-existing foreign claimable/pending/retry jobs found in fitness.knowledge_processing_runs (count=" +
                    foreignJobs.size() + "). Aborting testbed execution to prevent claiming or interfering with foreign jobs. " +
                    "Found jobs: " + foreignJobs);
        }

        List<Map<String, Object>> who01PendingJobs = preExistingJobs.stream()
                .filter(job -> targetVersionId != null && targetVersionId.equals(job.get("knowledge_version_id")))
                .toList();

        if (!who01PendingJobs.isEmpty()) {
            log.info("Queue eligibility check: Found {} legitimate WHO-01 pending/retry job(s): {}",
                    who01PendingJobs.size(), who01PendingJobs);
        } else {
            log.info("Queue eligibility pre-check PASSED: 0 claimable/pending/retry jobs in outbox prior to writes.");
        }

        // 5. Prepare Local Database Registry: activate OpenAI model and version
        jdbcTemplate.update("""
                UPDATE fitness.ai_models
                SET is_active = true
                WHERE id = 'e0000000-0000-0000-0000-000000000002'
                """);
        jdbcTemplate.update("""
                UPDATE fitness.ai_model_versions
                SET activated_at = COALESCE(activated_at, now())
                WHERE id = 'e1000000-0000-0000-0000-000000000002'
                """);

        // 6. Seed Admin user with CONTENT_ADMIN permission if not present
        jdbcTemplate.update("""
                INSERT INTO fitness.users (
                    id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at
                ) VALUES (
                    ?, 'admin@fitplatform.local', '$2a$10$dummyHash', 'Content Admin',
                    'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now()
                ) ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                """, ADMIN_ID);

        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp()
                FROM fitness.roles
                WHERE code IN ('ADMIN', 'CONTENT_ADMIN')
                ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL
                """, ADMIN_ID, ADMIN_ID);

        // 7. Ensure WHO-01 version is ready for indexing without creating duplicate jobs
        boolean needsIndexing = false;
        if (docId == null || versionId == null) {
            log.info("WHO-01 version {} does not exist in testbed. Importing bundle...", VERSION_LABEL);
            Path contentPath = resolveFile("derivatives/who-2020/01-scope.vi.md");
            String content = Files.readString(contentPath, StandardCharsets.UTF_8);
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            String contentHash = sha256Hex(contentBytes);

            String bundleJson = """
                    {
                      "formatVersion": 1,
                      "purpose": "F01_GENERAL_KNOWLEDGE",
                      "bundleName": "who01-live-testbed-bundle",
                      "items": [
                        {
                          "documentCode": "WHO-PA-SB-2020-VI-01",
                          "sourceId": "WHO-PA-SB-2020",
                          "title": "Phạm vi và cách sử dụng WHO 2020",
                          "sourceName": "World Health Organization",
                          "versionLabel": "1.1.0",
                          "versionNumber": 1,
                          "contentHash": "%s",
                          "contentPath": "derivatives/who-2020/01-scope.vi.md",
                          "sourceBytes": %d,
                          "reviewEvidenceId": "REV-PO-20261008-01",
                          "technicalReadiness": "TECHNICAL_VERIFICATION_PASSED",
                          "lineageBaseDocumentId": "WHO-PA-SB-2020-VI-01",
                          "lineageBaseVersion": "1.0.0",
                          "lineageBaseHash": "83f7921e04986d1d3843ceb5d07d7911aac0c93dd81b87ab6f4659fae42277c1",
                          "content": %s
                        }
                      ]
                    }
                    """.formatted(contentHash, contentBytes.length, objectMapper.writeValueAsString(content));

            ImportCorpusBundle bundle = objectMapper.readValue(bundleJson, ImportCorpusBundle.class);
            importCorpusUseCase.importBundle(ADMIN_ID, bundle);

            docId = jdbcTemplate.queryForObject(
                    "SELECT id FROM fitness.knowledge_documents WHERE document_code = ?", UUID.class, DOCUMENT_CODE);
            versionId = jdbcTemplate.queryForObject(
                    "SELECT id FROM fitness.knowledge_versions WHERE knowledge_document_id = ? AND version_label = ?",
                    UUID.class, docId, VERSION_LABEL);
            assertThat(docId).isNotNull();
            assertThat(versionId).isNotNull();

            // Publish version through domain service (creates QUEUED outbox run)
            lifecycleService.publishVersion(ADMIN_ID, versionId);
            needsIndexing = true;
        } else {
            String verStatus = jdbcTemplate.queryForObject(
                    "SELECT status::text FROM fitness.knowledge_versions WHERE id = ?", String.class, versionId);
            if ("DRAFT".equals(verStatus)) {
                log.info("WHO-01 version {} exists in DRAFT status. Publishing...", VERSION_LABEL);
                lifecycleService.publishVersion(ADMIN_ID, versionId);
                needsIndexing = true;
            } else if ("ACTIVE".equals(verStatus)) {
                Integer existingVectors = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM fitness.knowledge_embeddings
                        WHERE embedding_model_version_id = ?
                          AND knowledge_chunk_id IN (SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?)
                        """, Integer.class, embeddingModelPort.modelVersionId(), versionId);
                if (existingVectors != null && existingVectors > 0) {
                    log.info("WHO-01 version {} is already ACTIVE with {} OpenAI vectors. Reusing existing data for retrieval verification.",
                            VERSION_LABEL, existingVectors);
                    needsIndexing = false;
                } else {
                    List<UUID> existingWho01Runs = jdbcTemplate.query("""
                            SELECT id FROM fitness.knowledge_processing_runs
                            WHERE knowledge_version_id = ? AND run_type = 'REINDEX' AND status = 'QUEUED'
                            ORDER BY created_at DESC
                            """, (rs, rowNum) -> (UUID) rs.getObject("id"), versionId);
                    if (!existingWho01Runs.isEmpty()) {
                        log.info("WHO-01 version {} is ACTIVE without OpenAI vectors and has existing QUEUED retry job {}. Resuming execution via existing outbox job without creating duplicates.",
                                VERSION_LABEL, existingWho01Runs.get(0));
                        needsIndexing = true;
                    } else {
                        log.info("WHO-01 version {} is ACTIVE without OpenAI vectors and no pending job. Enqueueing reindex...", VERSION_LABEL);
                        String operationKey = "INDEX:" + versionId;
                        jdbcTemplate.update("""
                                INSERT INTO fitness.knowledge_processing_runs (
                                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at, attempt_count, max_attempts
                                ) VALUES (
                                    gen_random_uuid(), ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, now(), 0, 3
                                ) ON CONFLICT (operation_key) DO UPDATE SET status = 'QUEUED', next_attempt_at = now()
                                """, versionId, operationKey);
                        needsIndexing = true;
                    }
                }
            }
        }

        // 8. Process outbox run specifically for WHO-01: exactly ONE eligible run, called exactly ONCE
        if (needsIndexing) {
            UUID who01RunId = jdbcTemplate.queryForObject("""
                    SELECT id FROM fitness.knowledge_processing_runs
                    WHERE knowledge_version_id = ? AND run_type = 'REINDEX' AND status = 'QUEUED'
                    ORDER BY created_at DESC LIMIT 1
                    """, UUID.class, versionId);
            assertThat(who01RunId).as("A QUEUED outbox run must exist for WHO-01 version %s", versionId).isNotNull();

            Integer priorAttempts = jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM fitness.knowledge_processing_runs WHERE id = ?", Integer.class, who01RunId);
            log.info("Resuming WHO-01 outbox run {}: current attempt_count={}/3. Executing attempt #{}...",
                    who01RunId, priorAttempts, (priorAttempts != null ? priorAttempts + 1 : 1));

            // Verify that the ONLY eligible job in the entire queue is exactly who01RunId
            List<UUID> eligibleRunIds = jdbcTemplate.query("""
                    SELECT id FROM fitness.knowledge_processing_runs
                    WHERE (status = 'QUEUED' AND attempt_count < max_attempts AND (next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp()))
                       OR (status = 'RUNNING' AND attempt_count < max_attempts AND lease_expires_at IS NOT NULL AND lease_expires_at < clock_timestamp())
                    ORDER BY created_at ASC
                    """, (rs, rowNum) -> (UUID) rs.getObject("id"));

            assertThat(eligibleRunIds)
                    .as("The queue must contain EXACTLY ONE eligible job, which must be who01RunId")
                    .containsExactly(who01RunId);

            // Execute outbox consumer exactly ONCE (no drain loop)
            long indexStartTime = System.currentTimeMillis();
            int processedCount = outboxConsumer.processPendingRuns(1);
            long indexDurationMs = System.currentTimeMillis() - indexStartTime;

            assertThat(processedCount).as("outboxConsumer.processPendingRuns(1) must process exactly 1 job").isEqualTo(1);

            // Verify that who01RunId specifically transitioned to SUCCEEDED
            String who01RunStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, who01RunId);
            assertThat(who01RunStatus).as("Target WHO-01 outbox run %s must be SUCCEEDED", who01RunId).isEqualTo("SUCCEEDED");

            Integer finalAttempts = jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM fitness.knowledge_processing_runs WHERE id = ?", Integer.class, who01RunId);
            assertThat(finalAttempts).as("attempt_count must increment by 1").isEqualTo(priorAttempts + 1);

            String statisticsJson = jdbcTemplate.queryForObject(
                    "SELECT statistics::text FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, who01RunId);
            JsonNode statsNode = objectMapper.readTree(statisticsJson);
            assertThat(statsNode.path("index_ready").asBoolean()).isTrue();
            int indexedChunks = statsNode.path("indexed_chunks").asInt();
            assertThat(indexedChunks).isGreaterThan(0);

            log.info("WHO-01 Indexing Complete: runId={}, indexedChunks={}, durationMs={}, modelVersionId={}, attempts={}/{}",
                    who01RunId, indexedChunks, indexDurationMs, embeddingModelPort.modelVersionId(), finalAttempts, 3);
        }

        // 7. Verify Database Records (Chunks & Vectors)
        Integer chunkCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?", Integer.class, versionId);
        Integer vectorCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM fitness.knowledge_embeddings
                WHERE embedding_model_version_id = ?
                  AND knowledge_chunk_id IN (SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?)
                """, Integer.class, embeddingModelPort.modelVersionId(), versionId);

        assertThat(chunkCount).isGreaterThan(0);
        assertThat(vectorCount).isEqualTo(chunkCount);

        // Budget Decision & Cost Accounting Log (Approved DEC-A13-03: Hard Limit $10.00, Soft Alert $5.00)
        // Heuristic tokenCount (~622 across 5 chunks) vs OpenAI API actual token usage
        log.info("Cost Accounting (DEC-A13-03): chunks={}, vectors={}, heuristicTokens~622, estimatedCost~$0.000012 USD. " +
                 "Actual billed tokens are determined by OpenAI API response (usage.total_tokens).",
                chunkCount, vectorCount);

        // 8. Execute Retrieval Verification
        String testQuery = "Nguyên tắc vận động và hoạt động thể lực theo khuyến nghị WHO 2020 là gì?";
        long queryStartTime = System.currentTimeMillis();
        List<RetrievedKnowledgeChunk> retrieved = retrievalService.retrieveActiveKnowledge(
                testQuery,
                List.of(SOURCE_ID),
                5
        );
        long queryDurationMs = System.currentTimeMillis() - queryStartTime;

        assertThat(retrieved).isNotEmpty();
        RetrievedKnowledgeChunk topChunk = retrieved.get(0);
        log.info("Retrieval PASSED: retrievedCount={}, topSimilarity={}, durationMs={}, topHeading='{}', snippet='{}'",
                retrieved.size(), topChunk.similarityScore(), queryDurationMs, topChunk.headingPath(), topChunk.snippet());

        assertThat(topChunk.documentCode()).isEqualTo(DOCUMENT_CODE);
        assertThat(topChunk.versionLabel()).isEqualTo(VERSION_LABEL);
        assertThat(topChunk.sourceId()).isEqualTo(SOURCE_ID);
        assertThat(topChunk.similarityScore()).isGreaterThan(0.0f);

        // Test Negative Source Scope: Disallowed source returns empty
        List<RetrievedKnowledgeChunk> emptyRetrieved = retrievalService.retrieveActiveKnowledge(
                testQuery,
                List.of("NIH-ODS-EXERCISE-SUPPLEMENTS"),
                5
        );
        assertThat(emptyRetrieved).isEmpty();
        log.info("Negative source scope verified: querying disallowed source returned 0 chunks as expected.");
    }
}
