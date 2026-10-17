package com.fitnesscoaching.platform;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.SimulatedF01AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunKnowledgeChunk;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeChunkRepository;
import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeIndexingOutboxConsumer;
import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeRetrievalService;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeSourceIdentity;
import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.adapter.out.embedding.DeterministicEmbeddingGenerator;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryRequest;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryResult;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;
import com.fitnesscoaching.platform.modules.ai.application.service.F01RuntimeOutputValidator;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiKnowledgeRetrievalIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID STUDENT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String INTERNAL_SERVICE_KEY = "test-internal-service-key-32chars";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("retrieval_integration")
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
        registry.add("ai.service.internal-service-key", () -> INTERNAL_SERVICE_KEY);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private KnowledgeIndexingOutboxConsumer outboxConsumer;
    @Autowired private KnowledgeRetrievalUseCase retrievalUseCase;
    @Autowired private KnowledgeChunkRepository chunkRepository;
    @Autowired private AiRunRepository aiRunRepository;
    @Autowired private AiProvenanceRepository provenanceRepository;
    @Autowired private SimulatedF01AiServiceClient simulatedAiClient;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private DeterministicEmbeddingGenerator embeddingGenerator;
    @Autowired private KnowledgeQueryUseCase knowledgeQueryUseCase;
    @Autowired private F01RuntimeOutputValidator runtimeValidator;

    @BeforeEach
    void setUp() {
        simulatedAiClient.reset();
        jdbcTemplate.execute("DELETE FROM fitness.ai_run_knowledge_chunks");
        jdbcTemplate.execute("DELETE FROM fitness.ai_runs");
        jdbcTemplate.execute("TRUNCATE TABLE fitness.knowledge_documents, fitness.knowledge_processing_runs CASCADE");

        // Seed Admin user
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, 'admin@fitplatform.local', 'hash', 'Admin User', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                """, ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code IN ('ADMIN', 'CONTENT_ADMIN')
                ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL
                """, ADMIN_ID, ADMIN_ID);

        // Seed Student user
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, 'student@fitplatform.local', 'hash', 'Student User', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE'
                """, STUDENT_ID);
        jdbcTemplate.update("""
                INSERT INTO fitness.student_profiles (user_id, created_at, updated_at)
                VALUES (?, clock_timestamp(), clock_timestamp())
                ON CONFLICT (user_id) DO NOTHING
                """, STUDENT_ID);
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'STUDENT'
                ON CONFLICT (user_id, role_id) DO UPDATE SET revoked_at = NULL
                """, STUDENT_ID, ADMIN_ID);
    }

    @AfterEach
    void tearDown() {
        simulatedAiClient.reset();
    }

    private String bearer(UUID userId, String... roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(userId.toString())
                .claim("email", "test@fitplatform.local")
                .claim("roles", List.of(roles))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private Path resolveKnowledgeFile(String relative) {
        List<Path> candidates = List.of(
                Path.of("..", "..", "data", "knowledge", relative),
                Path.of("data", "knowledge", relative),
                Path.of("..", "..", relative),
                Path.of(relative)
        );
        for (Path p : candidates) {
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("Knowledge file not found on disk: " + relative);
    }

    @Test
    @DisplayName("A-27 Full End-to-End: Cold Start (ACTIVE=0) -> Import & Index -> pgvector Retrieval -> Internal API -> AI Orchestration & Citation Provenance -> Invalidation")
    void fullRealRetrievalLifecycle() throws Exception {
        String adminToken = bearer(ADMIN_ID, "ADMIN", "CONTENT_ADMIN");
        String studentToken = bearer(STUDENT_ID, "STUDENT");

        // -------------------------------------------------------------
        // STEP 1: COLD START VERIFICATION (ACTIVE = 0)
        // -------------------------------------------------------------
        // Without active indexed knowledge, querying F01 must return INSUFFICIENT_DATA (NO_RELEVANT_EVIDENCE)
        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "Khuyến nghị vận động thể lực WHO 2020 là gì?",
                                  "language": "vi"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INSUFFICIENT_DATA")))
                .andExpect(jsonPath("$.reasonCode", is("NO_RELEVANT_EVIDENCE")))
                .andExpect(jsonPath("$.citations", hasSize(0)));

        // -------------------------------------------------------------
        // STEP 2: IMPORT CORPUS ARTICLES (WHO = VERIFIED, NIDDK = HELD_AS_DRAFT)
        // -------------------------------------------------------------
        String whoContent = Files.readString(resolveKnowledgeFile("derivatives/who-2020/01-scope.vi.md"), StandardCharsets.UTF_8);
        String whoHash = sha256(whoContent);

        String niddkContent = Files.readString(resolveKnowledgeFile("derivatives/niddk-weight-factors/05-medicines.vi.md"), StandardCharsets.UTF_8);
        String niddkHash = sha256(niddkContent);

        String bundleJson = """
                {
                  "formatVersion": 1,
                  "purpose": "F01_GENERAL_KNOWLEDGE",
                  "bundleName": "f01-a27-verification-bundle",
                  "items": [
                    {
                      "documentCode": "WHO-PA-SB-2020-VI-01",
                      "sourceId": "WHO-PA-SB-2020",
                      "title": "WHO Physical Activity Guidelines",
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
                    },
                    {
                      "documentCode": "NIDDK-WEIGHT-FACTORS-2023-VI-05",
                      "sourceId": "NIDDK-WEIGHT-FACTORS-2023",
                      "title": "NIDDK Medicines",
                      "sourceName": "National Institute of Diabetes and Digestive and Kidney Diseases",
                      "versionLabel": "1.1.0",
                      "versionNumber": 1,
                      "contentHash": "%s",
                      "contentPath": "derivatives/niddk-weight-factors/05-medicines.vi.md",
                      "sourceBytes": %d,
                      "reviewEvidenceId": "REV-PO-20261008-01",
                      "technicalReadiness": "HELD_AS_DRAFT",
                      "lineageBaseDocumentId": "NIDDK-WEIGHT-FACTORS-2023-VI-05",
                      "lineageBaseVersion": "1.0.0",
                      "lineageBaseHash": "6b344cca9a7a69e055d6067b6cdf69ce6acf6ee90d0f404b5114fcbffb5241c0",
                      "content": %s
                    }
                  ]
                }
                """.formatted(
                whoHash, whoContent.getBytes(StandardCharsets.UTF_8).length, objectMapper.writeValueAsString(whoContent),
                niddkHash, niddkContent.getBytes(StandardCharsets.UTF_8).length, objectMapper.writeValueAsString(niddkContent)
        );

        mockMvc.perform(post("/api/v1/admin/content/knowledge/import")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bundleJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount", is(2)));

        UUID whoVersionId = jdbcTemplate.queryForObject("""
                SELECT v.id FROM fitness.knowledge_versions v
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE d.document_code = 'WHO-PA-SB-2020-VI-01'
                """, UUID.class);

        UUID niddkVersionId = jdbcTemplate.queryForObject("""
                SELECT v.id FROM fitness.knowledge_versions v
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE d.document_code = 'NIDDK-WEIGHT-FACTORS-2023-VI-05'
                """, UUID.class);

        // NIDDK cannot be published because it is HELD_AS_DRAFT
        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + niddkVersionId + "/publish")
                        .header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("CONTENT_LIFECYCLE_CONFLICT")));

        // -------------------------------------------------------------
        // STEP 3: PUBLISH & OUTBOX INDEXING (WHO-PA-SB-2020-VI-01)
        // -------------------------------------------------------------
        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + whoVersionId + "/publish")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk());

        // Verify outbox worker processes indexing
        int processedCount = outboxConsumer.processPendingRuns(5);
        assertThat(processedCount).isEqualTo(1);

        String outboxStatsJson = jdbcTemplate.queryForObject("""
                SELECT statistics::text FROM fitness.knowledge_processing_runs
                WHERE knowledge_version_id = ? AND run_type = 'REINDEX'
                """, String.class, whoVersionId);
        JsonNode outboxStats = objectMapper.readTree(outboxStatsJson);
        assertThat(outboxStats.path("mode").asText()).isEqualTo("REAL_RETRIEVAL_INDEXING");
        assertThat(outboxStats.path("real_embeddings").asBoolean()).isFalse();
        assertThat(outboxStats.path("embedding_mode").asText()).isEqualTo("SIMULATED_DETERMINISTIC");
        assertThat(outboxStats.path("embedding_provider").asText()).isEqualTo("simulated");
        assertThat(outboxStats.path("index_ready").asBoolean()).isTrue();
        assertThat(outboxStats.path("dimension").asInt()).isEqualTo(1536);
        int indexedChunks = outboxStats.path("indexed_chunks").asInt();
        assertThat(indexedChunks).isGreaterThanOrEqualTo(3);

        // Verify database persistence of chunks and embeddings
        Integer chunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?",
                Integer.class, whoVersionId
        );
        assertThat(chunkCount).isEqualTo(indexedChunks);

        Integer embeddingCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fitness.knowledge_embeddings e
                JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id
                WHERE c.knowledge_version_id = ?
                """, Integer.class, whoVersionId);
        assertThat(embeddingCount).isEqualTo(indexedChunks);

        // -------------------------------------------------------------
        // STEP 4: RETRIEVAL USE CASE & INTERNAL HTTP ENDPOINT
        // -------------------------------------------------------------
        // 4A: Java Port retrieval
        List<RetrievedKnowledgeChunk> directChunks = retrievalUseCase.retrieveActiveKnowledge(
                "vận động thể lực cho người trưởng thành",
                List.of("WHO-PA-SB-2020"),
                5
        );
        assertThat(directChunks).isNotEmpty();
        assertThat(directChunks.get(0).documentCode()).isEqualTo("WHO-PA-SB-2020-VI-01");
        assertThat(directChunks.get(0).versionLabel()).isEqualTo("1.1.0");
        assertThat(directChunks.get(0).similarityScore()).isGreaterThan(0.0);
        assertThat(directChunks.get(0).content()).contains("WHO 2020");

        // 4B: Internal retrieval HTTP endpoint (missing key -> 401)
        mockMvc.perform(post("/internal/v1/knowledge/retrieval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "người trưởng thành vận động bao nhiêu phút",
                                  "allowedSourceIds": ["WHO-PA-SB-2020"],
                                  "limit": 3
                                }
                                """))
                .andExpect(status().isUnauthorized());

        // 4C: Internal retrieval HTTP endpoint (valid key -> 200 with retrieved chunks)
        mockMvc.perform(post("/internal/v1/knowledge/retrieval")
                        .header("X-Internal-Service-Key", INTERNAL_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "người trưởng thành vận động bao nhiêu phút",
                                  "allowedSourceIds": ["WHO-PA-SB-2020"],
                                  "limit": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunks", hasSize(3)))
                .andExpect(jsonPath("$.chunks[0].chunkId", notNullValue()))
                .andExpect(jsonPath("$.chunks[0].documentCode", is("WHO-PA-SB-2020-VI-01")))
                .andExpect(jsonPath("$.chunks[0].similarityScore", notNullValue()));

        // -------------------------------------------------------------
        // STEP 5: AI ORCHESTRATION WITH REAL RETRIEVAL & CITATION PROVENANCE
        // -------------------------------------------------------------
        // Step 5 uses SimulatedF01AiServiceClient's built-in retrievalUseCase grounding
        RetrievedKnowledgeChunk topChunk = directChunks.get(0);

        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "Nguyên tắc vận động theo WHO 2020?",
                                  "language": "vi"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INFORMATION")))
                .andExpect(jsonPath("$.citations", hasSize(3)))
                .andExpect(jsonPath("$.citations[0].documentId", is("WHO-PA-SB-2020-VI-01")))
                .andExpect(jsonPath("$.citations[0].version", is("1.1.0")))
                .andExpect(jsonPath("$.citations[0].sourceId", is("WHO-PA-SB-2020")));

        // Verify persistence in fitness.ai_run_knowledge_chunks
        List<AiRunKnowledgeChunk> persistedChunks = jdbcTemplate.query("""
                SELECT ai_run_id, knowledge_chunk_id, rank, similarity_score
                FROM fitness.ai_run_knowledge_chunks
                ORDER BY rank ASC
                """, (rs, rowNum) -> new AiRunKnowledgeChunk(
                UUID.fromString(rs.getString("ai_run_id")),
                UUID.fromString(rs.getString("knowledge_chunk_id")),
                rs.getInt("rank"),
                rs.getBigDecimal("similarity_score")
        ));
        assertThat(persistedChunks).isNotEmpty();
        assertThat(persistedChunks.get(0).knowledgeChunkId()).isEqualTo(topChunk.chunkId());
        assertThat(persistedChunks.get(0).rank()).isEqualTo(1);
        assertThat(persistedChunks.get(0).similarityScore()).isNotNull();

        // -------------------------------------------------------------
        // STEP 6: DYNAMIC INVALIDATION VIA ARCHIVING
        // -------------------------------------------------------------
        // When active version is archived, vector retrieval must immediately exclude it
        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + whoVersionId + "/archive")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Superseded by updated edition\"}"))
                .andExpect(status().isOk());

        // Vector retrieval immediately returns empty for archived source
        List<RetrievedKnowledgeChunk> archivedChunks = retrievalUseCase.retrieveActiveKnowledge(
                "vận động thể lực cho người trưởng thành",
                List.of("WHO-PA-SB-2020"),
                5
        );
        assertThat(archivedChunks).isEmpty();

        // AI query immediately yields INSUFFICIENT_DATA (NO_RELEVANT_EVIDENCE)
        mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "Khuyến nghị vận động WHO 2020 là gì?",
                                  "language": "vi"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INSUFFICIENT_DATA")))
                .andExpect(jsonPath("$.reasonCode", is("NO_RELEVANT_EVIDENCE")))
                .andExpect(jsonPath("$.citations", hasSize(0)));
    }

    @Test
    @DisplayName("AI response with unverified/hallucinated citation is strictly rejected with UNVERIFIED_CITATION")
    void unverifiedCitationRejectedByValidator() throws Exception {
        String studentToken = bearer(STUDENT_ID, "STUDENT");

        simulatedAiClient.setCustomBehavior(envelope -> new com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse(
                envelope.requestId(),
                envelope.correlationId(),
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Phát biểu không có cơ sở xác thực.",
                List.of(new com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse.CitationDto(
                        "ODS-EXERCISE-SUPPLEMENTS-2024-VI-01",
                        "1.0.0",
                        "ODS-EXERCISE-SUPPLEMENTS-2024",
                        new com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse.LocatorDto("path", "head", List.of(1, 2), 0),
                        "Fabricated excerpt"
                )),
                List.of(),
                List.of(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                new com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse.UsageDto(100, 20, 120, 100),
                null,
                new com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse.GenerationMetadataDto("1.0.0", "v1.0", "rule-v1", "simulated", "simulated-f01-v1", null, null)
        ));

        String responseBody = mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "Câu hỏi với citation bịa đặt?",
                                  "language": "vi"
                                }
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.runStatus", is("REJECTED_BY_VALIDATOR")))
                .andExpect(jsonPath("$.reasonCode", is("UNVERIFIED_CITATION")))
                .andExpect(jsonPath("$.error.code", is("UNVERIFIED_CITATION")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        UUID runId = UUID.fromString(objectMapper.readTree(responseBody).path("runId").asText());
        AiRun run = aiRunRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AiRunStatus.REJECTED_BY_VALIDATOR);
        assertThat(run.errorCode()).isEqualTo("UNVERIFIED_CITATION");
    }

    @Test
    @DisplayName("Reindexing preserves cited chunk content and hash, and prunes unreferenced chunks beyond count")
    void reindexPreservesCitedChunkAndPrunesUnreferencedChunks() {
        // Seed document, version and 3 chunks
        UUID docId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String docCode = "WHO-REINDEX-TEST-01";
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                VALUES (?, ?, 'Reindex Test', 'WHO-PA-SB-2020', 'vi', ?)
                """, docId, docCode, ADMIN_ID);

        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_versions(
                    id, knowledge_document_id, version_number, version_label,
                    status, content_hash, technical_readiness, content, created_by
                ) VALUES (
                    ?, ?, 1, '1.0.0', 'ACTIVE', 'hash123', 'TECHNICAL_VERIFICATION_PASSED', 'test content', ?
                )
                """, versionId, docId, ADMIN_ID);

        UUID chunk0Id = UUID.randomUUID();
        UUID chunk1Id = UUID.randomUUID();
        UUID chunk2Id = UUID.randomUUID();

        String originalContent0 = "Original chunk 0 content cited by AI Run";
        String originalContent1 = "Original chunk 1 content cited by AI Run";
        String uncitedContent2 = "Uncited chunk 2 content";

        chunkRepository.saveBatch(List.of(
                new com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk(
                        chunk0Id, versionId, 0, "H1", originalContent0, 10, "{\"chunkHash\": \"hash0\"}", Instant.now()
                ),
                new com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk(
                        chunk1Id, versionId, 1, "H2", originalContent1, 10, "{\"chunkHash\": \"hash1\"}", Instant.now()
                ),
                new com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk(
                        chunk2Id, versionId, 2, "H3", uncitedContent2, 10, "{\"chunkHash\": \"hash2\"}", Instant.now()
                )
        ));

        // Create an AI run that cites chunk0 and chunk1
        UUID aiRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.ai_runs(id, request_type, status, created_at)
                VALUES (?, 'KNOWLEDGE_QUERY', 'SUCCEEDED', clock_timestamp())
                """, aiRunId);

        jdbcTemplate.update("""
                INSERT INTO fitness.ai_run_knowledge_chunks(ai_run_id, knowledge_chunk_id, rank, similarity_score)
                VALUES (?, ?, 1, 0.95), (?, ?, 2, 0.85)
                """, aiRunId, chunk0Id, aiRunId, chunk1Id);

        // 1. Verify conflict on cited chunk with MODIFIED content is rejected fail-closed (preserves original content)
        assertThatThrownBy(() -> chunkRepository.saveBatch(List.of(
                new com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk(
                        chunk0Id, versionId, 0, "H1", "MUTATED CONTENT ATTEMPT", 10, "{\"chunkHash\": \"mutatedHash\"}", Instant.now()
                )
        ))).isInstanceOf(com.fitnesscoaching.platform.common.exception.ContentConflictException.class)
          .hasMessageContaining("Cannot mutate content of cited chunk");

        String contentAfterAttempt = jdbcTemplate.queryForObject(
                "SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunk0Id);
        assertThat(contentAfterAttempt).isEqualTo(originalContent0);

        // 2. Prune unreferenced chunks beyond index 1 (activeCount = 1)
        chunkRepository.deleteUnreferencedChunksBeyondIndex(versionId, 1);

        // chunk2 (uncited) must be pruned
        Integer chunk2Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE id = ?", Integer.class, chunk2Id);
        assertThat(chunk2Count).isEqualTo(0);

        // chunk1 (cited by AI run) must be preserved in knowledge_chunks for citation history
        Integer chunk1Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE id = ?", Integer.class, chunk1Id);
        assertThat(chunk1Count).isEqualTo(1);

        // But chunk1 has no embedding for current index, so isChunkEligible returns false
        boolean chunk1Eligible = retrievalUseCase.isChunkEligible(docCode, "1.0.0", 1);
        assertThat(chunk1Eligible).isFalse();
    }

    @Test
    @DisplayName("Regression test: worker reindexing with mutated content on cited chunk must fail-closed and not mark index_ready")
    void workerReindexingWithMutatedContentOnCitedChunkMustFailClosed() {
        UUID docId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String docCode = "WHO-REGRESSION-CITED-01";
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                VALUES (?, ?, 'Cited Reindex Test', 'WHO-PA-SB-2020', 'vi', ?)
                """, docId, docCode, ADMIN_ID);

        String initialContent = "# Section 1\n\nOriginal cited chunk content.";
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_versions(
                    id, knowledge_document_id, version_number, version_label,
                    status, content_hash, technical_readiness, content, created_by
                ) VALUES (
                    ?, ?, 1, '1.0.0', 'ACTIVE', 'hashInitial', 'TECHNICAL_VERIFICATION_PASSED', ?, ?
                )
                """, versionId, docId, initialContent, ADMIN_ID);

        // Enqueue and run initial indexing
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, UUID.randomUUID(), versionId, "INITIAL:" + versionId);

        boolean processedInitial = outboxConsumer.processNextRun("test-worker-1");
        assertThat(processedInitial).isTrue();

        UUID chunkId = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 0",
                UUID.class, versionId);
        String chunkContentBefore = jdbcTemplate.queryForObject(
                "SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        assertThat(chunkContentBefore).isEqualTo("# Section 1\n\nOriginal cited chunk content.");

        // An AI Run cites this chunk
        UUID aiRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.ai_runs(id, request_type, status, created_at)
                VALUES (?, 'KNOWLEDGE_QUERY', 'SUCCEEDED', clock_timestamp())
                """, aiRunId);
        jdbcTemplate.update("""
                INSERT INTO fitness.ai_run_knowledge_chunks(ai_run_id, knowledge_chunk_id, rank, similarity_score)
                VALUES (?, ?, 1, 0.99)
                """, aiRunId, chunkId);

        // Mutate the version content (simulating reindex after content update or chunker change)
        String mutatedContent = "# Section 1\n\nMutated content completely different from original.";
        jdbcTemplate.execute("ALTER TABLE fitness.knowledge_versions DISABLE TRIGGER protect_knowledge_version_content");
        try {
            jdbcTemplate.update("UPDATE fitness.knowledge_versions SET content = ? WHERE id = ?", mutatedContent, versionId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE fitness.knowledge_versions ENABLE TRIGGER protect_knowledge_version_content");
        }

        // Enqueue reindexing run
        UUID reindexRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, reindexRunId, versionId, "REINDEX:MUTATED:" + versionId);

        // Process through worker
        outboxConsumer.processNextRun("test-worker-2");

        // Verification:
        // 1. The reindex run MUST NOT succeed and must NOT report index_ready=true!
        String runStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, reindexRunId);
        assertThat(runStatus).isEqualTo("FAILED");

        // 2. Vector in knowledge_embeddings must NEVER be the vector for mutated content!
        // Chunk content must remain original
        String chunkContentAfter = jdbcTemplate.queryForObject(
                "SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        assertThat(chunkContentAfter).isEqualTo(chunkContentBefore);
    }

    private UUID createTestDocumentAndVersion(String docCode, String sourceId, String content) {
        UUID docId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                VALUES (?, ?, 'Test Doc ' || ?, ?, 'vi', ?)
                """, docId, docCode, docCode, sourceId, ADMIN_ID);

        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_versions(
                    id, knowledge_document_id, version_number, version_label,
                    status, content_hash, content_path, technical_readiness, content, created_by
                ) VALUES (
                    ?, ?, 1, '1.0.0', 'ACTIVE', 'hash-' || ?, 'data/knowledge/' || ? || '.vi.md', 'TECHNICAL_VERIFICATION_PASSED', ?, ?
                )
                """, versionId, docId, docCode, docCode, content, ADMIN_ID);
        return versionId;
    }

    @Test
    @DisplayName("Reindex unchanged data succeeds idempotently without duplicate chunks or broken embeddings")
    void reindexUnchangedDataSucceedsIdempotently() {
        UUID versionId = createTestDocumentAndVersion("TEST-UNCHANGED-01", "WHO-PA-SB-2020",
                "# Section 1\n\nContent for section 1.\n\n# Section 2\n\nContent for section 2.");

        // 1st run
        UUID run1Id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, run1Id, versionId, "RUN1:" + versionId);
        boolean processed1 = outboxConsumer.processNextRun("test-worker-1");
        assertThat(processed1).isTrue();

        UUID chunk0Id = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 0", UUID.class, versionId);
        int embeddings1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_embeddings e JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id WHERE c.knowledge_version_id = ?",
                Integer.class, versionId);
        assertThat(embeddings1).isEqualTo(2);

        // 2nd run with unchanged data
        UUID run2Id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, run2Id, versionId, "RUN2:" + versionId);
        boolean processed2 = outboxConsumer.processNextRun("test-worker-2");
        assertThat(processed2).isTrue();

        String run2Status = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, run2Id);
        assertThat(run2Status).isEqualTo("SUCCEEDED");

        // Chunk ID is preserved, count is preserved
        UUID chunk0IdAfter = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 0", UUID.class, versionId);
        assertThat(chunk0IdAfter).isEqualTo(chunk0Id);

        int embeddings2 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_embeddings e JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id WHERE c.knowledge_version_id = ?",
                Integer.class, versionId);
        assertThat(embeddings2).isEqualTo(2);
    }

    @Test
    @DisplayName("Mutating provenance or locator on cited chunk must fail closed even if content and chunkHash are unchanged")
    void reindexMutatingProvenanceOrLocatorOnCitedChunkFailsClosedEvenIfContentAndHashUnchanged() {
        UUID versionId = createTestDocumentAndVersion("TEST-PROV-01", "WHO-PA-SB-2020",
                "# Initial Heading\n\nIdentical body content.");

        UUID run1Id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, run1Id, versionId, "RUN1:" + versionId);
        assertThat(outboxConsumer.processNextRun("test-worker-1")).isTrue();

        UUID chunkId = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 0", UUID.class, versionId);
        String originalHeading = jdbcTemplate.queryForObject(
                "SELECT heading_path FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        String originalContent = jdbcTemplate.queryForObject(
                "SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        String originalMeta = jdbcTemplate.queryForObject(
                "SELECT metadata::text FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);

        // Cite chunk 0 by an AI Run
        UUID aiRunId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO fitness.ai_runs(id, request_type, status, created_at) VALUES (?, 'KNOWLEDGE_QUERY', 'SUCCEEDED', clock_timestamp())", aiRunId);
        jdbcTemplate.update("INSERT INTO fitness.ai_run_knowledge_chunks(ai_run_id, knowledge_chunk_id, rank, similarity_score) VALUES (?, ?, 1, 0.95)", aiRunId, chunkId);

        // Sub-test A: Mutating heading_path (locator) on cited chunk must throw ContentConflictException
        KnowledgeChunk chunkWithMutatedHeading = new KnowledgeChunk(
                chunkId, versionId, 0, "Mutated Section Heading", originalContent, 10, originalMeta, Instant.now());
        assertThatThrownBy(() -> chunkRepository.saveBatch(List.of(chunkWithMutatedHeading)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.ContentConflictException.class)
                .hasMessageContaining("heading_path locator");

        // Sub-test B: Mutating sourceId (provenance) in metadata on cited chunk must throw ContentConflictException
        String mutatedSourceMeta = originalMeta.replace("\"WHO-PA-SB-2020\"", "\"MUTATED-SOURCE-ID\"");
        KnowledgeChunk chunkWithMutatedSource = new KnowledgeChunk(
                chunkId, versionId, 0, originalHeading, originalContent, 10, mutatedSourceMeta, Instant.now());
        assertThatThrownBy(() -> chunkRepository.saveBatch(List.of(chunkWithMutatedSource)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.ContentConflictException.class)
                .hasMessageContaining("provenance field 'sourceId'");

        // Sub-test C: Mutating lineageBaseHash (lineage) in metadata on cited chunk must throw ContentConflictException
        String metaWithLineage = originalMeta.replace("}", ", \"lineageBaseHash\": \"sha256-original\"}");
        String mutatedLineageMeta = originalMeta.replace("}", ", \"lineageBaseHash\": \"sha256-mutated\"}");
        // First establish lineageBaseHash on an uncited chunk or simulate initial state
        // On cited chunk, if lineageBaseHash is mutated:
        jdbcTemplate.update("UPDATE fitness.knowledge_chunks SET metadata = ?::jsonb WHERE id = ?", metaWithLineage, chunkId);
        KnowledgeChunk chunkWithMutatedLineage = new KnowledgeChunk(
                chunkId, versionId, 0, originalHeading, originalContent, 10, mutatedLineageMeta, Instant.now());
        assertThatThrownBy(() -> chunkRepository.saveBatch(List.of(chunkWithMutatedLineage)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.ContentConflictException.class)
                .hasMessageContaining("provenance field 'lineageBaseHash'");

        // Restore original metadata and verify DB state was fully preserved
        jdbcTemplate.update("UPDATE fitness.knowledge_chunks SET metadata = ?::jsonb WHERE id = ?", originalMeta, chunkId);
        String finalHeading = jdbcTemplate.queryForObject("SELECT heading_path FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        String finalContent = jdbcTemplate.queryForObject("SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        assertThat(finalHeading).isEqualTo(originalHeading);
        assertThat(finalContent).isEqualTo(originalContent);
    }

    @Test
    @DisplayName("Non-provenance operational metadata update on cited chunk succeeds when locator, content, and provenance are unchanged")
    void reindexNonProvenanceOperationalMetadataUpdateOnCitedChunkSucceeds() {
        UUID versionId = createTestDocumentAndVersion("TEST-OPERATIONAL-01", "WHO-PA-SB-2020",
                "# Section 1\n\nContent for operational metadata test.");

        UUID run1Id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, run1Id, versionId, "RUN1:" + versionId);
        assertThat(outboxConsumer.processNextRun("test-worker-1")).isTrue();

        UUID chunkId = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 0", UUID.class, versionId);
        String originalHeading = jdbcTemplate.queryForObject(
                "SELECT heading_path FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        String originalContent = jdbcTemplate.queryForObject(
                "SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        String originalMeta = jdbcTemplate.queryForObject(
                "SELECT metadata::text FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);

        // Cite chunk 0
        UUID aiRunId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO fitness.ai_runs(id, request_type, status, created_at) VALUES (?, 'KNOWLEDGE_QUERY', 'SUCCEEDED', clock_timestamp())", aiRunId);
        jdbcTemplate.update("INSERT INTO fitness.ai_run_knowledge_chunks(ai_run_id, knowledge_chunk_id, rank, similarity_score) VALUES (?, ?, 1, 0.95)", aiRunId, chunkId);

        // Add non-provenance operational field (e.g. worker audit tag) preserving all immutable provenance fields
        String updatedMeta = originalMeta.replace("}", ", \"last_scanned_by\": \"worker-audit-42\"}");
        KnowledgeChunk updatedChunk = new KnowledgeChunk(
                chunkId, versionId, 0, originalHeading, originalContent, 10, updatedMeta, Instant.now());

        chunkRepository.saveBatch(List.of(updatedChunk));

        // Verify metadata was updated with the operational field
        String metaAfter = jdbcTemplate.queryForObject(
                "SELECT metadata::text FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        assertThat(metaAfter).contains("last_scanned_by").contains("worker-audit-42");

        // Verify content and heading remain identical
        String contentAfter = jdbcTemplate.queryForObject("SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        String headingAfter = jdbcTemplate.queryForObject("SELECT heading_path FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        assertThat(contentAfter).isEqualTo(originalContent);
        assertThat(headingAfter).isEqualTo(originalHeading);
    }

    @Test
    @DisplayName("Older AI Run retains exact citation data, locator, provenance, and content across unchanged reindex, fails closed on mutation, and permits operational metadata")
    void olderAiRunRetainsExactCitationDataAcrossReindex() throws Exception {
        UUID versionId = createTestDocumentAndVersion("TEST-HISTORIC-VI-01", "WHO-PA-SB-2020",
                "# Section 1\n\nContent for historic citation verification.");

        // Step 1: Initial outbox indexing run
        UUID run1Id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, run1Id, versionId, "RUN1:" + versionId);
        assertThat(outboxConsumer.processNextRun("test-worker-1")).isTrue();

        UUID chunkId = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 0", UUID.class, versionId);

        // Step 2: Create real AI Run through application endpoint
        String studentToken = bearer(STUDENT_ID, "STUDENT");
        String queryResponseJson = mockMvc.perform(post("/api/v1/ai/knowledge/query")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "Content for historic citation verification?",
                                  "language": "vi"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.resultType", is("INFORMATION")))
                .andExpect(jsonPath("$.citations", hasSize(1)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode initialResponseNode = objectMapper.readTree(queryResponseJson);
        UUID aiRunId = UUID.fromString(initialResponseNode.path("runId").asText());
        JsonNode citationOutput = initialResponseNode.path("citations").get(0);
        assertThat(citationOutput.path("documentId").asText()).isEqualTo("TEST-HISTORIC-VI-01");
        assertThat(citationOutput.path("sourceId").asText()).isEqualTo("WHO-PA-SB-2020");

        // Step 3: Snapshot all exact chunk fields and citation reference BEFORE reindex
        String beforeContent = jdbcTemplate.queryForObject("SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        String beforeHeadingPath = jdbcTemplate.queryForObject("SELECT heading_path FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        String beforeMetaText = jdbcTemplate.queryForObject("SELECT metadata::text FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        JsonNode beforeMeta = objectMapper.readTree(beforeMetaText);

        String beforeChunkHash = beforeMeta.path("chunkHash").asText();
        String beforeHeading = beforeMeta.path("heading").asText();
        int beforeSectionIndex = beforeMeta.path("sectionIndex").asInt();
        String beforeSourceId = beforeMeta.path("sourceId").asText();
        String beforeSourcePath = beforeMeta.path("sourcePath").asText();
        String beforeDocumentCode = beforeMeta.path("documentCode").asText();
        String beforeVersionLabel = beforeMeta.path("versionLabel").asText();
        String beforeContentHash = beforeMeta.path("contentHash").asText();
        String beforeLineageDocId = beforeMeta.path("lineageBaseDocumentId").asText();
        String beforeLineageVer = beforeMeta.path("lineageBaseVersion").asText();
        String beforeLineageHash = beforeMeta.path("lineageBaseHash").asText();

        List<AiRunKnowledgeChunk> beforeRunChunks = provenanceRepository.findKnowledgeChunkReferencesByRunId(aiRunId);
        assertThat(beforeRunChunks).isNotEmpty();
        UUID beforeCitedChunkId = beforeRunChunks.get(0).knowledgeChunkId();
        int beforeRank = beforeRunChunks.get(0).rank();
        BigDecimal beforeSimilarity = beforeRunChunks.get(0).similarityScore();

        // -------------------------------------------------------------
        // SUB-TEST A: REINDEX WITH UNCHANGED DATA PRESERVES ALL FIELDS EXACTLY
        // -------------------------------------------------------------
        UUID run2Id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, run2Id, versionId, "RUN2:" + versionId);
        assertThat(outboxConsumer.processNextRun("test-worker-2")).isTrue();

        String run2Status = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, run2Id);
        assertThat(run2Status).isEqualTo("SUCCEEDED");

        // Snapshot AFTER unchanged reindex
        String afterContent = jdbcTemplate.queryForObject("SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        String afterHeadingPath = jdbcTemplate.queryForObject("SELECT heading_path FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        JsonNode afterMeta = objectMapper.readTree(
                jdbcTemplate.queryForObject("SELECT metadata::text FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId));

        assertThat(afterContent).isEqualTo(beforeContent);
        assertThat(afterHeadingPath).isEqualTo(beforeHeadingPath);
        assertThat(afterMeta.path("chunkHash").asText()).isEqualTo(beforeChunkHash);
        assertThat(afterMeta.path("heading").asText()).isEqualTo(beforeHeading);
        assertThat(afterMeta.path("sectionIndex").asInt()).isEqualTo(beforeSectionIndex);
        assertThat(afterMeta.path("sourceId").asText()).isEqualTo(beforeSourceId);
        assertThat(afterMeta.path("sourcePath").asText()).isEqualTo(beforeSourcePath);
        assertThat(afterMeta.path("documentCode").asText()).isEqualTo(beforeDocumentCode);
        assertThat(afterMeta.path("versionLabel").asText()).isEqualTo(beforeVersionLabel);
        assertThat(afterMeta.path("contentHash").asText()).isEqualTo(beforeContentHash);
        assertThat(afterMeta.path("lineageBaseDocumentId").asText()).isEqualTo(beforeLineageDocId);
        assertThat(afterMeta.path("lineageBaseVersion").asText()).isEqualTo(beforeLineageVer);
        assertThat(afterMeta.path("lineageBaseHash").asText()).isEqualTo(beforeLineageHash);

        // Assert ai_run_knowledge_chunks citation remains exactly identical
        List<AiRunKnowledgeChunk> afterRunChunks = provenanceRepository.findKnowledgeChunkReferencesByRunId(aiRunId);
        assertThat(afterRunChunks).hasSize(1);
        assertThat(afterRunChunks.get(0).knowledgeChunkId()).isEqualTo(beforeCitedChunkId);
        assertThat(afterRunChunks.get(0).rank()).isEqualTo(beforeRank);
        assertThat(afterRunChunks.get(0).similarityScore()).isEqualByComparingTo(beforeSimilarity);

        // Assert reading run via application GET endpoint retains SUCCEEDED status
        mockMvc.perform(get("/api/v1/ai/runs/" + aiRunId)
                        .header("Authorization", studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.runId", is(aiRunId.toString())));

        // -------------------------------------------------------------
        // SUB-TEST B: REINDEX MUTATING PROVENANCE/LOCATOR FAILS CLOSED
        // -------------------------------------------------------------
        String mutatedSourceMeta = beforeMetaText.replace("\"WHO-PA-SB-2020\"", "\"MUTATED-SOURCE\"");
        KnowledgeChunk chunkWithMutatedSource = new KnowledgeChunk(
                chunkId, versionId, 0, beforeHeadingPath, beforeContent, 10, mutatedSourceMeta, Instant.now());
        assertThatThrownBy(() -> chunkRepository.saveBatch(List.of(chunkWithMutatedSource)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.ContentConflictException.class)
                .hasMessageContaining("provenance field 'sourceId'");

        KnowledgeChunk chunkWithMutatedHeading = new KnowledgeChunk(
                chunkId, versionId, 0, "Altered Heading", beforeContent, 10, beforeMetaText, Instant.now());
        assertThatThrownBy(() -> chunkRepository.saveBatch(List.of(chunkWithMutatedHeading)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.ContentConflictException.class)
                .hasMessageContaining("heading_path locator");

        // Verify DB state and citations remain 100% intact after rejected mutations
        String restoredHeading = jdbcTemplate.queryForObject("SELECT heading_path FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        assertThat(restoredHeading).isEqualTo(beforeHeadingPath);
        List<AiRunKnowledgeChunk> preservedChunks = provenanceRepository.findKnowledgeChunkReferencesByRunId(aiRunId);
        assertThat(preservedChunks.get(0).knowledgeChunkId()).isEqualTo(beforeCitedChunkId);

        // -------------------------------------------------------------
        // SUB-TEST C: NON-PROVENANCE OPERATIONAL METADATA CAN STILL BE UPDATED
        // -------------------------------------------------------------
        String operationalMeta = beforeMetaText.replace("}", ", \"last_scanned_by\": \"worker-audit-42\"}");
        KnowledgeChunk chunkWithOperationalMeta = new KnowledgeChunk(
                chunkId, versionId, 0, beforeHeadingPath, beforeContent, 10, operationalMeta, Instant.now());
        chunkRepository.saveBatch(List.of(chunkWithOperationalMeta));

        String finalMeta = jdbcTemplate.queryForObject("SELECT metadata::text FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);
        assertThat(finalMeta).contains("worker-audit-42");
        assertThat(finalMeta).contains(beforeChunkHash);
        assertThat(finalMeta).contains(beforeSourceId);
        // Citation link is still intact
        List<AiRunKnowledgeChunk> finalChunks = provenanceRepository.findKnowledgeChunkReferencesByRunId(aiRunId);
        assertThat(finalChunks.get(0).knowledgeChunkId()).isEqualTo(beforeCitedChunkId);
    }

    @Test
    @DisplayName("Reduced chunk count prunes uncited chunks and preserves cited chunk without embeddings, making it ineligible for retrieval")
    void reindexWithReducedChunkCountPrunesUncitedChunksAndPreservesCitedChunkWithoutEmbeddings() {
        UUID versionId = createTestDocumentAndVersion("TEST-REDUCE-01", "WHO-PA-SB-2020",
                "# S1\n\nContent for chunk 0.\n\n# S2\n\nContent for chunk 1.\n\n# S3\n\nContent for chunk 2.");

        UUID run1Id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, run1Id, versionId, "RUN1:" + versionId);
        assertThat(outboxConsumer.processNextRun("test-worker-1")).isTrue();

        UUID chunk2Id = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 2", UUID.class, versionId);

        // Chunk 2 is cited by an AI Run
        UUID aiRunId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO fitness.ai_runs(id, request_type, status, created_at) VALUES (?, 'KNOWLEDGE_QUERY', 'SUCCEEDED', clock_timestamp())", aiRunId);
        jdbcTemplate.update("INSERT INTO fitness.ai_run_knowledge_chunks(ai_run_id, knowledge_chunk_id, rank, similarity_score) VALUES (?, ?, 1, 0.90)", aiRunId, chunk2Id);

        // Version content is reduced to only 1 section (chunk 0), so chunk 1 and chunk 2 are beyond active count (1)
        jdbcTemplate.execute("ALTER TABLE fitness.knowledge_versions DISABLE TRIGGER protect_knowledge_version_content");
        try {
            jdbcTemplate.update("UPDATE fitness.knowledge_versions SET content = ? WHERE id = ?",
                    "# S1\n\nContent for chunk 0.", versionId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE fitness.knowledge_versions ENABLE TRIGGER protect_knowledge_version_content");
        }

        UUID run2Id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, run2Id, versionId, "RUN2:" + versionId);
        assertThat(outboxConsumer.processNextRun("test-worker-2")).isTrue();

        String run2Status = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, run2Id);
        assertThat(run2Status).isEqualTo("SUCCEEDED");

        // Chunk 1 (uncited) was pruned from database
        Integer chunk1Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 1",
                Integer.class, versionId);
        assertThat(chunk1Count).isEqualTo(0);

        // Chunk 2 (cited) was preserved in database because of citation FK
        Integer chunk2Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE id = ?", Integer.class, chunk2Id);
        assertThat(chunk2Count).isEqualTo(1);

        // BUT Chunk 2 has NO embedding in knowledge_embeddings!
        Integer chunk2EmbeddingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_embeddings WHERE knowledge_chunk_id = ?",
                Integer.class, chunk2Id);
        assertThat(chunk2EmbeddingCount).isEqualTo(0);

        // And isChunkEligible returns false for chunk 2!
        boolean chunk2Eligible = retrievalUseCase.isChunkEligible("TEST-REDUCE-01", "1.0.0", 2);
        assertThat(chunk2Eligible).isFalse();

        // Active chunk 0 has embedding and is eligible
        boolean chunk0Eligible = retrievalUseCase.isChunkEligible("TEST-REDUCE-01", "1.0.0", 0);
        assertThat(chunk0Eligible).isTrue();
    }

    @Test
    @DisplayName("Empty content chunking safely deletes unreferenced chunks, removes embeddings, and succeeds")
    void reindexWithEmptyContentProducesEmptyChunksAndCleansEmbeddings() {
        UUID versionId = createTestDocumentAndVersion("TEST-EMPTY-01", "WHO-PA-SB-2020",
                "# Section 1\n\nContent to be cleared.");

        UUID run1Id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, run1Id, versionId, "RUN1:" + versionId);
        assertThat(outboxConsumer.processNextRun("test-worker-1")).isTrue();

        // Update version to blank/empty content
        jdbcTemplate.execute("ALTER TABLE fitness.knowledge_versions DISABLE TRIGGER protect_knowledge_version_content");
        try {
            jdbcTemplate.update("UPDATE fitness.knowledge_versions SET content = '' WHERE id = ?", versionId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE fitness.knowledge_versions ENABLE TRIGGER protect_knowledge_version_content");
        }

        UUID run2Id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, run2Id, versionId, "RUN2:" + versionId);
        assertThat(outboxConsumer.processNextRun("test-worker-2")).isTrue();

        String run2Status = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, run2Id);
        assertThat(run2Status).isEqualTo("SUCCEEDED");

        // All unreferenced chunks deleted
        int chunksRemaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?", Integer.class, versionId);
        assertThat(chunksRemaining).isEqualTo(0);

        // All embeddings deleted
        int embeddingsRemaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fitness.knowledge_embeddings e JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id WHERE c.knowledge_version_id = ?",
                Integer.class, versionId);
        assertThat(embeddingsRemaining).isEqualTo(0);
    }

    @Test
    @DisplayName("Persistence failure or stale lease rolls back transaction cleanly without partial writes")
    void reindexRollsBackOnPersistenceErrorOrLostLease() {
        UUID versionId = createTestDocumentAndVersion("TEST-LEASE-01", "WHO-PA-SB-2020",
                "# Section 1\n\nLease test content.");

        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, runId, versionId, "RUN-LEASE:" + versionId);

        UUID overtakingToken = UUID.randomUUID();
        // Register hook to simulate overtaking worker / expired lease during non-transactional phase
        outboxConsumer.setExecutionInspectionHook(run -> {
            if (run.id().equals(runId)) {
                // Change claim_token in DB so Boundary 3 fencing check fails
                jdbcTemplate.update("UPDATE fitness.knowledge_processing_runs SET claim_token = ? WHERE id = ?", overtakingToken, runId);
            }
        });

        try {
            boolean processed = outboxConsumer.processNextRun("test-worker-original");
            assertThat(processed).isTrue();

            // Run in DB was not updated to SUCCEEDED with original token
            UUID tokenInDb = jdbcTemplate.queryForObject(
                    "SELECT claim_token FROM fitness.knowledge_processing_runs WHERE id = ?", UUID.class, runId);
            assertThat(tokenInDb).isEqualTo(overtakingToken);

            // Chunks and embeddings must NOT have been saved due to transactional rollback on fencing failure
            int chunkCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?", Integer.class, versionId);
            assertThat(chunkCount).isEqualTo(0);

            int embeddingCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fitness.knowledge_embeddings e JOIN fitness.knowledge_chunks c ON c.id = e.knowledge_chunk_id WHERE c.knowledge_version_id = ?",
                    Integer.class, versionId);
            assertThat(embeddingCount).isEqualTo(0);
        } finally {
            outboxConsumer.setExecutionInspectionHook(null);
        }
    }

    @Test
    @DisplayName("saveBatch fails closed when metadata or chunkHash is missing/null on cited chunk")
    void saveBatchFailsClosedWhenMetadataOrChunkHashIsMissingOnCitedChunk() {
        UUID versionId = createTestDocumentAndVersion("TEST-NULL-META-01", "WHO-PA-SB-2020",
                "# Section 1\n\nValid cited chunk content.");

        UUID run1Id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, run1Id, versionId, "RUN1:" + versionId);
        assertThat(outboxConsumer.processNextRun("test-worker-1")).isTrue();

        UUID chunkId = jdbcTemplate.queryForObject(
                "SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? AND chunk_index = 0", UUID.class, versionId);
        String originalContent = jdbcTemplate.queryForObject(
                "SELECT content FROM fitness.knowledge_chunks WHERE id = ?", String.class, chunkId);

        // Cite chunk 0
        UUID aiRunId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO fitness.ai_runs(id, request_type, status, created_at) VALUES (?, 'KNOWLEDGE_QUERY', 'SUCCEEDED', clock_timestamp())", aiRunId);
        jdbcTemplate.update("INSERT INTO fitness.ai_run_knowledge_chunks(ai_run_id, knowledge_chunk_id, rank, similarity_score) VALUES (?, ?, 1, 0.95)", aiRunId, chunkId);

        // Case A: null metadataJson
        KnowledgeChunk chunkWithNullMeta = new KnowledgeChunk(
                chunkId, versionId, 0, "Section 1", originalContent, 10, null, Instant.now());
        assertThatThrownBy(() -> chunkRepository.saveBatch(List.of(chunkWithNullMeta)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.ContentConflictException.class)
                .hasMessageContaining("Missing or unverified chunkHash");

        // Case B: empty metadataJson "{}"
        KnowledgeChunk chunkWithEmptyMeta = new KnowledgeChunk(
                chunkId, versionId, 0, "Section 1", originalContent, 10, "{}", Instant.now());
        assertThatThrownBy(() -> chunkRepository.saveBatch(List.of(chunkWithEmptyMeta)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.ContentConflictException.class)
                .hasMessageContaining("Missing or unverified chunkHash");

        // Case C: null chunkHash inside metadataJson "{\"chunkHash\": null}"
        KnowledgeChunk chunkWithNullHashField = new KnowledgeChunk(
                chunkId, versionId, 0, "Section 1", originalContent, 10, "{\"chunkHash\": null}", Instant.now());
        assertThatThrownBy(() -> chunkRepository.saveBatch(List.of(chunkWithNullHashField)))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.ContentConflictException.class)
                .hasMessageContaining("Missing or unverified chunkHash");
    }

    @Test
    @DisplayName("Query embedding executes outside transaction observing port state with real PlatformTransactionManager")
    void queryEmbeddingExecutesOutsideTransactionWithRealTransactionManager() {
        UUID versionId = createTestDocumentAndVersion("TEST-TX-REAL-01", "WHO-PA-SB-2020",
                "# Active Section\n\nActive body content for real tx test.");

        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, runId, versionId, "RUN-TX:" + versionId);
        assertThat(outboxConsumer.processNextRun("test-worker-1")).isTrue();

        AtomicBoolean txActiveDuringEmbedding = new AtomicBoolean(true);
        AtomicInteger embeddingInvocationCount = new AtomicInteger(0);

        EmbeddingModelPort observingPort = new EmbeddingModelPort() {
            @Override
            public float[] generateEmbedding(String text) {
                embeddingInvocationCount.incrementAndGet();
                // Direct observation AT the embedding port: check transaction state
                txActiveDuringEmbedding.set(
                        org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                );
                return embeddingGenerator.generateEmbedding(text);
            }

            @Override
            public List<float[]> generateBatch(List<String> texts) {
                return texts.stream().map(this::generateEmbedding).toList();
            }

            @Override public int dimension() { return embeddingGenerator.dimension(); }
            @Override public String provider() { return embeddingGenerator.provider(); }
            @Override public String modelCode() { return embeddingGenerator.modelCode(); }
            @Override public String versionLabel() { return embeddingGenerator.versionLabel(); }
            @Override public String modelSpace() { return embeddingGenerator.modelSpace(); }
            @Override public UUID modelVersionId() { return embeddingGenerator.modelVersionId(); }
        };

        KnowledgeRetrievalService observingService = new KnowledgeRetrievalService(
                jdbcTemplate, observingPort, transactionManager
        );

        // -------------------------------------------------------------
        // SCENARIO 1: CALLER HAS AN ACTIVE TRANSACTION
        // Proves: PROPAGATION_NOT_SUPPORTED suspends caller transaction at the port,
        // and caller transaction is restored afterwards.
        // -------------------------------------------------------------
        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);

        txTemplate.execute(status -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    .as("Caller transaction is active prior to retrieval")
                    .isTrue();

            List<RetrievedKnowledgeChunk> chunks = observingService.retrieveActiveKnowledge(
                    "Active body content", List.of("WHO-PA-SB-2020"), 5);

            assertThat(chunks).isNotEmpty();
            assertThat(chunks.get(0).documentCode()).isEqualTo("TEST-TX-REAL-01");

            // Key observation: during generateEmbedding execution, transaction synchronization was NOT active!
            assertThat(txActiveDuringEmbedding.get())
                    .as("Direct port observation: transaction must NOT be active during generateEmbedding")
                    .isFalse();

            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    .as("Caller transaction is resumed and active after retrieval call")
                    .isTrue();

            return null;
        });

        assertThat(embeddingInvocationCount.get()).isEqualTo(1);

        // -------------------------------------------------------------
        // SCENARIO 2: PRODUCTION CALLER PATH WITHOUT TRANSACTION
        // Proves: Production callers (e.g. KnowledgeQueryService) do not open
        // a database transaction before remote I/O.
        // -------------------------------------------------------------
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                .as("Production caller thread has no active transaction before retrieval")
                .isFalse();

        List<RetrievedKnowledgeChunk> prodChunks = observingService.retrieveActiveKnowledge(
                "Active body content", List.of("WHO-PA-SB-2020"), 5);

        assertThat(prodChunks).isNotEmpty();
        assertThat(txActiveDuringEmbedding.get())
                .as("Embedding generation executes outside transaction in production caller path")
                .isFalse();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                .as("Production caller thread remains without transaction after retrieval")
                .isFalse();
    }

    @Test
    @DisplayName("ODS end-to-end normalization maps legacy alias to canonical, validates within scope, and filters unapproved sources")
    void odsEndToEndNormalizationAndScopeValidationTest() {
        // Step 1: Verify domain value type normalization
        assertThat(KnowledgeSourceIdentity.canonicalize("ODS-EXERCISE-SUPPLEMENTS-2024"))
                .isEqualTo("NIH-ODS-EXERCISE-SUPPLEMENTS");
        assertThat(KnowledgeSourceIdentity.canonicalize("NIH-ODS-EXERCISE-SUPPLEMENTS"))
                .isEqualTo("NIH-ODS-EXERCISE-SUPPLEMENTS");
        assertThat(KnowledgeSourceIdentity.canonicalize("UNKNOWN-SOURCE"))
                .isEqualTo("UNKNOWN-SOURCE");

        // Step 2: Seed active ODS document with canonical sourceId
        UUID versionId = createTestDocumentAndVersion("ODS-TEST-DOC-01", "NIH-ODS-EXERCISE-SUPPLEMENTS",
                "# Creatine Monohydrate\n\nCreatine monohydrate is effective for strength training.");

        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, '{}'::jsonb, clock_timestamp())
                """, runId, versionId, "RUN-ODS:" + versionId);
        assertThat(outboxConsumer.processNextRun("test-worker-1")).isTrue();

        // Step 3: Test KnowledgeQueryUseCase with legacy alias -> normalizes and queries active version
        KnowledgeQueryResult legacyAliasResult = knowledgeQueryUseCase.queryActiveKnowledge(
                new KnowledgeQueryRequest("creatine", List.of("ODS-EXERCISE-SUPPLEMENTS-2024"), 5)
        );
        assertThat(legacyAliasResult.citations()).isNotEmpty();
        assertThat(legacyAliasResult.citations().get(0).sourceId())
                .isEqualTo("NIH-ODS-EXERCISE-SUPPLEMENTS");

        // Step 4: Empty scope and unapproved source return empty list
        KnowledgeQueryResult emptyScopeResult = knowledgeQueryUseCase.queryActiveKnowledge(
                new KnowledgeQueryRequest("creatine", List.of(), 5)
        );
        assertThat(emptyScopeResult.citations()).isEmpty();

        KnowledgeQueryResult unapprovedScopeResult = knowledgeQueryUseCase.queryActiveKnowledge(
                new KnowledgeQueryRequest("creatine", List.of("MALICIOUS-SOURCE"), 5)
        );
        assertThat(unapprovedScopeResult.citations()).isEmpty();

        // Step 5: Test F01RuntimeOutputValidator with envelope containing canonical source
        F01InternalContextEnvelope envelope = new F01InternalContextEnvelope(
                UUID.randomUUID(),
                F01InternalContextEnvelope.F01_REQUEST_TYPE,
                F01InternalContextEnvelope.SCHEMA_VERSION,
                UUID.randomUUID(),
                new F01InternalContextEnvelope.ActorContext(STUDENT_ID, "STUDENT"),
                F01InternalContextEnvelope.GENERAL_PURPOSE,
                F01InternalContextEnvelope.ALLOWED_ACTIONS,
                "2026-10-18T00:00:00Z",
                "creatine safety",
                "vi",
                null,
                new F01InternalContextEnvelope.KnowledgeScopeContext(
                        "ACTIVE",
                        List.of("NIH-ODS-EXERCISE-SUPPLEMENTS")
                )
        );

        // Case 5A: Citation using legacy alias ODS-EXERCISE-SUPPLEMENTS-2024 is accepted by validator
        F01KnowledgeQueryResponse.CitationDto legacyCitation = new F01KnowledgeQueryResponse.CitationDto(
                "ODS-TEST-DOC-01-VI-01",
                "1.0.0",
                "ODS-EXERCISE-SUPPLEMENTS-2024",
                new F01KnowledgeQueryResponse.LocatorDto("data/ods/01.md", "Creatine", null, 0),
                "Creatine monohydrate is effective"
        );
        F01KnowledgeQueryResponse validRespWithLegacyAlias = new F01KnowledgeQueryResponse(
                envelope.requestId(),
                envelope.correlationId(),
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Creatine is effective.",
                List.of(legacyCitation),
                null,
                null,
                "2026-10-18T00:00:01Z",
                null,
                null,
                null
        );
        F01RuntimeOutputValidator.ValidationResult resultLegacy = runtimeValidator.validate(validRespWithLegacyAlias, envelope);
        assertThat(resultLegacy.isValid()).isTrue();

        // Case 5B: Citation using unapproved source is rejected
        F01KnowledgeQueryResponse.CitationDto unapprovedCitation = new F01KnowledgeQueryResponse.CitationDto(
                "ODS-TEST-DOC-01-VI-01",
                "1.0.0",
                "MALICIOUS-SOURCE",
                new F01KnowledgeQueryResponse.LocatorDto("data/ods/01.md", "Creatine", null, 0),
                "Creatine monohydrate is effective"
        );
        F01KnowledgeQueryResponse respWithUnapprovedCitation = new F01KnowledgeQueryResponse(
                envelope.requestId(),
                envelope.correlationId(),
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Creatine is effective.",
                List.of(unapprovedCitation),
                null,
                null,
                "2026-10-18T00:00:01Z",
                null,
                null,
                null
        );
        F01RuntimeOutputValidator.ValidationResult resultUnapproved = runtimeValidator.validate(respWithUnapprovedCitation, envelope);
        assertThat(resultUnapproved.isValid()).isFalse();
        assertThat(resultUnapproved.failureReason()).contains("is outside envelope allowedSourceIds scope");

        // Case 5C: Envelope with empty allowedSourceIds rejects any citation
        F01InternalContextEnvelope emptyEnvelope = new F01InternalContextEnvelope(
                UUID.randomUUID(),
                F01InternalContextEnvelope.F01_REQUEST_TYPE,
                F01InternalContextEnvelope.SCHEMA_VERSION,
                UUID.randomUUID(),
                new F01InternalContextEnvelope.ActorContext(STUDENT_ID, "STUDENT"),
                F01InternalContextEnvelope.GENERAL_PURPOSE,
                F01InternalContextEnvelope.ALLOWED_ACTIONS,
                "2026-10-18T00:00:00Z",
                "creatine safety",
                "vi",
                null,
                new F01InternalContextEnvelope.KnowledgeScopeContext("ACTIVE", List.of())
        );
        F01RuntimeOutputValidator.ValidationResult resultEmptyScope = runtimeValidator.validate(validRespWithLegacyAlias, emptyEnvelope);
        assertThat(resultEmptyScope.isValid()).isFalse();
        assertThat(resultEmptyScope.failureReason()).contains("is outside envelope allowedSourceIds scope");
    }
}
