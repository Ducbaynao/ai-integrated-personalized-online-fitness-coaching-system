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
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controlled Testbed Integration Test for the expanded 9 eligible derivative articles.
 * Targets the local isolated testbed database at localhost:5433 (digital-fitness-postgres).
 *
 * <p>Execution Guard:
 * This class requires {@code RUN_LIVE_OPENAI_TESTS=true} via {@link EnabledIfEnvironmentVariable}.
 * By applying this condition at class level, JUnit Jupiter disables the container before
 * Spring TestContext framework attempts to boot {@link SpringBootTest} or connect to
 * {@code localhost:5433}. This ensures 100% CI isolation while preserving local testbed execution.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_OPENAI_TESTS", matches = "(?i)true")
@DisplayName("Eligible Corpus Real Embedding Testbed Integration Test")
class EligibleCorpusRealEmbeddingTestbedIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EligibleCorpusRealEmbeddingTestbedIntegrationTest.class);

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    record EligibleArticleDef(
            String documentCode,
            String sourceId,
            String title,
            String sourceName,
            String versionLabel,
            int versionNumber,
            String contentPath,
            String contentHash,
            int sourceBytes,
            String reviewEvidenceId,
            String baseDocumentId,
            String baseVersion,
            String baseHash
    ) {}

    private static final List<EligibleArticleDef> ELIGIBLE_ARTICLES = List.of(
            new EligibleArticleDef(
                    "WHO-PA-SB-2020-VI-01",
                    "WHO-PA-SB-2020",
                    "Phạm vi và cách sử dụng WHO 2020",
                    "World Health Organization",
                    "1.1.0",
                    1,
                    "derivatives/who-2020/01-scope.vi.md",
                    "5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840",
                    3429,
                    "REV-PO-20261008-01",
                    "WHO-PA-SB-2020-VI-01",
                    "1.0.0",
                    "83f7921e04986d1d3843ceb5d07d7911aac0c93dd81b87ab6f4659fae42277c1"
            ),
            new EligibleArticleDef(
                    "WHO-PA-SB-2020-VI-02",
                    "WHO-PA-SB-2020",
                    "Thuật ngữ vận động và sức khỏe",
                    "World Health Organization",
                    "1.1.0",
                    1,
                    "derivatives/who-2020/02-glossary.vi.md",
                    "47393a0e6335c702fe4c69bb2c45b7a848bab39a265a1532d7a2e4a389a2ad51",
                    5472,
                    "REV-PO-20261008-01",
                    "WHO-PA-SB-2020-VI-02",
                    "1.0.0",
                    "e5ba0ac1da1d49d92d55897da537a17cd7cd34662841c8df75576af65b743ab3"
            ),
            new EligibleArticleDef(
                    "WHO-PA-SB-2020-VI-03",
                    "WHO-PA-SB-2020",
                    "MET và cường độ vận động",
                    "World Health Organization",
                    "1.1.0",
                    1,
                    "derivatives/who-2020/03-intensity.vi.md",
                    "d56ad3c4473e8648e2b158c20e9cfa311a9fd37f282a0b517f1a9494c2099ebc",
                    2685,
                    "REV-PO-20261008-01",
                    "WHO-PA-SB-2020-VI-03",
                    "1.0.0",
                    "105839965aba23a1a1c6bdc116eaae28be0e729fda870473a140c6144786e835"
            ),
            new EligibleArticleDef(
                    "WHO-PA-SB-2020-VI-05",
                    "WHO-PA-SB-2020",
                    "Vận động và tăng cường cơ ở tuổi 18–64",
                    "World Health Organization",
                    "1.1.0",
                    1,
                    "derivatives/who-2020/05-adults-activity.vi.md",
                    "db6f9dc1140fd582487422cc23596224bfda2e711ff009335a7da07227b6fbe5",
                    4202,
                    "REV-PO-20261008-01",
                    "WHO-PA-SB-2020-VI-05",
                    "1.0.0",
                    "2a44bb5061ad3e28f761c3b6ef6e6f5f7b1c71b367e2431061e8da405dae3fc7"
            ),
            new EligibleArticleDef(
                    "WHO-PA-SB-2020-VI-06",
                    "WHO-PA-SB-2020",
                    "Hành vi ít vận động ở tuổi 18–64",
                    "World Health Organization",
                    "1.1.0",
                    1,
                    "derivatives/who-2020/06-adults-sedentary.vi.md",
                    "630802d505124da0fa395cc939977ed4278ee2297dfcac9cdde7fce6eb49fdd0",
                    2986,
                    "REV-PO-20261008-01",
                    "WHO-PA-SB-2020-VI-06",
                    "1.0.0",
                    "8dc3555454e284110c745cce526a3846e4d3575d471a5267c21ef1d1eddff999"
            ),
            new EligibleArticleDef(
                    "NIDDK-WEIGHT-FACTORS-2023-VI-01",
                    "NIDDK-WEIGHT-FACTORS-2023",
                    "Các yếu tố ảnh hưởng đến cân nặng",
                    "National Institute of Diabetes and Digestive and Kidney Diseases",
                    "1.1.0",
                    1,
                    "derivatives/niddk-weight-factors/01-overview.vi.md",
                    "49a628a6f06a20c0f85599542e8a4592ec3087a7d44fb64bb940d93de760cd90",
                    1678,
                    "REV-PO-20261008-01",
                    "NIDDK-WEIGHT-FACTORS-2023-VI-01",
                    "1.0.0",
                    "b75825137a19e142ea32c380c965afee91deb6f8307c7a7834bca0626855b523"
            ),
            new EligibleArticleDef(
                    "NIDDK-WEIGHT-FACTORS-2023-VI-02",
                    "NIDDK-WEIGHT-FACTORS-2023",
                    "Thói quen sinh hoạt và cân bằng năng lượng",
                    "National Institute of Diabetes and Digestive and Kidney Diseases",
                    "1.1.0",
                    1,
                    "derivatives/niddk-weight-factors/02-lifestyle.vi.md",
                    "cd5f102f182bfd2eea2a7ebf4f6b9aa91e6ecc894c8f9197756d2f9a4aa7c3d5",
                    1952,
                    "REV-PO-20261008-01",
                    "NIDDK-WEIGHT-FACTORS-2023-VI-02",
                    "1.0.0",
                    "70ebd4fcf742f8d07d8586608b67c13ed2960f56c196f0c64d74de7af4612ef1"
            ),
            new EligibleArticleDef(
                    "NIDDK-WEIGHT-FACTORS-2023-VI-03",
                    "NIDDK-WEIGHT-FACTORS-2023",
                    "Môi trường và khả năng lựa chọn lối sống lành mạnh",
                    "National Institute of Diabetes and Digestive and Kidney Diseases",
                    "1.1.0",
                    1,
                    "derivatives/niddk-weight-factors/03-environment.vi.md",
                    "cee3300127db2e339b2ceccf0e73a6c4e81172cb2db02942e332af3d0c9669ec",
                    1687,
                    "REV-PO-20261008-01",
                    "NIDDK-WEIGHT-FACTORS-2023-VI-03",
                    "1.0.0",
                    "9f7d22bd11c213fd19b60ff848a5ab814f35c99b4489a2309015cc29680df96d"
            ),
            new EligibleArticleDef(
                    "NIDDK-WEIGHT-FACTORS-2023-VI-04",
                    "NIDDK-WEIGHT-FACTORS-2023",
                    "Giấc ngủ và cân nặng ở người trưởng thành",
                    "National Institute of Diabetes and Digestive and Kidney Diseases",
                    "1.1.0",
                    1,
                    "derivatives/niddk-weight-factors/04-sleep.vi.md",
                    "87e4a99998a3fcea44d96bcda218b27214cba3dabc18e008fb1cd8bc618ef82f",
                    1951,
                    "REV-PO-20261008-01",
                    "NIDDK-WEIGHT-FACTORS-2023-VI-04",
                    "1.0.0",
                    "4ba66733c47e58fa077b34c7818ca79aefac149ba7167f20a75839e67fea535b"
            )
    );

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

    @Test
    @DisplayName("Controlled Live: Index all 9 eligible derivatives with OpenAI embeddings and verify retrieval")
    void testEligibleCorpusLiveIndexingAndRetrieval() throws Exception {
        Assumptions.assumeTrue(isLiveExplicitlyRequestedAndConfigured(),
                "SKIPPED: Live OpenAI test requires BOTH RUN_LIVE_OPENAI_TESTS=true AND a valid API key. " +
                "Offline safety preserved: no external API called and no database mutations executed.");

        log.info("Starting controlled eligible corpus indexing & retrieval test with real OpenAI embeddings...");

        // 1. Verify target database is isolated local testbed before any writes
        String currentDb = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
        assertThat(currentDb).isEqualTo("digital_fitness");
        assertThat(currentSchema).isEqualTo("fitness");

        // 2. Independently verify that automatic worker and scheduler are stopped
        if (scheduledTaskHolder != null) {
            Set<ScheduledTask> scheduledTasks = scheduledTaskHolder.getScheduledTasks();
            for (ScheduledTask task : scheduledTasks) {
                assertThat(task.toString())
                        .doesNotContain("KnowledgeIndexingOutbox")
                        .doesNotContain("processPendingRuns");
            }
        }

        // 3. Prepare Local Database Registry: activate OpenAI model and version
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

        // 4. Seed Admin user with CONTENT_ADMIN permission
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

        // 4B. Verify WHO-01 baseline integrity upfront before any new processing
        List<UUID> who01DocIds = jdbcTemplate.query(
                "SELECT id FROM fitness.knowledge_documents WHERE document_code = 'WHO-PA-SB-2020-VI-01'",
                (rs, rowNum) -> (UUID) rs.getObject("id"));
        assertThat(who01DocIds)
                .withFailMessage("WHO-01 document must exist in database before expanding indexing")
                .isNotEmpty();
        UUID who01DocId = who01DocIds.get(0);

        List<UUID> who01VerIds = jdbcTemplate.query(
                "SELECT id FROM fitness.knowledge_versions WHERE knowledge_document_id = ? AND version_label = '1.1.0'",
                (rs, rowNum) -> (UUID) rs.getObject("id"), who01DocId);
        assertThat(who01VerIds)
                .withFailMessage("WHO-01 version 1.1.0 must exist in database before expanding indexing")
                .isNotEmpty();
        UUID who01VerId = who01VerIds.get(0);

        String who01Status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM fitness.knowledge_versions WHERE id = ?", String.class, who01VerId);
        assertThat(who01Status).isEqualTo("ACTIVE");

        String who01Readiness = jdbcTemplate.queryForObject(
                "SELECT technical_readiness FROM fitness.knowledge_versions WHERE id = ?", String.class, who01VerId);
        assertThat(who01Readiness).isEqualTo("TECHNICAL_VERIFICATION_PASSED");

        String who01Hash = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM fitness.knowledge_versions WHERE id = ?", String.class, who01VerId);
        assertThat(who01Hash).isEqualTo("5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840");

        Integer who01Chunks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?", Integer.class, who01VerId);
        assertThat(who01Chunks).isEqualTo(5);

        Integer who01Vectors = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM fitness.knowledge_embeddings
                WHERE embedding_model_version_id = ?
                  AND knowledge_chunk_id IN (SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?)
                """, Integer.class, embeddingModelPort.modelVersionId(), who01VerId);
        assertThat(who01Vectors).isEqualTo(5);

        log.info("WHO-01 baseline verified upfront: ACTIVE, hash={}, chunks={}, vectors={}", who01Hash, who01Chunks, who01Vectors);

        // 5. Sequentially verify or import/publish/index eligible articles
        String targetDocCode = readEnvProperty("TARGET_DOCUMENT_CODE");
        List<EligibleArticleDef> articlesToProcess = (targetDocCode != null && !targetDocCode.isBlank())
                ? ELIGIBLE_ARTICLES.stream()
                        .filter(a -> a.documentCode().equalsIgnoreCase(targetDocCode.trim()))
                        .toList()
                : ELIGIBLE_ARTICLES;

        if (targetDocCode != null && !targetDocCode.isBlank()) {
            assertThat(articlesToProcess)
                    .withFailMessage("Target document code %s not found in ELIGIBLE_ARTICLES", targetDocCode)
                    .isNotEmpty();
            log.info("Running pilot execution targeting single document: {}", articlesToProcess.get(0).documentCode());
        }

        List<UUID> queuedRunIds = new ArrayList<>();

        for (EligibleArticleDef article : articlesToProcess) {
            UUID docId = null;
            UUID versionId = null;

            List<UUID> docIds = jdbcTemplate.query(
                    "SELECT id FROM fitness.knowledge_documents WHERE document_code = ?",
                    (rs, rowNum) -> (UUID) rs.getObject("id"),
                    article.documentCode());

            if (!docIds.isEmpty()) {
                docId = docIds.get(0);
                List<UUID> verIds = jdbcTemplate.query(
                        "SELECT id FROM fitness.knowledge_versions WHERE knowledge_document_id = ? AND version_label = ?",
                        (rs, rowNum) -> (UUID) rs.getObject("id"),
                        docId, article.versionLabel());
                if (!verIds.isEmpty()) {
                    versionId = verIds.get(0);
                }
            }

            if (docId == null || versionId == null) {
                log.info("Importing bundle for article {}...", article.documentCode());
                Path contentPath = resolveFile(article.contentPath());
                String content = Files.readString(contentPath, StandardCharsets.UTF_8);

                String bundleJson = """
                        {
                          "formatVersion": 1,
                          "purpose": "F01_GENERAL_KNOWLEDGE",
                          "bundleName": "eligible-corpus-bundle-%s",
                          "items": [
                            {
                              "documentCode": "%s",
                              "sourceId": "%s",
                              "title": "%s",
                              "sourceName": "%s",
                              "versionLabel": "%s",
                              "versionNumber": %d,
                              "contentHash": "%s",
                              "contentPath": "%s",
                              "sourceBytes": %d,
                              "reviewEvidenceId": "%s",
                              "technicalReadiness": "TECHNICAL_VERIFICATION_PASSED",
                              "lineageBaseDocumentId": "%s",
                              "lineageBaseVersion": "%s",
                              "lineageBaseHash": "%s",
                              "content": %s
                            }
                          ]
                        }
                        """.formatted(
                        article.documentCode().toLowerCase(),
                        article.documentCode(),
                        article.sourceId(),
                        article.title(),
                        article.sourceName(),
                        article.versionLabel(),
                        article.versionNumber(),
                        article.contentHash(),
                        article.contentPath(),
                        article.sourceBytes(),
                        article.reviewEvidenceId(),
                        article.baseDocumentId(),
                        article.baseVersion(),
                        article.baseHash(),
                        objectMapper.writeValueAsString(content)
                );

                ImportCorpusBundle bundle = objectMapper.readValue(bundleJson, ImportCorpusBundle.class);
                importCorpusUseCase.importBundle(ADMIN_ID, bundle);

                docId = jdbcTemplate.queryForObject(
                        "SELECT id FROM fitness.knowledge_documents WHERE document_code = ?",
                        UUID.class, article.documentCode());
                versionId = jdbcTemplate.queryForObject(
                        "SELECT id FROM fitness.knowledge_versions WHERE knowledge_document_id = ? AND version_label = ?",
                        UUID.class, docId, article.versionLabel());

                lifecycleService.publishVersion(ADMIN_ID, versionId);
            } else {
                String verStatus = jdbcTemplate.queryForObject(
                        "SELECT status::text FROM fitness.knowledge_versions WHERE id = ?", String.class, versionId);
                if ("DRAFT".equals(verStatus)) {
                    lifecycleService.publishVersion(ADMIN_ID, versionId);
                }
            }

            // Verify version and vector validity before deciding to reuse
            Integer chunkCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?",
                    Integer.class, versionId);
            Integer vectorCount = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM fitness.knowledge_embeddings
                    WHERE embedding_model_version_id = ?
                      AND knowledge_chunk_id IN (SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?)
                    """, Integer.class, embeddingModelPort.modelVersionId(), versionId);

            String currentStatus = jdbcTemplate.queryForObject(
                    "SELECT status::text FROM fitness.knowledge_versions WHERE id = ?", String.class, versionId);
            String currentReadiness = jdbcTemplate.queryForObject(
                    "SELECT technical_readiness FROM fitness.knowledge_versions WHERE id = ?", String.class, versionId);
            String currentHash = jdbcTemplate.queryForObject(
                    "SELECT content_hash FROM fitness.knowledge_versions WHERE id = ?", String.class, versionId);

            boolean isAlreadyIndexedAndValid = "ACTIVE".equals(currentStatus)
                    && "TECHNICAL_VERIFICATION_PASSED".equals(currentReadiness)
                    && article.contentHash().equalsIgnoreCase(currentHash)
                    && chunkCount != null && chunkCount > 0
                    && vectorCount != null && vectorCount.equals(chunkCount);

            if (isAlreadyIndexedAndValid) {
                log.info("Article {} version {} (hash={}) is validly ACTIVE with {} chunks and {} OpenAI vectors. Reusing without reindex.",
                        article.documentCode(), article.versionLabel(), currentHash, chunkCount, vectorCount);
            } else {
                // Find QUEUED outbox run
                List<UUID> runIds = jdbcTemplate.query("""
                        SELECT id FROM fitness.knowledge_processing_runs
                        WHERE knowledge_version_id = ? AND status = 'QUEUED'
                        ORDER BY created_at DESC
                        """, (rs, rowNum) -> (UUID) rs.getObject("id"), versionId);
                if (!runIds.isEmpty()) {
                    queuedRunIds.add(runIds.get(0));
                } else {
                    String opKey = "INDEX:" + versionId;
                    jdbcTemplate.update("""
                            INSERT INTO fitness.knowledge_processing_runs (
                                id, knowledge_version_id, run_type, status, operation_key, statistics, created_at, attempt_count, max_attempts
                            ) VALUES (
                                gen_random_uuid(), ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, now(), 0, 3
                            ) ON CONFLICT (operation_key) DO UPDATE SET status = 'QUEUED', next_attempt_at = now()
                            """, versionId, opKey);
                    UUID newRunId = jdbcTemplate.queryForObject(
                            "SELECT id FROM fitness.knowledge_processing_runs WHERE operation_key = ?", UUID.class, opKey);
                    queuedRunIds.add(newRunId);
                }
            }
        }

        // Process queued runs one by one
        log.info("Processing {} queued outbox runs...", queuedRunIds.size());
        for (UUID runId : queuedRunIds) {
            int processed = outboxConsumer.processPendingRuns(1);
            assertThat(processed).isEqualTo(1);

            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, runId);
            assertThat(status).isEqualTo("SUCCEEDED");
        }

        // 6. Verify database state & Retrieval
        if (targetDocCode != null && !targetDocCode.isBlank()) {
            EligibleArticleDef target = articlesToProcess.get(0);
            UUID targetDocId = jdbcTemplate.queryForObject(
                    "SELECT id FROM fitness.knowledge_documents WHERE document_code = ?",
                    UUID.class, target.documentCode());
            UUID targetVerId = jdbcTemplate.queryForObject(
                    "SELECT id FROM fitness.knowledge_versions WHERE knowledge_document_id = ? AND version_label = ?",
                    UUID.class, targetDocId, target.versionLabel());

            String verStatus = jdbcTemplate.queryForObject(
                    "SELECT status::text FROM fitness.knowledge_versions WHERE id = ?", String.class, targetVerId);
            assertThat(verStatus).isEqualTo("ACTIVE");

            Integer targetVectors = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM fitness.knowledge_embeddings
                    WHERE embedding_model_version_id = ?
                      AND knowledge_chunk_id IN (SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?)
                    """, Integer.class, embeddingModelPort.modelVersionId(), targetVerId);
            assertThat(targetVectors).isGreaterThan(0);

            // Verify WHO-01 remains intact
            Integer who01VectorsAfterPilot = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM fitness.knowledge_embeddings e
                    JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id
                    JOIN fitness.knowledge_versions v ON v.id = c.knowledge_version_id
                    JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                    WHERE d.document_code = 'WHO-PA-SB-2020-VI-01'
                      AND e.embedding_model_version_id = ?
                    """, Integer.class, embeddingModelPort.modelVersionId());
            assertThat(who01VectorsAfterPilot).isEqualTo(5);

            // Verify retrieval for target document
            List<RetrievedKnowledgeChunk> pilotResults = retrievalService.retrieveActiveKnowledge(
                    target.title(),
                    List.of(target.sourceId()),
                    5
            );
            assertThat(pilotResults).isNotEmpty();
            log.info("Pilot live verification PASSED for document {}: {} vectors created, retrieval verified.",
                    target.documentCode(), targetVectors);
        } else {
            // Full eligible corpus verification (all 9 documents, 29 vectors)
            Integer totalActiveDocs = jdbcTemplate.queryForObject("""
                    SELECT count(DISTINCT d.id)
                    FROM fitness.knowledge_documents d
                    JOIN fitness.knowledge_versions v ON v.knowledge_document_id = d.id
                    WHERE v.status = 'ACTIVE'
                      AND v.technical_readiness = 'TECHNICAL_VERIFICATION_PASSED'
                    """, Integer.class);
            assertThat(totalActiveDocs).isEqualTo(9);

            Integer totalVectors = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM fitness.knowledge_embeddings
                    WHERE embedding_model_version_id = ?
                    """, Integer.class, embeddingModelPort.modelVersionId());
            assertThat(totalVectors).isEqualTo(29);

            // Verify Retrieval across both sources
            List<RetrievedKnowledgeChunk> whoResults = retrievalService.retrieveActiveKnowledge(
                    "Khuyến nghị hoạt động thể lực cho người trưởng thành",
                    List.of("WHO-PA-SB-2020"),
                    5
            );
            assertThat(whoResults).isNotEmpty();

            List<RetrievedKnowledgeChunk> niddkResults = retrievalService.retrieveActiveKnowledge(
                    "Các yếu tố lối sống ảnh hưởng đến cân nặng",
                    List.of("NIDDK-WEIGHT-FACTORS-2023"),
                    5
            );
            assertThat(niddkResults).isNotEmpty();

            log.info("Eligible corpus live verification PASSED: 9 active docs, 29 vectors, semantic retrieval verified for WHO and NIDDK.");
        }
    }
}
