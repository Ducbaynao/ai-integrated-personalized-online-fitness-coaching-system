package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V35ContentKnowledgeGovernanceMigrationIntegrationTest {

    @Test
    void cleanInstallReachesV35WithGovernanceConstraintsAndPermissions() {
        try (var db = database("content_v35_clean")) {
            db.start();
            var source = source(db);
            migrate(source, "35");
            var jdbc = new JdbcTemplate(source);

            assertThat(version(jdbc)).isEqualTo("35");

            // Verify knowledge_documents new columns
            Integer docCols = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'fitness' AND table_name = 'knowledge_documents'
                      AND column_name IN ('document_code', 'source_id', 'language')
                    """, Integer.class);
            assertThat(docCols).isEqualTo(3);

            // Verify knowledge_versions new columns
            Integer verCols = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'fitness' AND table_name = 'knowledge_versions'
                      AND column_name IN ('version_label', 'technical_readiness', 'hold_reason', 'content_path', 'review_evidence_id', 'content')
                    """, Integer.class);
            assertThat(verCols).isEqualTo(6);

            // Verify knowledge_version_reviews new columns (sequence, content_hash, reviewer_identity, reviewer_role)
            Integer reviewCols = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'fitness' AND table_name = 'knowledge_version_reviews'
                      AND column_name IN ('review_sequence', 'content_hash', 'reviewer_identity', 'reviewer_role')
                    """, Integer.class);
            assertThat(reviewCols).isEqualTo(4);

            // Verify outbox columns in knowledge_processing_runs
            Integer outboxCols = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'fitness' AND table_name = 'knowledge_processing_runs'
                      AND column_name IN ('operation_key', 'locked_at', 'locked_by', 'attempt_count', 'max_attempts', 'next_attempt_at', 'lease_expires_at')
                    """, Integer.class);
            assertThat(outboxCols).isEqualTo(7);

            // Verify claim_token does NOT exist yet in V35 (added later in V36)
            Integer claimTokenBeforeV36 = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'fitness' AND table_name = 'knowledge_processing_runs'
                      AND column_name = 'claim_token'
                    """, Integer.class);
            assertThat(claimTokenBeforeV36).isEqualTo(0);

            // Verify permissions seeded
            Integer permCount = jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.permissions
                    WHERE code IN ('KNOWLEDGE_IMPORT', 'KNOWLEDGE_REVIEW', 'KNOWLEDGE_PUBLISH', 'KNOWLEDGE_ARCHIVE')
                    """, Integer.class);
            assertThat(permCount).isEqualTo(4);

            // Verify ADMIN role assigned permissions
            Integer rolePermCount = jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.role_permissions rp
                    JOIN fitness.roles r ON r.id = rp.role_id
                    JOIN fitness.permissions p ON p.id = rp.permission_id
                    WHERE r.code = 'ADMIN'
                      AND p.code IN ('KNOWLEDGE_IMPORT', 'KNOWLEDGE_REVIEW', 'KNOWLEDGE_PUBLISH', 'KNOWLEDGE_ARCHIVE')
                    """, Integer.class);
            assertThat(rolePermCount).isEqualTo(4);
        }
    }

    @Test
    void legacyMigrationWithMultipleVersionsPerDocumentSucceeds() {
        try (var db = database("content_v35_legacy")) {
            db.start();
            var source = source(db);
            // 1. Migrate up to V31 only (simulating pre-existing database before V35)
            migrate(source, "31");
            var jdbc = new JdbcTemplate(source);

            // Verify schema before V35 governance does not have new Content columns
            Integer preDocCols = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'fitness' AND table_name = 'knowledge_documents'
                      AND column_name IN ('document_code', 'source_id', 'language')
                    """, Integer.class);
            assertThat(preDocCols).isEqualTo(0);

            UUID adminId = UUID.randomUUID();
            UUID docId = UUID.randomUUID();
            UUID v1Id = UUID.randomUUID();
            UUID v2Id = UUID.randomUUID();

            jdbc.update("""
                    INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone)
                    VALUES (?, ?, 'hash', 'Admin', 'ACTIVE', 'vi-VN', 'Etc/UTC')
                    """, adminId, "admin_" + adminId + "@example.com");

            jdbc.update("""
                    INSERT INTO fitness.knowledge_documents(id, title, created_by)
                    VALUES (?, 'Legacy Document', ?)
                    """, docId, adminId);

            // In V9/V15 schema, versions only had version_number (no version_label)
            jdbc.update("""
                    INSERT INTO fitness.knowledge_versions(id, knowledge_document_id, version_number, status, content_hash, created_by)
                    VALUES (?, ?, 1, 'ARCHIVED', 'hash1', ?)
                    """, v1Id, docId, adminId);

            jdbc.update("""
                    INSERT INTO fitness.knowledge_versions(id, knowledge_document_id, version_number, status, content_hash, created_by)
                    VALUES (?, ?, 2, 'ACTIVE', 'hash2', ?)
                    """, v2Id, docId, adminId);

            // 2. Now run forward migration V35:
            // Must NOT fail with duplicate key violation on (knowledge_document_id, version_label)
            migrate(source, "35");

            assertThat(version(jdbc)).isEqualTo("35");

            // Verify backfilled version_label uses version_number string
            String v1Label = jdbc.queryForObject("SELECT version_label FROM fitness.knowledge_versions WHERE id = ?", String.class, v1Id);
            String v2Label = jdbc.queryForObject("SELECT version_label FROM fitness.knowledge_versions WHERE id = ?", String.class, v2Id);
            assertThat(v1Label).isEqualTo("1");
            assertThat(v2Label).isEqualTo("2");
        }
    }

    @Test
    void publishTriggerBlocksHeldAsDraftAndDraftStagedFromActivation() {
        try (var db = database("content_v35_guard")) {
            db.start();
            var source = source(db);
            migrate(source, "35");
            var jdbc = new JdbcTemplate(source);

            UUID adminId = UUID.randomUUID();
            UUID docId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();

            jdbc.update("""
                    INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone)
                    VALUES (?, ?, 'hash', 'Admin', 'ACTIVE', 'vi-VN', 'Etc/UTC')
                    """, adminId, "admin_" + adminId + "@example.com");

            jdbc.update("""
                    INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                    VALUES (?, 'NIDDK-WEIGHT-FACTORS-2023-VI-05', 'NIDDK Medicines', 'NIDDK-WEIGHT-FACTORS-2023', 'vi', ?)
                    """, docId, adminId);

            // Insert version marked as HELD_AS_DRAFT
            jdbc.update("""
                    INSERT INTO fitness.knowledge_versions(
                        id, knowledge_document_id, version_number, version_label,
                        status, technical_readiness, hold_reason, content_hash, content, created_by
                    ) VALUES (
                        ?, ?, 1, '1.1.0',
                        'DRAFT', 'HELD_AS_DRAFT', 'Awaiting clinical review', 'hash123', 'Sample content', ?
                    )
                    """, versionId, docId, adminId);

            // Even if review approved is inserted:
            jdbc.update("""
                    INSERT INTO fitness.knowledge_version_reviews(knowledge_version_id, reviewer_id, decision, content_hash, notes)
                    VALUES (?, ?, 'APPROVED', 'hash123', 'Notes')
                    """, versionId, adminId);

            // Attempting to activate HELD_AS_DRAFT version must fail via trigger
            assertThatThrownBy(() -> {
                jdbc.update("""
                        UPDATE fitness.knowledge_versions
                        SET status = 'ACTIVE',
                            reviewed_by = ?,
                            reviewed_at = now(),
                            published_by = ?,
                            published_at = now()
                        WHERE id = ?
                        """, adminId, adminId, versionId);
            }).hasMessageContaining("Knowledge version technical readiness must be TECHNICAL_VERIFICATION_PASSED to publish");

            // Attempting with DRAFT_STAGED also fails
            jdbc.update("UPDATE fitness.knowledge_versions SET technical_readiness = 'DRAFT_STAGED' WHERE id = ?", versionId);
            assertThatThrownBy(() -> {
                jdbc.update("""
                        UPDATE fitness.knowledge_versions
                        SET status = 'ACTIVE',
                            reviewed_by = ?,
                            reviewed_at = now(),
                            published_by = ?,
                            published_at = now()
                        WHERE id = ?
                        """, adminId, adminId, versionId);
            }).hasMessageContaining("Knowledge version technical readiness must be TECHNICAL_VERIFICATION_PASSED to publish");
        }
    }

    @Test
    void publishTriggerBlocksWhenReviewHashIsNullBlankOrMismatched() {
        try (var db = database("content_v35_hash_guard")) {
            db.start();
            var source = source(db);
            migrate(source, "35");
            var jdbc = new JdbcTemplate(source);

            UUID adminId = UUID.randomUUID();
            UUID docId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();

            jdbc.update("""
                    INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone)
                    VALUES (?, ?, 'hash', 'Admin', 'ACTIVE', 'vi-VN', 'Etc/UTC')
                    """, adminId, "admin_" + adminId + "@example.com");

            jdbc.update("""
                    INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                    VALUES (?, 'WHO-PA-SB-2020-VI-01', 'WHO Activity', 'WHO-PA-SB-2020', 'vi', ?)
                    """, docId, adminId);

            jdbc.update("""
                    INSERT INTO fitness.knowledge_versions(
                        id, knowledge_document_id, version_number, version_label,
                        status, technical_readiness, content_hash, content, created_by
                    ) VALUES (
                        ?, ?, 1, '1.1.0',
                        'DRAFT', 'TECHNICAL_VERIFICATION_PASSED', 'correct_hash_123', 'Sample content', ?
                    )
                    """, versionId, docId, adminId);

            // Case A: Review content_hash is NULL -> must fail
            jdbc.update("""
                    INSERT INTO fitness.knowledge_version_reviews(knowledge_version_id, reviewer_id, decision, content_hash, notes)
                    VALUES (?, ?, 'APPROVED', NULL, 'Notes')
                    """, versionId, adminId);

            assertThatThrownBy(() -> {
                jdbc.update("""
                        UPDATE fitness.knowledge_versions
                        SET status = 'ACTIVE',
                            reviewed_by = ?,
                            reviewed_at = now(),
                            published_by = ?,
                            published_at = now()
                        WHERE id = ?
                        """, adminId, adminId, versionId);
            }).hasMessageContaining("Knowledge version content hash does not match approved review hash");

            // Case B: Review content_hash is mismatched -> must fail
            jdbc.update("""
                    INSERT INTO fitness.knowledge_version_reviews(knowledge_version_id, reviewer_id, decision, content_hash, notes)
                    VALUES (?, ?, 'APPROVED', 'wrong_hash_xyz', 'Notes')
                    """, versionId, adminId);

            assertThatThrownBy(() -> {
                jdbc.update("""
                        UPDATE fitness.knowledge_versions
                        SET status = 'ACTIVE',
                            reviewed_by = ?,
                            reviewed_at = now(),
                            published_by = ?,
                            published_at = now()
                        WHERE id = ?
                        """, adminId, adminId, versionId);
            }).hasMessageContaining("Knowledge version content hash does not match approved review hash");
        }
    }

    @Test
    void publishTriggerRespectsMonotonicSequenceWhenTimestampsEqual() {
        try (var db = database("content_v35_review_seq")) {
            db.start();
            var source = source(db);
            migrate(source, "35");
            var jdbc = new JdbcTemplate(source);

            UUID adminId = UUID.randomUUID();
            UUID docId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();

            jdbc.update("""
                    INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone)
                    VALUES (?, ?, 'hash', 'Admin', 'ACTIVE', 'vi-VN', 'Etc/UTC')
                    """, adminId, "admin_" + adminId + "@example.com");

            jdbc.update("""
                    INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                    VALUES (?, 'WHO-PA-SB-2020-VI-01', 'WHO Activity', 'WHO-PA-SB-2020', 'vi', ?)
                    """, docId, adminId);

            jdbc.update("""
                    INSERT INTO fitness.knowledge_versions(
                        id, knowledge_document_id, version_number, version_label,
                        status, technical_readiness, content_hash, content, created_by
                    ) VALUES (
                        ?, ?, 1, '1.1.0',
                        'DRAFT', 'TECHNICAL_VERIFICATION_PASSED', 'hash123', 'Sample content', ?
                    )
                    """, versionId, docId, adminId);

            // Insert 1st review (APPROVED) and 2nd review (REJECTED) with IDENTICAL timestamp
            jdbc.update("""
                    INSERT INTO fitness.knowledge_version_reviews(knowledge_version_id, reviewer_id, decision, content_hash, notes, reviewed_at)
                    VALUES (?, ?, 'APPROVED', 'hash123', 'Old approval', '2026-10-11 12:00:00+00')
                    """, versionId, adminId);

            jdbc.update("""
                    INSERT INTO fitness.knowledge_version_reviews(knowledge_version_id, reviewer_id, decision, content_hash, notes, reviewed_at)
                    VALUES (?, ?, 'REJECTED', 'hash123', 'Rejection with same timestamp', '2026-10-11 12:00:00+00')
                    """, versionId, adminId);

            // Because 2nd review was inserted later, its review_sequence is higher:
            // Hence effective review is REJECTED and publish MUST BE BLOCKED!
            assertThatThrownBy(() -> {
                jdbc.update("""
                        UPDATE fitness.knowledge_versions
                        SET status = 'ACTIVE',
                            reviewed_by = ?,
                            reviewed_at = now(),
                            published_by = ?,
                            published_at = now()
                        WHERE id = ?
                        """, adminId, adminId, versionId);
            }).hasMessageContaining("Knowledge version requires effective APPROVED review before activation");
        }
    }

    @Test
    void activeContentIsImmutableOnceActive() {
        try (var db = database("content_v35_immutability")) {
            db.start();
            var source = source(db);
            migrate(source, "35");
            var jdbc = new JdbcTemplate(source);

            UUID adminId = UUID.randomUUID();
            UUID docId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();

            jdbc.update("""
                    INSERT INTO fitness.users(id, email, password_hash, display_name, status, preferred_locale, timezone)
                    VALUES (?, ?, 'hash', 'Admin', 'ACTIVE', 'vi-VN', 'Etc/UTC')
                    """, adminId, "admin_" + adminId + "@example.com");

            jdbc.update("""
                    INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                    VALUES (?, 'WHO-PA-SB-2020-VI-01', 'WHO Activity', 'WHO-PA-SB-2020', 'vi', ?)
                    """, docId, adminId);

            jdbc.update("""
                    INSERT INTO fitness.knowledge_versions(
                        id, knowledge_document_id, version_number, version_label,
                        status, technical_readiness, content_hash, content, created_by,
                        reviewed_by, reviewed_at, published_by, published_at
                    ) VALUES (
                        ?, ?, 1, '1.1.0',
                        'DRAFT', 'TECHNICAL_VERIFICATION_PASSED', 'hash123', 'Initial content', ?,
                        ?, now(), ?, now()
                    )
                    """, versionId, docId, adminId, adminId, adminId);

            jdbc.update("""
                    INSERT INTO fitness.knowledge_version_reviews(knowledge_version_id, reviewer_id, decision, content_hash, notes)
                    VALUES (?, ?, 'APPROVED', 'hash123', 'Approved')
                    """, versionId, adminId);

            // Activate version
            jdbc.update("UPDATE fitness.knowledge_versions SET status = 'ACTIVE' WHERE id = ?", versionId);

            // Modifying content or content_hash of ACTIVE version MUST BE REJECTED by trigger
            assertThatThrownBy(() -> {
                jdbc.update("UPDATE fitness.knowledge_versions SET content = 'Tampered content' WHERE id = ?", versionId);
            }).hasMessageContaining("Published content is immutable");

            assertThatThrownBy(() -> {
                jdbc.update("UPDATE fitness.knowledge_versions SET content_hash = 'different_hash' WHERE id = ?", versionId);
            }).hasMessageContaining("Published content is immutable");
        }
    }

    @Test
    void cleanInstallReachesV36WithClaimFencingAndAttemptGuards() {
        try (var db = database("content_v36_clean")) {
            db.start();
            var source = source(db);
            migrate(source, "36");
            var jdbc = new JdbcTemplate(source);

            assertThat(version(jdbc)).isEqualTo("36");

            // Verify claim_token column exists in knowledge_processing_runs
            Integer claimTokenCol = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'fitness' AND table_name = 'knowledge_processing_runs'
                      AND column_name = 'claim_token'
                    """, Integer.class);
            assertThat(claimTokenCol).isEqualTo(1);

            // Verify attempt count constraints exist
            Integer attemptConstraints = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.table_constraints
                    WHERE table_schema = 'fitness' AND table_name = 'knowledge_processing_runs'
                      AND constraint_name IN ('chk_processing_runs_max_attempts', 'chk_processing_runs_attempt_count')
                    """, Integer.class);
            assertThat(attemptConstraints).isEqualTo(2);

            // Verify claim fencing index exists
            Integer fenceIndex = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_indexes
                    WHERE schemaname = 'fitness' AND tablename = 'knowledge_processing_runs'
                      AND indexname = 'idx_knowledge_processing_runs_claim_fence'
                    """, Integer.class);
            assertThat(fenceIndex).isEqualTo(1);
        }
    }

    @Test
    void cleanInstallReachesV37WithFullLifecycleOperationUniqueness() {
        try (var db = database("content_v37_clean")) {
            db.start();
            var source = source(db);
            migrate(source, "37");
            var jdbc = new JdbcTemplate(source);

            assertThat(version(jdbc)).isEqualTo("37");

            // Verify operation_key is NOT NULL
            String isNullable = jdbc.queryForObject("""
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = 'fitness' AND table_name = 'knowledge_processing_runs'
                      AND column_name = 'operation_key'
                    """, String.class);
            assertThat(isNullable).isEqualTo("NO");

            // Verify unique constraint on operation_key exists
            Integer uqConstraint = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.table_constraints
                    WHERE table_schema = 'fitness' AND table_name = 'knowledge_processing_runs'
                      AND constraint_name = 'uq_knowledge_processing_runs_operation_key'
                      AND constraint_type = 'UNIQUE'
                    """, Integer.class);
            assertThat(uqConstraint).isEqualTo(1);

            // Verify non-empty check constraint on operation_key exists
            Integer notEmptyConstraint = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.table_constraints
                    WHERE table_schema = 'fitness' AND table_name = 'knowledge_processing_runs'
                      AND constraint_name = 'chk_processing_runs_operation_key_not_empty'
                    """, Integer.class);
            assertThat(notEmptyConstraint).isEqualTo(1);
        }
    }

    @Test
    void v37Migration_reconcilesLegacyNullKeysAndFailsOnLegacyDuplicates() {
        try (var db = database("content_v37_legacy")) {
            db.start();
            var source = source(db);
            // Migrate up to V36 first
            migrate(source, "36");
            var jdbc = new JdbcTemplate(source);

            UUID adminId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.users (
                        id, email, password_hash, display_name, status, preferred_locale, timezone, created_at, updated_at
                    ) VALUES (?, 'admin@fitplatform.local', 'hash', 'Admin', 'ACTIVE', 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                    """, adminId);

            UUID docId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.knowledge_documents(id, document_code, title, source_id, language, created_by)
                    VALUES (?, 'DOC-LEGACY', 'Legacy Doc', 'WHO', 'vi', ?)
                    """, docId, adminId);

            UUID versionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.knowledge_versions(
                        id, knowledge_document_id, version_number, version_label, status,
                        technical_readiness, content_hash, content, created_by
                    ) VALUES (?, ?, 1, '1.0.0', 'ACTIVE', 'TECHNICAL_VERIFICATION_PASSED', 'hash', 'Content', ?)
                    """, versionId, docId, adminId);

            UUID run1Id = UUID.randomUUID();
            UUID run2Id = UUID.randomUUID();
            // Insert two duplicate operation keys in legacy V36
            jdbc.update("""
                    INSERT INTO fitness.knowledge_processing_runs (
                        id, knowledge_version_id, run_type, status, operation_key, created_at
                    ) VALUES (?, ?, 'REINDEX', 'SUCCEEDED', 'DUP-KEY', now())
                    """, run1Id, versionId);

            jdbc.update("""
                    INSERT INTO fitness.knowledge_processing_runs (
                        id, knowledge_version_id, run_type, status, operation_key, created_at
                    ) VALUES (?, ?, 'REINDEX', 'FAILED', 'DUP-KEY', now())
                    """, run2Id, versionId);

            // Migrating to V37 MUST fail with diagnostic error because duplicate keys exist
            assertThatThrownBy(() -> migrate(source, "37"))
                    .hasMessageContaining("duplicate operation_key entries in legacy data");

            // Remove the duplicate and insert a row with null operation_key
            jdbc.update("DELETE FROM fitness.knowledge_processing_runs WHERE id = ?", run2Id);
            UUID nullKeyRunId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.knowledge_processing_runs (
                        id, knowledge_version_id, run_type, status, operation_key, created_at
                    ) VALUES (?, ?, 'REINDEX', 'SUCCEEDED', NULL, now())
                    """, nullKeyRunId, versionId);

            // Migrating to V37 now succeeds
            migrate(source, "37");
            assertThat(version(jdbc)).isEqualTo("37");

            // Null row must have been reconciled to 'LEGACY:' || id
            String reconciledKey = jdbc.queryForObject(
                    "SELECT operation_key FROM fitness.knowledge_processing_runs WHERE id = ?", String.class, nullKeyRunId);
            assertThat(reconciledKey).isEqualTo("LEGACY:" + nullKeyRunId);
        }
    }

    private PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(name)
                .withUsername("postgres")
                .withPassword("postgres");
    }

    private DataSource source(PostgreSQLContainer<?> db) {
        var source = new DriverManagerDataSource();
        source.setDriverClassName(db.getDriverClassName());
        source.setUrl(db.getJdbcUrl());
        source.setUsername(db.getUsername());
        source.setPassword(db.getPassword());
        return source;
    }

    private void migrate(DataSource source, String target) {
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .defaultSchema("fitness")
                .target(target)
                .load()
                .migrate();
    }

    private String version(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT version FROM fitness.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class);
    }
}
