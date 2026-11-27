package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.content.adapter.in.scheduled.KnowledgeIndexingOutboxScheduler;
import com.fitnesscoaching.platform.modules.content.application.port.in.ContentLifecycleUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.in.ImportKnowledgeCorpusUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import com.fitnesscoaching.platform.modules.content.domain.ImportCorpusBundle;
import com.fitnesscoaching.platform.modules.content.domain.ImportCorpusResult;
import com.fitnesscoaching.platform.modules.content.domain.ImportKnowledgeItem;
import com.fitnesscoaching.platform.modules.content.domain.ReviewDecision;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "ai.knowledge.outbox-worker.enabled=true",
                "ai.knowledge.outbox-worker.initial-delay-ms=600000",
                "ai.knowledge.outbox-worker.fixed-delay-ms=600000",
                "ai.knowledge.outbox-worker.batch-size=5"
        }
)
@ActiveProfiles("test")
class KnowledgeIndexingOutboxSchedulerIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("outbox_scheduler_integration")
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

    @Autowired private ImportKnowledgeCorpusUseCase importUseCase;
    @Autowired private ContentLifecycleUseCase lifecycleUseCase;
    @Autowired private KnowledgeIndexingOutboxScheduler scheduler;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private EmbeddingModelPort embeddingModelPort;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "INSERT INTO fitness.users (id, email, display_name, status) VALUES (?, 'outbox-admin@test.local', 'Outbox Admin', 'ACTIVE') ON CONFLICT (id) DO NOTHING",
                ADMIN_ID
        );
        jdbcTemplate.update(
                "INSERT INTO fitness.user_roles (user_id, role_id) SELECT ?, id FROM fitness.roles WHERE code IN ('ADMIN', 'CONTENT_ADMIN') ON CONFLICT DO NOTHING",
                ADMIN_ID
        );
    }

    @AfterEach
    void tearDown() {
        reset(embeddingModelPort);
    }

    @Test
    @DisplayName("Publish creates QUEUED outbox, scheduler drains to index_ready=true, and duplicate ticks stay idempotent")
    void publishCreatesOutbox_schedulerDrainsToIndexReady_andDuplicateTicksAreIdempotent() throws Exception {
        UUID versionId = importReviewAndPublish(
                "WHO-PA-SB-2020-VI-05",
                "derivatives/who-2020/05-adults-activity.vi.md"
        );

        String initialStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE knowledge_version_id = ?",
                String.class,
                versionId
        );
        assertThat(initialStatus).isEqualTo("QUEUED");

        int firstTickProcessed = scheduler.runScheduledIndexing();
        assertThat(firstTickProcessed).isEqualTo(1);

        String completedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE knowledge_version_id = ?",
                String.class,
                versionId
        );
        Boolean indexReady = jdbcTemplate.queryForObject(
                "SELECT (statistics->>'index_ready')::boolean FROM fitness.knowledge_processing_runs WHERE knowledge_version_id = ?",
                Boolean.class,
                versionId
        );
        Integer chunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?",
                Integer.class,
                versionId
        );
        Integer embeddingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_embeddings e JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id WHERE c.knowledge_version_id = ?",
                Integer.class,
                versionId
        );

        assertThat(completedStatus).isEqualTo("SUCCEEDED");
        assertThat(indexReady).isTrue();
        assertThat(chunkCount).isGreaterThan(0);
        assertThat(embeddingCount).isEqualTo(chunkCount);

        // Duplicate scheduler tick must not reprocess or duplicate chunks/embeddings
        int secondTickProcessed = scheduler.runScheduledIndexing();
        assertThat(secondTickProcessed).isEqualTo(0);

        Integer chunkCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?",
                Integer.class,
                versionId
        );
        assertThat(chunkCountAfter).isEqualTo(chunkCount);
    }

    @Test
    @DisplayName("Transient embedding failure requeues run for retry and subsequent scheduler tick succeeds")
    void transientFailure_requeuesAndRecoversOnNextTick() throws Exception {
        UUID versionId = importReviewAndPublish(
                "WHO-PA-SB-2020-VI-01",
                "derivatives/who-2020/01-scope.vi.md"
        );

        doThrow(new IllegalStateException("Simulated transient embedding timeout"))
                .when(embeddingModelPort).generateEmbedding(anyString());

        int failedTick = scheduler.runScheduledIndexing();
        assertThat(failedTick).isEqualTo(1);

        String statusAfterFailure = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE knowledge_version_id = ?",
                String.class,
                versionId
        );
        Integer attemptsAfterFailure = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM fitness.knowledge_processing_runs WHERE knowledge_version_id = ?",
                Integer.class,
                versionId
        );
        assertThat(statusAfterFailure).isEqualTo("QUEUED");
        assertThat(attemptsAfterFailure).isEqualTo(1);

        // Make next_attempt_at immediately eligible and restore real embedding generation
        jdbcTemplate.update(
                "UPDATE fitness.knowledge_processing_runs SET next_attempt_at = now() - interval '1 second' WHERE knowledge_version_id = ?",
                versionId
        );
        reset(embeddingModelPort);
        doReturn(unitVector()).when(embeddingModelPort).generateEmbedding(anyString());

        int recoveryTick = scheduler.runScheduledIndexing();
        assertThat(recoveryTick).isEqualTo(1);

        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE knowledge_version_id = ?",
                String.class,
                versionId
        );
        Boolean indexReady = jdbcTemplate.queryForObject(
                "SELECT (statistics->>'index_ready')::boolean FROM fitness.knowledge_processing_runs WHERE knowledge_version_id = ?",
                Boolean.class,
                versionId
        );
        assertThat(finalStatus).isEqualTo("SUCCEEDED");
        assertThat(indexReady).isTrue();
    }

    private UUID importReviewAndPublish(String docCode, String relativeDerivativePath) throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of("../../data/knowledge", relativeDerivativePath);
        String content = java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
        String hash = sha256(content);
        ImportCorpusBundle bundle = new ImportCorpusBundle(
                1,
                "OUTBOX_SCHEDULER_TEST",
                "scheduler-test-bundle",
                List.of(new ImportKnowledgeItem(
                        docCode,
                        "WHO-PA-SB-2020",
                        "Test " + docCode,
                        "WHO-PA-SB-2020",
                        null,
                        "WHO",
                        "general_knowledge",
                        "vi",
                        "1.1.0",
                        1,
                        hash,
                        relativeDerivativePath,
                        content.getBytes(StandardCharsets.UTF_8).length,
                        "REV-PO-20261008-01",
                        TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED,
                        null,
                        docCode,
                        "1.0.0",
                        null,
                        content
                ))
        );
        ImportCorpusResult imported = importUseCase.importBundle(ADMIN_ID, bundle);
        UUID versionId = imported.importedItems().getFirst().versionId();
        lifecycleUseCase.reviewVersion(ADMIN_ID, versionId, ReviewDecision.APPROVED, "Scheduler test approval");
        lifecycleUseCase.publishVersion(ADMIN_ID, versionId);
        return versionId;
    }

    private static float[] unitVector() {
        float[] v = new float[1536];
        v[0] = 1.0f;
        return v;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
