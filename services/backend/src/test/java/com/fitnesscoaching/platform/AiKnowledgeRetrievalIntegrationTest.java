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
import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
        assertThat(outboxStats.path("real_embeddings").asBoolean()).isTrue();
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
}
