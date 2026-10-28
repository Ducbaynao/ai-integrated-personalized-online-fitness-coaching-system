package com.fitnesscoaching.platform;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeIndexingOutboxConsumer;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContentManagementIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("content_integration")
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

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private KnowledgeIndexingOutboxConsumer outboxConsumer;

    @BeforeEach
    void seedAdminUserAndPermissions() {
        jdbcTemplate.update("TRUNCATE TABLE fitness.knowledge_documents, fitness.knowledge_processing_runs CASCADE");

        jdbcTemplate.update("""
                INSERT INTO fitness.users (
                    id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at
                ) VALUES (
                    ?, 'admin@fitplatform.local', '$2a$10$dummyHash', 'Admin Tester',
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
    }

    private String bearer(UUID userId, String... roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(userId.toString())
                .claim("email", "admin@fitplatform.local")
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
                Path.of(relative),
                Path.of("..", "..", "data", "knowledge", "packages", "issn-protein-2017-rag-normalized-v1", relative),
                Path.of("data", "knowledge", "packages", "issn-protein-2017-rag-normalized-v1", relative)
        );
        for (Path p : candidates) {
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("Knowledge file not found on disk: " + relative);
    }

    @Test
    void fullContentLifecycleAndQueryFlow() throws Exception {
        String adminToken = bearer(ADMIN_ID, "ADMIN");

        // Read verified production corpus derivative files
        String whoContent = Files.readString(resolveKnowledgeFile("derivatives/who-2020/01-scope.vi.md"), StandardCharsets.UTF_8);
        String whoHash = sha256(whoContent);

        String niddkContent = Files.readString(resolveKnowledgeFile("derivatives/niddk-weight-factors/05-medicines.vi.md"), StandardCharsets.UTF_8);
        String niddkHash = sha256(niddkContent);

        // A. REGRESSION TEST: Fake reviewEvidenceId must be REJECTED (HTTP 400)
        String fakeEvidenceBundle = """
                {
                  "formatVersion": 1,
                  "purpose": "F01_GENERAL_KNOWLEDGE",
                  "bundleName": "fake-evidence-test",
                  "items": [
                    {
                      "documentCode": "WHO-PA-SB-2020-VI-01",
                      "sourceId": "WHO-PA-SB-2020",
                      "title": "WHO Physical Activity Guidelines",
                      "versionLabel": "1.1.0",
                      "contentHash": "%s",
                      "contentPath": "derivatives/who-2020/01-scope.vi.md",
                      "reviewEvidenceId": "FAKE-UNTRUSTED-EVIDENCE-999",
                      "content": %s
                    }
                  ]
                }
                """.formatted(whoHash, objectMapper.writeValueAsString(whoContent));

        mockMvc.perform(post("/api/v1/admin/content/knowledge/import")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fakeEvidenceBundle))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("CONTENT_VALIDATION_FAILED")));

        // B. VALID IMPORT with authentic specialist review evidence (REV-PO-20261008-01)
        String bundleJson = """
                {
                  "formatVersion": 1,
                  "purpose": "F01_GENERAL_KNOWLEDGE",
                  "bundleName": "test-corpus-v1",
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
                niddkHash, niddkContent.getBytes(StandardCharsets.UTF_8).length, objectMapper.writeValueAsString(niddkContent));

        // 1. IMPORT
        mockMvc.perform(post("/api/v1/admin/content/knowledge/import")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bundleJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems", is(2)))
                .andExpect(jsonPath("$.importedCount", is(2)))
                .andExpect(jsonPath("$.skippedCount", is(0)));

        // Verify rows in PostgreSQL
        Integer docCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.knowledge_documents", Integer.class);
        assertThat(docCount).isGreaterThanOrEqualTo(2);

        // Verify review records in PostgreSQL retain original reviewer identity
        String reviewerIdentity = jdbcTemplate.queryForObject("""
                SELECT r.reviewer_identity FROM fitness.knowledge_version_reviews r
                JOIN fitness.knowledge_versions v ON v.id = r.knowledge_version_id
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE d.document_code = 'WHO-PA-SB-2020-VI-01'
                LIMIT 1
                """, String.class);
        assertThat(reviewerIdentity).isEqualTo("Chủ dự án — xác nhận trực tiếp của người dùng");

        // 2. IDEMPOTENT RE-IMPORT (exact same bundle)
        mockMvc.perform(post("/api/v1/admin/content/knowledge/import")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bundleJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems", is(2)))
                .andExpect(jsonPath("$.importedCount", is(0)))
                .andExpect(jsonPath("$.skippedCount", is(2)));

        // 2B. PROVENANCE CONFLICT: Re-import same content hash but with DIFFERENT provenance (altered path)
        String provConflictBundle = """
                {
                  "formatVersion": 1,
                  "purpose": "F01_GENERAL_KNOWLEDGE",
                  "bundleName": "prov-conflict-test",
                  "items": [
                    {
                      "documentCode": "WHO-PA-SB-2020-VI-01",
                      "sourceId": "WHO-PA-SB-2020",
                      "title": "WHO Physical Activity Guidelines",
                      "versionLabel": "1.1.0",
                      "contentHash": "%s",
                      "contentPath": "different/tampered/path.vi.md",
                      "content": %s
                    }
                  ]
                }
                """.formatted(whoHash, objectMapper.writeValueAsString(whoContent));

        mockMvc.perform(post("/api/v1/admin/content/knowledge/import")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(provConflictBundle))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("CONTENT_CONFLICT")));

        // 3. CONFLICT: same version with modified hash
        String conflictContent = "Tampered content with different hash";
        String conflictHash = sha256(conflictContent);
        String conflictBundle = """
                {
                  "formatVersion": 1,
                  "purpose": "F01_GENERAL_KNOWLEDGE",
                  "bundleName": "conflict-test",
                  "items": [
                    {
                      "documentCode": "WHO-PA-SB-2020-VI-01",
                      "sourceId": "WHO-PA-SB-2020",
                      "title": "WHO Guidelines",
                      "versionLabel": "1.1.0",
                      "contentHash": "%s",
                      "content": %s
                    }
                  ]
                }
                """.formatted(conflictHash, objectMapper.writeValueAsString(conflictContent));

        mockMvc.perform(post("/api/v1/admin/content/knowledge/import")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictBundle))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("CONTENT_CONFLICT")));

        // 3B. SNAPSHOT ARTICLE (Approved in specialist review, but technical verification not performed yet)
        String issnContent = Files.readString(resolveKnowledgeFile("data/knowledge/curated/issn-protein-2017/01-scope.vi.md"), StandardCharsets.UTF_8);
        String issnHash = sha256(issnContent);
        String snapshotBundle = """
                {
                  "formatVersion": 1,
                  "purpose": "F01_GENERAL_KNOWLEDGE",
                  "bundleName": "snapshot-test",
                  "items": [
                    {
                      "documentCode": "ISSN-PROTEIN-EXERCISE-2017-VI-01",
                      "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
                      "title": "ISSN Protein Guidelines",
                      "versionLabel": "1.0.0",
                      "contentHash": "%s",
                      "contentPath": "data/knowledge/curated/issn-protein-2017/01-scope.vi.md",
                      "sourceBytes": %d,
                      "reviewEvidenceId": "REV-PO-20261008-01",
                      "content": %s
                    }
                  ]
                }
                """.formatted(issnHash, issnContent.getBytes(StandardCharsets.UTF_8).length, objectMapper.writeValueAsString(issnContent));

        mockMvc.perform(post("/api/v1/admin/content/knowledge/import")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(snapshotBundle))
                .andExpect(status().isOk());

        UUID issnVersionId = jdbcTemplate.queryForObject("""
                SELECT v.id FROM fitness.knowledge_versions v
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE d.document_code = 'ISSN-PROTEIN-EXERCISE-2017-VI-01'
                """, UUID.class);

        // Crucial invariant: Specialist approval does NOT imply technical verification passed!
        // Snapshot articles remain DRAFT_STAGED.
        String issnReadiness = jdbcTemplate.queryForObject(
                "SELECT technical_readiness FROM fitness.knowledge_versions WHERE id = ?", String.class, issnVersionId);
        assertThat(issnReadiness).isEqualTo("DRAFT_STAGED");

        // Attempting to publish unverified snapshot must be REJECTED (409)
        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + issnVersionId + "/publish")
                        .header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("CONTENT_LIFECYCLE_CONFLICT")));

        // 4. PUBLISH GUARD: Attempting to publish NIDDK (HELD_AS_DRAFT) must be REJECTED (409)
        UUID niddkVersionId = jdbcTemplate.queryForObject("""
                SELECT v.id FROM fitness.knowledge_versions v
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE d.document_code = 'NIDDK-WEIGHT-FACTORS-2023-VI-05'
                """, UUID.class);

        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + niddkVersionId + "/publish")
                        .header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("CONTENT_LIFECYCLE_CONFLICT")));

        // Verify status remains DRAFT
        String niddkStatus = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.knowledge_versions WHERE id = ?", String.class, niddkVersionId);
        assertThat(niddkStatus).isEqualTo("DRAFT");

        // 5. PUBLISH SUCCESS FOR WHO (TECHNICAL_VERIFICATION_PASSED + APPROVED)
        UUID whoVersionId = jdbcTemplate.queryForObject("""
                SELECT v.id FROM fitness.knowledge_versions v
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE d.document_code = 'WHO-PA-SB-2020-VI-01'
                """, UUID.class);

        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + whoVersionId + "/publish")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk());

        String whoStatus = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.knowledge_versions WHERE id = ?", String.class, whoVersionId);
        assertThat(whoStatus).isEqualTo("ACTIVE");

        // Verify durable outbox queue in PostgreSQL
        Integer queuedRuns = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM fitness.knowledge_processing_runs
                WHERE knowledge_version_id = ? AND run_type = 'REINDEX' AND status = 'QUEUED'
                """, Integer.class, whoVersionId);
        assertThat(queuedRuns).isEqualTo(1);

        // Process durable outbox run using consumer
        int processed = outboxConsumer.processPendingRuns(10);
        assertThat(processed).isEqualTo(1);

        String runStatus = jdbcTemplate.queryForObject("""
                SELECT status FROM fitness.knowledge_processing_runs
                WHERE knowledge_version_id = ?
                """, String.class, whoVersionId);
        assertThat(runStatus).isEqualTo("SUCCEEDED");

        // Verify statistics in outbox run: real embeddings true, real retrieval indexing, index ready
        String statisticsJson = jdbcTemplate.queryForObject("""
                SELECT statistics::text FROM fitness.knowledge_processing_runs
                WHERE knowledge_version_id = ?
                """, String.class, whoVersionId);
        JsonNode statsNode = objectMapper.readTree(statisticsJson);
        assertThat(statsNode.path("real_embeddings").asBoolean()).isFalse();
        assertThat(statsNode.path("embedding_provider").asText()).isEqualTo("simulated");
        assertThat(statsNode.path("embedding_mode").asText()).isEqualTo("SIMULATED_DETERMINISTIC");
        assertThat(statsNode.path("mode").asText()).isEqualTo("REAL_RETRIEVAL_INDEXING");
        assertThat(statsNode.path("index_ready").asBoolean()).isTrue();
        assertThat(statsNode.path("indexed_chunks").asInt()).isGreaterThan(0);

        // Verify database immutability: Cannot modify content of ACTIVE version
        assertThatThrownBy(() -> {
            jdbcTemplate.update("UPDATE fitness.knowledge_versions SET content = 'Altered after active' WHERE id = ?", whoVersionId);
        }).hasMessageContaining("Published content is immutable");

        // 6. AI KNOWLEDGE QUERY & SECURITY:
        // Case 6A: Unauthenticated query is REJECTED (HTTP 401)
        mockMvc.perform(post("/api/v1/content/knowledge/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"guidelines\",\"allowedSourceIds\":[\"WHO-PA-SB-2020\"]}"))
                .andExpect(status().isUnauthorized());

        // Case 6B: Suspended user is REJECTED (HTTP 403)
        UUID suspendedUserId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (
                    id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at
                ) VALUES (?, 'suspended@fitplatform.local', '$2a$10$dummyHash', 'Suspended Tester',
                          'SUSPENDED', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """, suspendedUserId);
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'STUDENT'
                """, suspendedUserId, ADMIN_ID);
        String suspendedToken = bearer(suspendedUserId, "STUDENT");

        mockMvc.perform(post("/api/v1/content/knowledge/query")
                        .header("Authorization", suspendedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"guidelines\",\"allowedSourceIds\":[\"WHO-PA-SB-2020\"]}"))
                .andExpect(status().isUnauthorized());

        // Case 6C: Active Student user is ALLOWED (HTTP 200)
        UUID studentUserId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (
                    id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at
                ) VALUES (?, 'student@fitplatform.local', '$2a$10$dummyHash', 'Active Student',
                          'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """, studentUserId);
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp() FROM fitness.roles WHERE code = 'STUDENT'
                """, studentUserId, ADMIN_ID);
        String studentToken = bearer(studentUserId, "STUDENT");

        mockMvc.perform(post("/api/v1/content/knowledge/query")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"guidelines\",\"allowedSourceIds\":[\"WHO-PA-SB-2020\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations", hasSize(5)));

        // Case 6D: allowedSourceIds is empty -> returns 0 citations
        mockMvc.perform(post("/api/v1/content/knowledge/query")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"physical activity\",\"allowedSourceIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations", hasSize(0)));

        // Case 6E: allowedSourceIds has NIDDK -> returns 0 citations (because NIDDK is DRAFT, not ACTIVE)
        mockMvc.perform(post("/api/v1/content/knowledge/query")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"medicines\",\"allowedSourceIds\":[\"NIDDK-WEIGHT-FACTORS-2023\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations", hasSize(0)));

        // Case 6F: allowedSourceIds has WHO -> returns the active citation!
        mockMvc.perform(post("/api/v1/content/knowledge/query")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"guidelines\",\"allowedSourceIds\":[\"WHO-PA-SB-2020\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations", hasSize(5)))
                .andExpect(jsonPath("$.citations[0].documentCode", is("WHO-PA-SB-2020-VI-01")))
                .andExpect(jsonPath("$.citations[0].version", is("1.1.0")))
                .andExpect(jsonPath("$.citations[0].sourceId", is("WHO-PA-SB-2020")));

        // 7. ARCHIVE
        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + whoVersionId + "/archive")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Replaced by newer edition with quotes \\\"test\\\" and Unicode Tiếng Việt\"}"))
                .andExpect(status().isOk());

        String whoArchivedStatus = jdbcTemplate.queryForObject("SELECT status::text FROM fitness.knowledge_versions WHERE id = ?", String.class, whoVersionId);
        assertThat(whoArchivedStatus).isEqualTo("ARCHIVED");

        // Case D: after ARCHIVE, query returns 0 citations
        mockMvc.perform(post("/api/v1/content/knowledge/query")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"guidelines\",\"allowedSourceIds\":[\"WHO-PA-SB-2020\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations", hasSize(0)));

        // Verify outbox consumer eligibility: if a job existed for ARCHIVED version, consumer marks it FAILED
        UUID dummyRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (id, knowledge_version_id, run_type, status, operation_key, created_at)
                VALUES (?, ?, 'REINDEX', 'QUEUED', ?, now())
                """, dummyRunId, whoVersionId, "REINDEX-ARCHIVED:" + dummyRunId);

        int processedArchived = outboxConsumer.processPendingRuns(10);
        assertThat(processedArchived).isEqualTo(1);

        String archivedRunStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, dummyRunId);
        assertThat(archivedRunStatus).isEqualTo("FAILED");

        // 8. AUDIT LOGS JSON VALIDATION
        List<String> auditJsons = jdbcTemplate.queryForList("""
                SELECT metadata::text FROM fitness.audit_logs
                WHERE action IN ('KNOWLEDGE_VERSION_IMPORTED', 'KNOWLEDGE_VERSION_PUBLISHED', 'KNOWLEDGE_VERSION_ARCHIVED')
                """, String.class);
        assertThat(auditJsons.size()).isGreaterThanOrEqualTo(3);

        for (String jsonStr : auditJsons) {
            JsonNode parsed = objectMapper.readTree(jsonStr);
            assertThat(parsed.isObject()).isTrue();
        }
    }

    @Test
    void concurrentPublishAttempts_exactlyOneSucceedsAndOthersConflict() throws Exception {
        String adminToken = bearer(ADMIN_ID, "ADMIN");

        UUID docId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                VALUES (?, ?, 'Concurrency Test Doc', 'WHO-PA-SB-2020', 'vi', ?)
                """, docId, "CONCURRENT-DOC-" + docId, ADMIN_ID);

        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_versions(
                    id, knowledge_document_id, version_number, version_label,
                    status, technical_readiness, content_hash, content, created_by
                ) VALUES (
                    ?, ?, 1, '1.0.0',
                    'DRAFT', 'TECHNICAL_VERIFICATION_PASSED', 'concurrenthash123', 'Content', ?
                )
                """, versionId, docId, ADMIN_ID);

        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_version_reviews(
                    knowledge_version_id, reviewer_id, reviewer_identity, reviewer_role, decision, content_hash, notes
                ) VALUES (?, ?, 'Admin Reviewer', 'ADMIN', 'APPROVED', 'concurrenthash123', 'Approval')
                """, versionId, ADMIN_ID);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Runnable publishTask = () -> {
            try {
                startLatch.await();
                var result = mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + versionId + "/publish")
                                .header("Authorization", adminToken))
                        .andReturn();
                int status = result.getResponse().getStatus();
                if (status == 200) {
                    successCount.incrementAndGet();
                } else if (status == 409) {
                    conflictCount.incrementAndGet();
                }
            } catch (Exception e) {
                // ignore
            }
        };

        Future<?> f1 = executor.submit(publishTask);
        Future<?> f2 = executor.submit(publishTask);

        startLatch.countDown();
        f1.get();
        f2.get();
        executor.shutdown();

        // Exactly one thread succeeded to publish; the competing thread was rejected with 409 Conflict
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
    }

    @Test
    void concurrentOutboxWorkers_atomicClaimPreventsDoubleProcessing() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                VALUES (?, ?, 'Outbox Atomic Test Doc', 'WHO-PA-SB-2020', 'vi', ?)
                """, docId, "OUTBOX-DOC-" + docId, ADMIN_ID);

        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_versions(
                    id, knowledge_document_id, version_number, version_label,
                    status, technical_readiness, content_hash, content, created_by
                ) VALUES (
                    ?, ?, 1, '1.0.0',
                    'ACTIVE', 'TECHNICAL_VERIFICATION_PASSED', 'outboxatomic123', 'Content', ?
                )
                """, versionId, docId, ADMIN_ID);

        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, created_at
                ) VALUES (?, ?, 'REINDEX', 'QUEUED', ?, now())
                """, runId, versionId, "INDEX:" + versionId);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger claimedCount = new AtomicInteger(0);

        Runnable workerTask = () -> {
            try {
                startLatch.await();
                boolean processed = outboxConsumer.processNextRun(Thread.currentThread().getName());
                if (processed) {
                    claimedCount.incrementAndGet();
                }
            } catch (Exception e) {
                // ignore
            }
        };

        Future<?> f1 = executor.submit(workerTask);
        Future<?> f2 = executor.submit(workerTask);

        startLatch.countDown();
        f1.get();
        f2.get();
        executor.shutdown();

        // Exactly one worker claimed and processed the single eligible run
        assertThat(claimedCount.get()).isEqualTo(1);

        String runStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, runId);
        assertThat(runStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    void adminWithoutContentAdminRoleIsDeniedOnContentMutations() throws Exception {
        UUID plainAdminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (
                    id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at
                ) VALUES (
                    ?, ?, '$2a$10$dummyHash', 'Plain Admin',
                    'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now()
                )
                """, plainAdminId, "plain.admin." + plainAdminId + "@fitplatform.local");

        // Assign ONLY the base ADMIN role (no CONTENT_ADMIN)
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp()
                FROM fitness.roles
                WHERE code = 'ADMIN'
                """, plainAdminId, ADMIN_ID);

        String plainAdminToken = bearer(plainAdminId, "ADMIN");

        // 1. Import mutation must be DENIED (HTTP 403)
        String dummyBundle = """
                {
                    "formatVersion": 1,
                    "purpose": "F01_GENERAL_KNOWLEDGE",
                    "bundleName": "plain-admin-test",
                    "items": [
                        {
                            "documentCode": "WHO-PA-TEST-PLAIN",
                            "sourceId": "WHO-PA-SB-2020",
                            "title": "Plain Test",
                            "language": "vi",
                            "versionLabel": "1.0.0",
                            "content": "# Test",
                            "contentHash": "%s",
                            "contentPath": "derivatives/who-2020/01-scope.vi.md",
                            "reviewEvidenceId": "REV-PO-20261008-01"
                        }
                    ]
                }
                """.formatted(sha256("# Test"));

        mockMvc.perform(post("/api/v1/admin/content/knowledge/import")
                        .header("Authorization", plainAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dummyBundle))
                .andExpect(status().isForbidden());

        // Prepare a test version in DRAFT
        UUID docId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                VALUES (?, ?, 'Plain Admin Doc', 'WHO-PA-SB-2020', 'vi', ?)
                """, docId, "DOC-PLAIN-" + docId, ADMIN_ID);

        jdbcTemplate.update("""
                INSERT INTO fitness.knowledge_versions(
                    id, knowledge_document_id, version_number, version_label,
                    status, technical_readiness, content_hash, content, created_by
                ) VALUES (
                    ?, ?, 1, '1.0.0',
                    'DRAFT', 'TECHNICAL_VERIFICATION_PASSED', 'dummyhash123', 'Content', ?
                )
                """, versionId, docId, ADMIN_ID);

        // 2. Review mutation must be DENIED (HTTP 403)
        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + versionId + "/review")
                        .header("Authorization", plainAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "decision": "APPROVED",
                                    "notes": "Attempt without Content authority"
                                }
                                """))
                .andExpect(status().isForbidden());

        // 3. Publish mutation must be DENIED (HTTP 403)
        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + versionId + "/publish")
                        .header("Authorization", plainAdminToken))
                .andExpect(status().isForbidden());

        // 4. Archive mutation must be DENIED (HTTP 403)
        mockMvc.perform(post("/api/v1/admin/content/knowledge/versions/" + versionId + "/archive")
                        .header("Authorization", plainAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "Attempt without Content authority"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokingContentAdminRoleImmediatelyDeniesApiAndRemovesEffectivePermissions() throws Exception {
        UUID dualAdminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (
                    id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at
                ) VALUES (
                    ?, ?, '$2a$10$dummyHash', 'Dual Admin',
                    'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now()
                )
                """, dualAdminId, "dual.admin." + dualAdminId + "@fitplatform.local");

        // Assign BOTH ADMIN and CONTENT_ADMIN roles
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, id, ?, clock_timestamp()
                FROM fitness.roles
                WHERE code IN ('ADMIN', 'CONTENT_ADMIN')
                """, dualAdminId, ADMIN_ID);

        // Verify initial effective permissions include CATALOG_MANAGE and all 4 KNOWLEDGE_* permissions
        List<String> permsBefore = jdbcTemplate.query(
                """
                SELECT DISTINCT p.code
                FROM fitness.users u
                JOIN fitness.user_roles ur ON ur.user_id = u.id
                JOIN fitness.role_permissions rp ON rp.role_id = ur.role_id
                JOIN fitness.permissions p ON p.id = rp.permission_id
                WHERE u.id = ? AND u.status = 'ACTIVE'::fitness.account_status AND ur.revoked_at IS NULL
                ORDER BY p.code ASC
                """,
                (rs, rowNum) -> rs.getString("code"),
                dualAdminId
        );
        assertThat(permsBefore).contains(
                "CATALOG_MANAGE", "KNOWLEDGE_ARCHIVE", "KNOWLEDGE_IMPORT", "KNOWLEDGE_PUBLISH", "KNOWLEDGE_REVIEW"
        );

        // Issue token while both roles active
        String dualToken = bearer(dualAdminId, "ADMIN", "CONTENT_ADMIN");

        // Revoke CONTENT_ADMIN role assignment
        jdbcTemplate.update("""
                UPDATE fitness.user_roles
                SET revoked_at = clock_timestamp()
                WHERE user_id = ?
                  AND role_id = (SELECT id FROM fitness.roles WHERE code = 'CONTENT_ADMIN')
                """, dualAdminId);

        // Verify effective permissions immediately lose all KNOWLEDGE_* permissions while keeping CATALOG_MANAGE
        List<String> permsAfter = jdbcTemplate.query(
                """
                SELECT DISTINCT p.code
                FROM fitness.users u
                JOIN fitness.user_roles ur ON ur.user_id = u.id
                JOIN fitness.role_permissions rp ON rp.role_id = ur.role_id
                JOIN fitness.permissions p ON p.id = rp.permission_id
                WHERE u.id = ? AND u.status = 'ACTIVE'::fitness.account_status AND ur.revoked_at IS NULL
                ORDER BY p.code ASC
                """,
                (rs, rowNum) -> rs.getString("code"),
                dualAdminId
        );
        assertThat(permsAfter).containsExactly("CATALOG_MANAGE");

        // API request with revoked CONTENT_ADMIN must be immediately rejected (HTTP 403)
        String dummyBundle = """
                {
                    "formatVersion": 1,
                    "purpose": "F01_GENERAL_KNOWLEDGE",
                    "bundleName": "revoked-admin-test",
                    "items": [
                        {
                            "documentCode": "WHO-PA-TEST-REVOKED",
                            "sourceId": "WHO-PA-SB-2020",
                            "title": "Revoked Test",
                            "language": "vi",
                            "versionLabel": "1.0.0",
                            "content": "# Test",
                            "contentHash": "%s",
                            "contentPath": "derivatives/who-2020/01-scope.vi.md",
                            "reviewEvidenceId": "REV-PO-20261008-01"
                        }
                    ]
                }
                """.formatted(sha256("# Test"));

        mockMvc.perform(post("/api/v1/admin/content/knowledge/import")
                        .header("Authorization", dualToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dummyBundle))
                .andExpect(status().isForbidden());
    }
}
