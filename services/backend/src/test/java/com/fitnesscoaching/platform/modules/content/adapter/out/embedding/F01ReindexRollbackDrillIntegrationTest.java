package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ContentConflictException;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunKnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeChunkRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeDocumentRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeEmbeddingRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeProcessingRunRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeVersionRepository;
import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeIndexingOutboxConsumer;
import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeRetrievalService;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeProcessingRun;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F01 Reindex / Rollback Runtime Verification Drill (DEC-A13-05).
 * Validates Scenarios A, B, C, D:
 * <ul>
 *   <li>Scenario A: Idempotent reindexing preserves identities, anti-duplication, readiness, and retrieval.</li>
 *   <li>Scenario B: Midway partial failures (Boundary 2 and Boundary 3) roll back cleanly with zero unready exposure and continuous retrieval.</li>
 *   <li>Scenario C: Vector replacement across model spaces requires outbox reindex or snapshot restore to recover.</li>
 *   <li>Scenario D: Citation history, locator, and provenance preservation across reindex, mutation fail-closed, and ineligible draft source isolation.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("F01 Reindex & Rollback Drill Integration Test (DEC-A13-05)")
class F01ReindexRollbackDrillIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SIMULATED_MODEL_VERSION_ID = UUID.fromString("e1000000-0000-0000-0000-000000000001");
    private static final UUID OPENAI_MODEL_VERSION_ID = UUID.fromString("e1000000-0000-0000-0000-000000000002");

    static final PostgreSQLContainer<?> postgres;

    static {
        String drillDbUrl = System.getenv("DRILL_DATABASE_URL");
        if (drillDbUrl == null || drillDbUrl.isBlank()) {
            postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("drill_integration")
                    .withUsername("fitness_app")
                    .withPassword("testpass123");
            postgres.start();
        } else {
            postgres = null;
        }
    }

    static final Set<Integer> FORBIDDEN_PORTS = Set.of(5432, 5433);
    private static final Pattern JDBC_URL_PATTERN = Pattern.compile("^jdbc:postgresql://([^:/]+):(\\d+)/([^?]+)(?:\\?.*)?$");

    public static void validateDrillJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("SAFETY GUARD: DRILL_DATABASE_URL cannot be null or blank");
        }
        Matcher matcher = JDBC_URL_PATTERN.matcher(jdbcUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalStateException("SAFETY GUARD: DRILL_DATABASE_URL does not match expected format jdbc:postgresql://host:port/database! Attempted: " + jdbcUrl);
        }
        int port = Integer.parseInt(matcher.group(2));
        String dbName = matcher.group(3);

        if (FORBIDDEN_PORTS.contains(port)) {
            throw new IllegalStateException("SAFETY GUARD: DRILL_DATABASE_URL targets forbidden port " + port + "! (Port 5432 is default Postgres, port 5433 is baseline testbed). Attempted: " + jdbcUrl);
        }
        if (!dbName.toLowerCase().contains("drill") || "digital_fitness".equalsIgnoreCase(dbName) || "postgres".equalsIgnoreCase(dbName) || "fitness".equalsIgnoreCase(dbName)) {
            throw new IllegalStateException("SAFETY GUARD: DRILL_DATABASE_URL must target an isolated drill database! Attempted DB: " + dbName);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String drillDbUrl = System.getenv("DRILL_DATABASE_URL");
        if (drillDbUrl != null && !drillDbUrl.isBlank()) {
            validateDrillJdbcUrl(drillDbUrl);
            registry.add("spring.datasource.url", () -> drillDbUrl);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DRILL_DATABASE_USER", "fitness_drill_app"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("DRILL_DATABASE_PASSWORD", "drill_pass_123"));
            registry.add("spring.flyway.enabled", () -> "false");
        } else {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.flyway.enabled", () -> "true");
        }

        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO fitness, public;");
        registry.add("app.scheduling.enabled", () -> "false");
        registry.add("attention.review.cron", () -> "-");
        registry.add("ai.embedding.provider", () -> "simulated");
    }

    /**
     * Testable Embedding Port implementation to allow controlled model-space switching
     * and fault injection without requiring paid external API credentials.
     */
    static class DrillEmbeddingModelPort implements EmbeddingModelPort {

        private final DeterministicEmbeddingGenerator delegate = new DeterministicEmbeddingGenerator();

        private volatile UUID currentModelVersionId = SIMULATED_MODEL_VERSION_ID;
        private volatile String currentModelSpace = "simulated/deterministic-1536/1.0.0";
        private volatile String currentProvider = "simulated";
        private volatile String currentModelCode = "simulated/deterministic-1536";
        private volatile String currentVersionLabel = "1.0.0";
        private volatile RuntimeException batchFailureException = null;

        public void reset() {
            this.currentModelVersionId = SIMULATED_MODEL_VERSION_ID;
            this.currentModelSpace = "simulated/deterministic-1536/1.0.0";
            this.currentProvider = "simulated";
            this.currentModelCode = "simulated/deterministic-1536";
            this.currentVersionLabel = "1.0.0";
            this.batchFailureException = null;
        }

        public void setTargetModel(UUID versionId, String modelSpace, String provider, String modelCode, String versionLabel) {
            this.currentModelVersionId = versionId;
            this.currentModelSpace = modelSpace;
            this.currentProvider = provider;
            this.currentModelCode = modelCode;
            this.currentVersionLabel = versionLabel;
        }

        public void setBatchFailureException(RuntimeException ex) {
            this.batchFailureException = ex;
        }

        @Override public int dimension() { return 1536; }
        @Override public String provider() { return currentProvider; }
        @Override public String modelCode() { return currentModelCode; }
        @Override public String versionLabel() { return currentVersionLabel; }
        @Override public String modelSpace() { return currentModelSpace; }
        @Override public UUID modelVersionId() { return currentModelVersionId; }

        @Override
        public float[] generateEmbedding(String text) {
            return delegate.generateEmbedding(text);
        }

        @Override
        public List<float[]> generateBatch(List<String> texts) {
            if (batchFailureException != null) {
                throw batchFailureException;
            }
            return delegate.generateBatch(texts);
        }
    }

    @TestConfiguration
    static class DrillTestConfig {
        @Bean
        @Primary
        public DrillEmbeddingModelPort drillEmbeddingModelPort() {
            return new DrillEmbeddingModelPort();
        }
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private KnowledgeDocumentRepository documentRepository;
    @Autowired private KnowledgeVersionRepository versionRepository;
    @Autowired private KnowledgeChunkRepository chunkRepository;
    @Autowired private KnowledgeEmbeddingRepository embeddingRepository;
    @Autowired private KnowledgeProcessingRunRepository processingRunRepository;
    @Autowired private KnowledgeIndexingOutboxConsumer outboxConsumer;
    @Autowired private KnowledgeRetrievalService retrievalService;
    @Autowired private KnowledgeRetrievalUseCase retrievalUseCase;
    @Autowired private AiProvenanceRepository provenanceRepository;
    @Autowired private DrillEmbeddingModelPort drillEmbeddingPort;
    @Autowired private ObjectMapper objectMapper;

    private void verifyTargetDatabaseSafety() {
        String currentDb = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        if (currentDb == null || !currentDb.toLowerCase().contains("drill")) {
            throw new IllegalStateException("SAFETY GUARD: Refusing destructive fixture reset on non-drill database: " + currentDb);
        }
        if ("digital_fitness".equalsIgnoreCase(currentDb) || "fitness".equalsIgnoreCase(currentDb) || "postgres".equalsIgnoreCase(currentDb)) {
            throw new IllegalStateException("SAFETY GUARD: Refusing destructive fixture reset on protected database: " + currentDb);
        }

        String drillDbUrl = System.getenv("DRILL_DATABASE_URL");
        if (drillDbUrl != null && !drillDbUrl.isBlank()) {
            try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
                String metaUrl = conn.getMetaData().getURL();
                validateDrillJdbcUrl(metaUrl);
            } catch (SQLException e) {
                throw new IllegalStateException("SAFETY GUARD: Failed to inspect JDBC connection metadata: " + e.getMessage(), e);
            }
        }
    }

    @BeforeEach
    void setUp() {
        drillEmbeddingPort.reset();
        outboxConsumer.setExecutionInspectionHook(null);

        verifyTargetDatabaseSafety();

        jdbcTemplate.execute("DELETE FROM fitness.ai_run_knowledge_chunks");
        jdbcTemplate.execute("DELETE FROM fitness.ai_runs");
        jdbcTemplate.execute("TRUNCATE TABLE fitness.knowledge_documents, fitness.knowledge_processing_runs CASCADE");

        // Seed admin user
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, 'admin-drill@fitplatform.local', '$2a$10$dummyHash', 'Drill Admin', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                """, ADMIN_ID);

        // Ensure models in registry are active for tests
        jdbcTemplate.update("UPDATE fitness.ai_models SET is_active = true WHERE id = 'e0000000-0000-0000-0000-000000000001'");
    }

    @AfterEach
    void tearDown() {
        drillEmbeddingPort.reset();
        outboxConsumer.setExecutionInspectionHook(null);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private UUID createDocument(String code, String sourceId) {
        UUID docId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                VALUES (?, ?, 'Drill Test Document', ?, 'vi', ?)
                """, docId, code, sourceId, ADMIN_ID);
        return docId;
    }

    private UUID createVersion(UUID docId, String versionLabel, String content, TechnicalReadiness readiness, KnowledgeStatus status) {
        UUID versionId = UUID.randomUUID();
        String hash = sha256(content);
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_versions(
                    id, knowledge_document_id, version_number, version_label,
                    status, content_hash, technical_readiness, content, created_by
                ) VALUES (?, ?, 1, ?, ?::fitness.knowledge_status, ?, ?, ?, ?)
                """, versionId, docId, versionLabel, status.name(), hash, readiness.name(), content, ADMIN_ID);
        return versionId;
    }

    // =========================================================================
    // SCENARIO A: Reindex thành công (Idempotent Reindex)
    // =========================================================================

    @Test
    @DisplayName("Scenario A: Successful reindex preserves chunk/vector identity, readiness, anti-duplication, and retrieval equivalence")
    void scenarioA_successfulIdempotentReindexPreservesChunksVectorsReadinessAndRetrieval() throws Exception {
        String docCode = "WHO-DRILL-SCENARIO-A";
        String content = "# Phạm vi khuyến nghị\n\nNgười trưởng thành nên thực hiện ít nhất 150-300 phút vận động vừa.\n\n# Lợi ích sức khỏe\n\nGiảm nguy cơ mắc bệnh tim mạch và cải thiện sức khỏe tinh thần.";
        UUID docId = createDocument(docCode, "WHO-PA-SB-2020");
        UUID versionId = createVersion(docId, "1.1.0", content, TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, KnowledgeStatus.ACTIVE);

        // 1. Initial Indexing
        UUID initialRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, initialRunId, versionId, "OP:INIT:" + versionId);

        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();

        // Verify initial state
        String initStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, initialRunId);
        assertThat(initStatus).isEqualTo("SUCCEEDED");

        Integer initialChunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?", Integer.class, versionId);
        Integer initialVectorCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_embeddings e JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id WHERE c.knowledge_version_id = ?",
                Integer.class, versionId);
        assertThat(initialChunkCount).isEqualTo(2);
        assertThat(initialVectorCount).isEqualTo(2);

        // Snapshot chunk 0 and 1
        UUID chunk0Id = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 0", UUID.class, versionId);
        UUID chunk1Id = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 1", UUID.class, versionId);
        String chunk0Content = jdbcTemplate.queryForObject("SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunk0Id);
        String chunk0Hash = jdbcTemplate.queryForObject("SELECT metadata->>'chunkHash' FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunk0Id);

        // Baseline retrieval check
        List<RetrievedKnowledgeChunk> initialRetrieval = retrievalService.retrieveActiveKnowledge("vận động thể lực tim mạch", List.of("WHO-PA-SB-2020"), 5);
        assertThat(initialRetrieval).isNotEmpty();
        assertThat(initialRetrieval.get(0).similarityScore()).isGreaterThan(0.0);
        double baselineSimilarity = initialRetrieval.get(0).similarityScore();

        // 2. Trigger REINDEX with same document content
        UUID reindexRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, reindexRunId, versionId, "OP:REINDEX:SCENARIO-A:" + reindexRunId);

        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();

        // 3. Post-reindex verification
        String reindexStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, reindexRunId);
        assertThat(reindexStatus).isEqualTo("SUCCEEDED");

        String statsText = jdbcTemplate.queryForObject(
                "SELECT statistics::text FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, reindexRunId);
        JsonNode statsNode = objectMapper.readTree(statsText);
        assertThat(statsNode.path("index_ready").asBoolean()).isTrue();
        assertThat(statsNode.path("indexed_chunks").asInt()).isEqualTo(2);
        assertThat(statsNode.path("embedding_space").asText()).isEqualTo("simulated/deterministic-1536/1.0.0");

        // Anti-duplication check: chunk count and vector count must NOT increase
        Integer postChunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?", Integer.class, versionId);
        Integer postVectorCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_embeddings e JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id WHERE c.knowledge_version_id = ?",
                Integer.class, versionId);
        assertThat(postChunkCount).isEqualTo(2);
        assertThat(postVectorCount).isEqualTo(2);

        // Identity check: chunk IDs and chunk content must remain strictly identical
        UUID postChunk0Id = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 0", UUID.class, versionId);
        UUID postChunk1Id = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 1", UUID.class, versionId);
        assertThat(postChunk0Id).isEqualTo(chunk0Id);
        assertThat(postChunk1Id).isEqualTo(chunk1Id);

        String postChunk0Content = jdbcTemplate.queryForObject("SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunk0Id);
        String postChunk0Hash = jdbcTemplate.queryForObject("SELECT metadata->>'chunkHash' FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunk0Id);
        assertThat(postChunk0Content).isEqualTo(chunk0Content);
        assertThat(postChunk0Hash).isEqualTo(chunk0Hash);

        // Retrieval equivalence check
        List<RetrievedKnowledgeChunk> postRetrieval = retrievalService.retrieveActiveKnowledge("vận động thể lực tim mạch", List.of("WHO-PA-SB-2020"), 5);
        assertThat(postRetrieval).hasSize(initialRetrieval.size());
        assertThat(postRetrieval.get(0).chunkId()).isEqualTo(initialRetrieval.get(0).chunkId());
        assertThat(postRetrieval.get(0).similarityScore()).isEqualTo(baselineSimilarity);
    }

    // =========================================================================
    // SCENARIO B: Reindex gặp lỗi giữa chừng (Fault Injection & Recovery)
    // =========================================================================

    @Test
    @DisplayName("Scenario B1: Midway fault during embedding generation rolls back atomically, does not expose partial data, and maintains active retrieval")
    void scenarioB1_midwayFailureDuringEmbeddingGenerationRollsBackAndMaintainsPriorIndex() throws Exception {
        String docCode = "WHO-DRILL-SCENARIO-B1";
        String content = "# Đoạn 1\n\nNội dung khởi đầu cho bài kiểm tra lỗi mạng.";
        UUID docId = createDocument(docCode, "WHO-PA-SB-2020");
        UUID versionId = createVersion(docId, "1.1.0", content, TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, KnowledgeStatus.ACTIVE);

        // 1. Initial healthy indexing
        UUID initRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, initRunId, versionId, "OP:INIT:" + versionId);
        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();

        // Baseline retrieval works
        List<RetrievedKnowledgeChunk> baseline = retrievalService.retrieveActiveKnowledge("nội dung khởi đầu bài kiểm tra", List.of("WHO-PA-SB-2020"), 5);
        assertThat(baseline).isNotEmpty();

        // 2. Inject fault in Boundary 2: Simulated external provider HTTP 503 / timeout
        drillEmbeddingPort.setBatchFailureException(new RuntimeException("Simulated 503 Gateway Timeout from Embedding Provider"));

        UUID failedRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, max_attempts, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', 1, ?, '{}'::jsonb, clock_timestamp())
                """, failedRunId, versionId, "OP:FAIL-B1:" + failedRunId);

        // Worker attempts to process
        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();

        // 3. Verify atomic failure: Boundary 3 was NOT entered, previous vectors were NOT deleted
        String runStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, failedRunId);
        assertThat(runStatus).isEqualTo("FAILED");

        String runError = jdbcTemplate.queryForObject(
                "SELECT error_message FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, failedRunId);
        assertThat(runError).contains("Simulated 503 Gateway Timeout");

        // Embeddings count for this version is still intact (not wiped)
        Integer vecCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_embeddings e JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id WHERE c.knowledge_version_id = ?",
                Integer.class, versionId);
        assertThat(vecCount).isEqualTo(1);

        // Retrieval CONTINUES to work seamlessly: zero downtime observed for active queries
        List<RetrievedKnowledgeChunk> postFailRetrieval = retrievalService.retrieveActiveKnowledge("nội dung khởi đầu bài kiểm tra", List.of("WHO-PA-SB-2020"), 5);
        assertThat(postFailRetrieval).isNotEmpty();
        assertThat(postFailRetrieval.get(0).chunkId()).isEqualTo(baseline.get(0).chunkId());

        // 4. Recovery: Clear fault and retry
        drillEmbeddingPort.setBatchFailureException(null);
        UUID recoveryRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, recoveryRunId, versionId, "OP:RECOVERY-B1:" + recoveryRunId);

        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();
        String recoveredStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, recoveryRunId);
        assertThat(recoveredStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("Scenario B2: Claim fencing failure in Boundary 3 rolls back transaction completely without partial state")
    void scenarioB2_boundary3ClaimFencingRollsBackAtomicallyWithoutPartialWrites() throws Exception {
        String docCode = "WHO-DRILL-SCENARIO-B2";
        String content = "# Mục A\n\nNội dung kiểm tra tranh chấp fencing token.";
        UUID docId = createDocument(docCode, "WHO-PA-SB-2020");
        UUID versionId = createVersion(docId, "1.1.0", content, TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, KnowledgeStatus.ACTIVE);

        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, runId, versionId, "OP:FENCE-TEST:" + runId);

        UUID overtakingToken = UUID.randomUUID();
        // Hook to simulate lease expiration / overtaking worker during Boundary 2 execution
        outboxConsumer.setExecutionInspectionHook(run -> {
            if (run.id().equals(runId)) {
                jdbcTemplate.update("UPDATE fitness.knowledge_processing_runs SET claim_token = ? WHERE id = ?", overtakingToken, runId);
            }
        });

        // Worker attempts to process run
        boolean processed = outboxConsumer.processNextRun("worker-original");
        assertThat(processed).isTrue();

        // Verification: Boundary 3 transaction failed fencing check and rolled back
        UUID tokenInDb = jdbcTemplate.queryForObject(
                "SELECT claim_token FROM fitness.knowledge_processing_runs WHERE id = ?", UUID.class, runId);
        assertThat(tokenInDb).isEqualTo(overtakingToken);

        String runStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, runId);
        assertThat(runStatus).isNotEqualTo("SUCCEEDED");

        // No chunks or embeddings were committed by worker-original
        int chunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?", Integer.class, versionId);
        assertThat(chunkCount).isEqualTo(0);
    }

    // =========================================================================
    // SCENARIO C: Rollback sau khi vectors cũ đã bị thay thế (DEC-A13-05)
    // =========================================================================

    @Test
    @DisplayName("Scenario C: Vector replacement across model spaces proves config switch alone is insufficient and outbox reindex recovers retrieval")
    void scenarioC_vectorReplacementRollbackProvesConfigSwitchInsufficientAndReindexOrRestoreRecovers() throws Exception {
        String docCode = "WHO-DRILL-SCENARIO-C";
        String content = "# Nguyên tắc dinh dưỡng và vận động\n\nCân bằng năng lượng đòi hỏi phối hợp dinh dưỡng hợp lý và vận động thể lực đều đặn.";
        UUID docId = createDocument(docCode, "WHO-PA-SB-2020");
        UUID versionId = createVersion(docId, "1.1.0", content, TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, KnowledgeStatus.ACTIVE);

        // 1. Initial State: Model Space A (simulated)
        drillEmbeddingPort.setTargetModel(
                SIMULATED_MODEL_VERSION_ID,
                "simulated/deterministic-1536/1.0.0",
                "simulated",
                "simulated/deterministic-1536",
                "1.0.0"
        );

        UUID runAId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, runAId, versionId, "OP:MODEL-A:" + versionId);
        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();

        // Baseline retrieval under Model A works
        List<RetrievedKnowledgeChunk> retrievalModelA = retrievalService.retrieveActiveKnowledge("nguyên tắc dinh dưỡng và vận động", List.of("WHO-PA-SB-2020"), 5);
        assertThat(retrievalModelA).isNotEmpty();
        assertThat(retrievalModelA.get(0).similarityScore()).isGreaterThan(0.0);

        // Take snapshot backup of vectors for disaster recovery demonstration
        jdbcTemplate.execute("DROP TABLE IF EXISTS fitness.drill_backup_embeddings");
        jdbcTemplate.execute("CREATE TABLE fitness.drill_backup_embeddings AS SELECT * FROM fitness.knowledge_embeddings");
        jdbcTemplate.execute("DROP TABLE IF EXISTS fitness.drill_backup_runs");
        jdbcTemplate.execute("CREATE TABLE fitness.drill_backup_runs AS SELECT * FROM fitness.knowledge_processing_runs");

        // 2. Reindex with Model Space B (simulating OpenAI model)
        jdbcTemplate.update("UPDATE fitness.ai_models SET is_active = true WHERE id = 'e0000000-0000-0000-0000-000000000002'");
        jdbcTemplate.update("UPDATE fitness.ai_model_versions SET activated_at = now() WHERE id = 'e1000000-0000-0000-0000-000000000002'");

        drillEmbeddingPort.setTargetModel(
                OPENAI_MODEL_VERSION_ID,
                "openai/text-embedding-3-small/1536",
                "openai",
                "text-embedding-3-small",
                "1.0.0"
        );

        UUID runBId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, runBId, versionId, "OP:MODEL-B:" + versionId);
        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();

        // 3. Verify vector replacement: Model A vectors are GONE from knowledge_embeddings!
        Integer countModelAVectors = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fitness.knowledge_embeddings e
                JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id
                WHERE c.knowledge_version_id = ? AND e.embedding_model_version_id = ?
                """, Integer.class, versionId, SIMULATED_MODEL_VERSION_ID);
        Integer countModelBVectors = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fitness.knowledge_embeddings e
                JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id
                WHERE c.knowledge_version_id = ? AND e.embedding_model_version_id = ?
                """, Integer.class, versionId, OPENAI_MODEL_VERSION_ID);

        assertThat(countModelAVectors).isZero();
        assertThat(countModelBVectors).isEqualTo(1);

        // 4. DEMONSTRATION OF DEC-A13-05: Merely changing app config back to Model A DOES NOT restore retrieval
        drillEmbeddingPort.setTargetModel(
                SIMULATED_MODEL_VERSION_ID,
                "simulated/deterministic-1536/1.0.0",
                "simulated",
                "simulated/deterministic-1536",
                "1.0.0"
        );

        List<RetrievedKnowledgeChunk> disruptedRetrieval = retrievalService.retrieveActiveKnowledge("nguyên tắc dinh dưỡng và vận động", List.of("WHO-PA-SB-2020"), 5);
        // Returns 0 chunks because vectors for Model A were replaced by Model B
        assertThat(disruptedRetrieval).isEmpty();

        // 5. RECOVERY PATH 1: Reindex back to Model A (Standard DEC-A13-05 procedure)
        UUID rollbackRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, rollbackRunId, versionId, "OP:ROLLBACK-TO-A:" + rollbackRunId);
        long startReindexRecoveryNs = System.nanoTime();
        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();
        long reindexRecoveryDurationMs = (System.nanoTime() - startReindexRecoveryNs) / 1_000_000;

        // Post-rollback verification: Model A vectors are back and Model B vectors are deleted
        Integer postRollbackCountA = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fitness.knowledge_embeddings e
                JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id
                WHERE c.knowledge_version_id = ? AND e.embedding_model_version_id = ?
                """, Integer.class, versionId, SIMULATED_MODEL_VERSION_ID);
        Integer postRollbackCountB = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fitness.knowledge_embeddings e
                JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id
                WHERE c.knowledge_version_id = ? AND e.embedding_model_version_id = ?
                """, Integer.class, versionId, OPENAI_MODEL_VERSION_ID);

        assertThat(postRollbackCountA).isEqualTo(1);
        assertThat(postRollbackCountB).isZero();

        // Retrieval under Model A is fully restored!
        List<RetrievedKnowledgeChunk> restoredRetrieval = retrievalService.retrieveActiveKnowledge("nguyên tắc dinh dưỡng và vận động", List.of("WHO-PA-SB-2020"), 5);
        assertThat(restoredRetrieval).isNotEmpty();
        assertThat(restoredRetrieval.get(0).similarityScore()).isEqualTo(retrievalModelA.get(0).similarityScore());

        // 6. RECOVERY PATH 2: Database Snapshot Restore
        // Switch again to Model B
        drillEmbeddingPort.setTargetModel(OPENAI_MODEL_VERSION_ID, "openai/text-embedding-3-small/1536", "openai", "text-embedding-3-small", "1.0.0");
        UUID reindexB2 = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO fitness.knowledge_processing_runs (id, knowledge_version_id, run_type, status, operation_key, statistics, created_at) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())",
                reindexB2, versionId, "OP:B2:" + reindexB2);
        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();

        // Switch config back to Model A
        drillEmbeddingPort.setTargetModel(SIMULATED_MODEL_VERSION_ID, "simulated/deterministic-1536/1.0.0", "simulated", "simulated/deterministic-1536", "1.0.0");
        assertThat(retrievalService.retrieveActiveKnowledge("nguyên tắc dinh dưỡng và vận động", List.of("WHO-PA-SB-2020"), 5)).isEmpty();

        // Perform fast snapshot restore of embeddings and runs
        long startSnapshotRecoveryNs = System.nanoTime();
        jdbcTemplate.update("DELETE FROM fitness.knowledge_embeddings WHERE knowledge_chunk_id IN (SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?)", versionId);
        jdbcTemplate.execute("INSERT INTO fitness.knowledge_embeddings SELECT * FROM fitness.drill_backup_embeddings");
        jdbcTemplate.execute("""
                INSERT INTO fitness.knowledge_processing_runs SELECT * FROM fitness.drill_backup_runs
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, statistics = EXCLUDED.statistics
                """);
        long snapshotRecoveryDurationMs = (System.nanoTime() - startSnapshotRecoveryNs) / 1_000_000;

        System.out.println("[DRILL_METRICS] outbox_reindex_recovery_ms=" + reindexRecoveryDurationMs + " snapshot_restore_recovery_ms=" + snapshotRecoveryDurationMs);

        // Retrieval is immediately restored via snapshot
        List<RetrievedKnowledgeChunk> snapshotRestored = retrievalService.retrieveActiveKnowledge("nguyên tắc dinh dưỡng và vận động", List.of("WHO-PA-SB-2020"), 5);
        assertThat(snapshotRestored).isNotEmpty();
        assertThat(snapshotRestored.get(0).chunkId()).isEqualTo(retrievalModelA.get(0).chunkId());

        // Clean up backup tables
        jdbcTemplate.execute("DROP TABLE IF EXISTS fitness.drill_backup_embeddings");
        jdbcTemplate.execute("DROP TABLE IF EXISTS fitness.drill_backup_runs");
    }

    // =========================================================================
    // SCENARIO D: Citation history và provenance
    // =========================================================================

    @Test
    @DisplayName("Scenario D: Citation history, locator, and provenance are strictly preserved across reindex, fail closed on mutation, and exclude draft/ineligible sources")
    void scenarioD_citationHistoryProvenancePreservedAcrossReindexAndIneligibleDraftIsolation() throws Exception {
        String docCode = "WHO-DRILL-SCENARIO-D";
        String content = "# Mục 0\n\nKhuyến nghị vận động cơ bản.\n\n# Mục 1\n\nChi tiết bài tập thể lực.\n\n# Mục 2\n\nCảnh báo và lưu ý an toàn.";
        UUID docId = createDocument(docCode, "WHO-PA-SB-2020");
        UUID versionId = createVersion(docId, "1.1.0", content, TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED, KnowledgeStatus.ACTIVE);

        // 1. Initial Indexing
        UUID initRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, initRunId, versionId, "OP:INIT-D:" + versionId);
        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();

        UUID chunk0Id = jdbcTemplate.queryForObject("SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 0", UUID.class, versionId);
        UUID chunk1Id = jdbcTemplate.queryForObject("SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 1", UUID.class, versionId);
        UUID chunk2Id = jdbcTemplate.queryForObject("SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 2", UUID.class, versionId);

        // 2. Create AI Run citing chunk 0 and chunk 2
        UUID aiRunId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO fitness.ai_runs(id, request_type, status, created_at) VALUES (?, 'KNOWLEDGE_QUERY', 'SUCCEEDED', clock_timestamp())", aiRunId);
        jdbcTemplate.update("INSERT INTO fitness.ai_run_knowledge_chunks(ai_run_id, knowledge_chunk_id, rank, similarity_score) VALUES (?, ?, 1, 0.95), (?, ?, 2, 0.88)",
                aiRunId, chunk0Id, aiRunId, chunk2Id);

        // Verify provenance resolution before reindex
        List<AiRunKnowledgeChunk> preReindexChunks = provenanceRepository.findKnowledgeChunkReferencesByRunId(aiRunId);
        assertThat(preReindexChunks).hasSize(2);
        assertThat(preReindexChunks.get(0).knowledgeChunkId()).isEqualTo(chunk0Id);
        assertThat(preReindexChunks.get(1).knowledgeChunkId()).isEqualTo(chunk2Id);

        // 3. Unchanged Reindex preserves citations exactly
        UUID reindex1 = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, reindex1, versionId, "OP:REINDEX-D1:" + reindex1);
        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();

        List<AiRunKnowledgeChunk> postReindexChunks = provenanceRepository.findKnowledgeChunkReferencesByRunId(aiRunId);
        assertThat(postReindexChunks).hasSize(2);
        assertThat(postReindexChunks.get(0).knowledgeChunkId()).isEqualTo(chunk0Id);
        assertThat(postReindexChunks.get(1).knowledgeChunkId()).isEqualTo(chunk2Id);

        // 4. Mutation on cited chunk must fail closed
        String originalContent0 = jdbcTemplate.queryForObject("SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunk0Id);
        String metaText0 = jdbcTemplate.queryForObject("SELECT metadata::text FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunk0Id);
        String headingPath0 = jdbcTemplate.queryForObject("SELECT heading_path FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunk0Id);

        // Attempt mutating content on cited chunk 0
        KnowledgeChunk mutatedChunk = new KnowledgeChunk(
                chunk0Id, versionId, 0, headingPath0, "MUTATED CONTENT INJECTION", 10, metaText0, Instant.now());
        assertThatThrownBy(() -> chunkRepository.saveBatch(List.of(mutatedChunk)))
                .isInstanceOf(ContentConflictException.class)
                .hasMessageContaining("Cannot mutate content of cited chunk");

        // Attempt mutating provenance sourceId on cited chunk 0
        String mutatedSourceMeta = metaText0.replace("\"WHO-PA-SB-2020\"", "\"UNAUTHORIZED-SOURCE\"");
        KnowledgeChunk mutatedSourceChunk = new KnowledgeChunk(
                chunk0Id, versionId, 0, headingPath0, originalContent0, 10, mutatedSourceMeta, Instant.now());
        assertThatThrownBy(() -> chunkRepository.saveBatch(List.of(mutatedSourceChunk)))
                .isInstanceOf(ContentConflictException.class)
                .hasMessageContaining("provenance field 'sourceId'");

        // Verify DB content remains unaltered
        String currentContent0 = jdbcTemplate.queryForObject("SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunk0Id);
        assertThat(currentContent0).isEqualTo(originalContent0);

        // 5. Content Reduction: Uncited chunks pruned, Cited chunks preserved for history but excluded from retrieval
        // Update document to only 1 section (Section 0)
        jdbcTemplate.execute("ALTER TABLE fitness.knowledge_versions DISABLE TRIGGER protect_knowledge_version_content");
        try {
            jdbcTemplate.update("UPDATE fitness.knowledge_versions SET content = ? WHERE id = ?",
                    "# Mục 0\n\nKhuyến nghị vận động cơ bản.", versionId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE fitness.knowledge_versions ENABLE TRIGGER protect_knowledge_version_content");
        }

        UUID reductionRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, reductionRunId, versionId, "OP:REDUCE-D:" + reductionRunId);
        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();

        // Chunk 1 (uncited) was pruned from DB
        int chunk1Count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE id = ?", Integer.class, chunk1Id);
        assertThat(chunk1Count).isZero();

        // Chunk 2 (cited by AI run) is PRESERVED in knowledge_chunks for citation history integrity
        int chunk2Count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE id = ?", Integer.class, chunk2Id);
        assertThat(chunk2Count).isEqualTo(1);

        // BUT Chunk 2 vector was deleted from knowledge_embeddings!
        int chunk2EmbeddingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_embeddings WHERE knowledge_chunk_id = ?", Integer.class, chunk2Id);
        assertThat(chunk2EmbeddingCount).isZero();

        // isChunkEligible returns false for chunk 2 (cannot be retrieved anymore)
        assertThat(retrievalUseCase.isChunkEligible(docCode, "1.1.0", 2)).isFalse();
        // isChunkEligible returns true for chunk 0
        assertThat(retrievalUseCase.isChunkEligible(docCode, "1.1.0", 0)).isTrue();

        // Historical AI Run still has chunk 2 in its provenance reference without foreign key error
        List<AiRunKnowledgeChunk> finalProvenance = provenanceRepository.findKnowledgeChunkReferencesByRunId(aiRunId);
        assertThat(finalProvenance).hasSize(2);
        assertThat(finalProvenance.get(1).knowledgeChunkId()).isEqualTo(chunk2Id);

        // 6. Ineligible / Draft sources (e.g. HELD_AS_DRAFT NIDDK 05/06) fail-closed
        UUID draftDocId = createDocument("NIDDK-WEIGHT-FACTORS-2023-VI-05", "NIDDK-WEIGHT-FACTORS-2023");
        UUID draftVersionId = createVersion(draftDocId, "1.1.0", "# Thuốc và tăng cân\n\nNội dung y tế nhạy cảm.",
                TechnicalReadiness.HELD_AS_DRAFT, KnowledgeStatus.DRAFT);

        UUID draftRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, draftRunId, draftVersionId, "OP:DRAFT:" + draftVersionId);

        assertThat(outboxConsumer.processNextRun("drill-worker")).isTrue();
        String draftRunStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, draftRunId);
        assertThat(draftRunStatus).isEqualTo("FAILED");
        String draftError = jdbcTemplate.queryForObject(
                "SELECT error_message FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, draftRunId);
        assertThat(draftError).contains("requires ACTIVE");

        // Zero chunks or embeddings exist for draft document
        int draftChunks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?", Integer.class, draftVersionId);
        assertThat(draftChunks).isZero();

        // Retrieval never returns draft/ineligible documents
        List<RetrievedKnowledgeChunk> draftRetrieval = retrievalService.retrieveActiveKnowledge("thuốc và tăng cân", List.of("NIDDK-WEIGHT-FACTORS-2023"), 5);
        assertThat(draftRetrieval).isEmpty();
    }
}
