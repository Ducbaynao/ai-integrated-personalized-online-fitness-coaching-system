package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ContentValidationException;
import com.fitnesscoaching.platform.modules.content.domain.ImportKnowledgeItem;
import com.fitnesscoaching.platform.modules.content.domain.ReviewDecision;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side trusted registry for specialist review evidence and corpus integrity bindings.
 * Resolves review evidence from verified review records and derivative readiness indices.
 * Fully fail-closed: missing, corrupted, or unverified records never produce default approval or readiness.
 */
@Component
public class SpecialistReviewEvidenceRegistry {

    private static final Logger log = LoggerFactory.getLogger(SpecialistReviewEvidenceRegistry.class);

    public record VerifiedArticleEvidence(
            String reviewEvidenceId,
            String documentCode,
            String sourceId,
            String versionLabel,
            String contentSha256,
            String contentPath,
            Integer bytes,
            String reviewerIdentity,
            String reviewerRole,
            ReviewDecision decision,
            TechnicalReadiness technicalReadiness,
            String holdReason,
            String baseDocumentId,
            String baseVersion,
            String baseSha256
    ) {}

    private final ObjectMapper objectMapper;
    private final Map<String, VerifiedArticleEvidence> verifiedArticles = new HashMap<>();
    private final Map<String, Boolean> knownReviewRecordIds = new HashMap<>();
    private boolean available = false;
    private String initializationError = null;

    public SpecialistReviewEvidenceRegistry(ObjectMapper objectMapper) {
        this(objectMapper, (String) null, (String) null);
    }

    @Autowired
    public SpecialistReviewEvidenceRegistry(
            ObjectMapper objectMapper,
            @Value("${knowledge.review-records.path:}") String customReviewRecordPath,
            @Value("${knowledge.derivative-index.path:}") String customDerivativeIndexPath
    ) {
        this.objectMapper = objectMapper;
        initialize(customReviewRecordPath, customDerivativeIndexPath);
    }

    public SpecialistReviewEvidenceRegistry(
            ObjectMapper objectMapper,
            Path reviewRecordPath,
            Path derivativeIndexPath
    ) {
        this.objectMapper = objectMapper;
        initializePaths(reviewRecordPath, derivativeIndexPath);
    }

    private void initialize(String customReviewRecordPath, String customDerivativeIndexPath) {
        Path reviewRecordPath = resolvePath(
                customReviewRecordPath,
                "data/knowledge/review-records/project-owner-specialist-review-2026-10-08.json",
                "../../data/knowledge/review-records/project-owner-specialist-review-2026-10-08.json"
        );
        Path derivativeIndexPath = resolvePath(
                customDerivativeIndexPath,
                "data/knowledge/derivative-index.json",
                "../../data/knowledge/derivative-index.json"
        );
        initializePaths(reviewRecordPath, derivativeIndexPath);
    }

    private void initializePaths(Path reviewRecordPath, Path derivativeIndexPath) {
        try {
            if (reviewRecordPath == null || !Files.isRegularFile(reviewRecordPath)) {
                this.available = false;
                this.initializationError = "Review record file not found or not a regular file: " + reviewRecordPath;
                log.warn("Specialist review evidence registry unavailable: {}", initializationError);
                return;
            }

            Map<String, String> holdReasons = new HashMap<>();
            Map<String, TechnicalReadiness> readinessMap = new HashMap<>();

            if (derivativeIndexPath != null && Files.isRegularFile(derivativeIndexPath)) {
                JsonNode root = objectMapper.readTree(Files.readAllBytes(derivativeIndexPath));
                JsonNode packages = root.path("derivative_packages");
                for (JsonNode pkg : packages) {
                    for (JsonNode art : pkg.path("articles")) {
                        String docId = art.path("document_id").asText();
                        String ver = art.path("version").asText();
                        String key = docId + ":" + ver;
                        String readinessStr = art.path("technical_readiness").asText();
                        if ("HELD_AS_DRAFT".equals(readinessStr)) {
                            readinessMap.put(key, TechnicalReadiness.HELD_AS_DRAFT);
                            holdReasons.put(key, art.path("changelog").asText("Held by specialist/governance policy"));
                        } else if ("TECHNICAL_VERIFICATION_PASSED".equals(readinessStr)) {
                            readinessMap.put(key, TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED);
                        } else {
                            readinessMap.put(key, TechnicalReadiness.DRAFT_STAGED);
                        }
                    }
                }
            }

            JsonNode reviewRoot = objectMapper.readTree(Files.readAllBytes(reviewRecordPath));

            // Strictly validate required structural fields
            if (!reviewRoot.hasNonNull("review_record_id") || reviewRoot.path("review_record_id").asText().isBlank()) {
                throw new IllegalStateException("Review record missing required non-blank field: review_record_id");
            }
            if (!reviewRoot.hasNonNull("specialist_review_verdict") || reviewRoot.path("specialist_review_verdict").asText().isBlank()) {
                throw new IllegalStateException("Review record missing required non-blank field: specialist_review_verdict");
            }
            if (!reviewRoot.hasNonNull("reviewer_identity") || reviewRoot.path("reviewer_identity").asText().isBlank()) {
                throw new IllegalStateException("Review record missing required non-blank field: reviewer_identity");
            }
            if (!reviewRoot.hasNonNull("reviewer_role") || reviewRoot.path("reviewer_role").asText().isBlank()) {
                throw new IllegalStateException("Review record missing required non-blank field: reviewer_role");
            }

            String recordId = reviewRoot.path("review_record_id").asText();
            String reviewerRole = reviewRoot.path("reviewer_role").asText();
            String reviewerIdentity = reviewRoot.path("reviewer_identity").asText();
            String verdict = reviewRoot.path("specialist_review_verdict").asText();

            ReviewDecision decision;
            try {
                decision = ReviewDecision.valueOf(verdict);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Invalid specialist_review_verdict: " + verdict, e);
            }

            knownReviewRecordIds.put(recordId, true);

            // 1. Process snapshot articles:
            // Snapshot articles represent curated source snapshots. They do NOT carry derivative technical verification,
            // so their technical readiness remains strictly DRAFT_STAGED (fail-closed, not auto-promoted to passed).
            for (JsonNode art : reviewRoot.path("approved_snapshot_articles")) {
                registerArticle(recordId, reviewerIdentity, reviewerRole, decision, art, readinessMap, holdReasons, false);
            }

            // 2. Process derivative articles:
            // Derivative articles have readiness derived from the verified derivative index.
            for (JsonNode art : reviewRoot.path("approved_derivative_articles")) {
                registerArticle(recordId, reviewerIdentity, reviewerRole, decision, art, readinessMap, holdReasons, true);
            }

            this.available = true;
            this.initializationError = null;
            log.info("Loaded {} verified specialist review articles from {}", verifiedArticles.size(), reviewRecordPath);
        } catch (Exception e) {
            this.available = false;
            this.initializationError = "Failed to load review evidence from disk: " + e.getMessage();
            this.verifiedArticles.clear();
            this.knownReviewRecordIds.clear();
            log.warn("Specialist review evidence registry initialization failed: {}", initializationError, e);
        }
    }

    private void registerArticle(
            String recordId,
            String reviewerIdentity,
            String reviewerRole,
            ReviewDecision decision,
            JsonNode art,
            Map<String, TechnicalReadiness> readinessMap,
            Map<String, String> holdReasons,
            boolean isDerivative
    ) {
        String docId = art.path("document_id").asText();
        String ver = art.path("version").asText();
        String sourceId = art.hasNonNull("source_id") ? art.path("source_id").asText() : null;
        String hash = art.path("content_sha256").asText();
        String path = art.hasNonNull("content_path") ? art.path("content_path").asText() : null;
        Integer bytes = art.hasNonNull("bytes") ? art.path("bytes").asInt() : null;

        String key = docId + ":" + ver;
        TechnicalReadiness readiness;
        String holdReason;

        if (isDerivative) {
            // Derivative readiness comes strictly from derivative-index; if absent, defaults to DRAFT_STAGED
            readiness = readinessMap.getOrDefault(key, TechnicalReadiness.DRAFT_STAGED);
            holdReason = holdReasons.get(key);
        } else {
            // Snapshot articles have not undergone derivative technical verification; must stay DRAFT_STAGED
            readiness = TechnicalReadiness.DRAFT_STAGED;
            holdReason = null;
        }

        String baseDoc = isDerivative && art.hasNonNull("base_document_id") ? art.path("base_document_id").asText() : null;
        String baseVer = isDerivative && art.hasNonNull("base_version") ? art.path("base_version").asText() : null;
        String baseHash = isDerivative && art.hasNonNull("base_sha256") ? art.path("base_sha256").asText() : null;

        VerifiedArticleEvidence evidence = new VerifiedArticleEvidence(
                recordId, docId, sourceId, ver, hash, path, bytes,
                reviewerIdentity, reviewerRole, decision,
                readiness, holdReason,
                baseDoc, baseVer, baseHash
        );
        verifiedArticles.put(recordId + ":" + docId + ":" + ver, evidence);
    }

    private Path resolvePath(String customPath, String path1, String path2) {
        if (customPath != null && !customPath.isBlank()) {
            Path custom = Path.of(customPath);
            if (Files.exists(custom)) return custom;
        }
        Path p1 = Path.of(path1);
        if (Files.exists(p1)) return p1;
        Path p2 = Path.of(path2);
        if (Files.exists(p2)) return p2;
        return null;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getInitializationError() {
        return initializationError;
    }

    /**
     * Verifies that the reviewEvidenceId and the item's content, hash, version, path, bytes, and lineage
     * strictly match a trusted specialist review record.
     *
     * @return VerifiedArticleEvidence if verified, or empty if no review evidence was requested.
     * @throws ContentValidationException if evidenceId is unknown, registry is unavailable, binding mismatches,
     *                                    path is invalid/traversal, bytes mismatch, or hash is tampered.
     */
    public Optional<VerifiedArticleEvidence> verifyAndResolve(String evidenceId, ImportKnowledgeItem item) {
        if (evidenceId == null || evidenceId.isBlank()) {
            return Optional.empty();
        }

        if (!available) {
            throw new ContentValidationException("Specialist review evidence registry is unavailable or misconfigured: " + initializationError);
        }

        if (!knownReviewRecordIds.containsKey(evidenceId)) {
            throw new ContentValidationException("Unknown or untrusted specialist review evidence ID: " + evidenceId);
        }

        // Validate content path safety
        String contentPath = item.contentPath();
        if (contentPath != null && !contentPath.isBlank()) {
            if (contentPath.startsWith("/") || contentPath.startsWith("\\") || contentPath.contains(":") ||
                    contentPath.contains("..") || contentPath.contains("//") || contentPath.contains("\\\\")) {
                throw new ContentValidationException("Content path is invalid or contains traversal sequences: " + contentPath);
            }
        }

        // Validate actual UTF-8 byte count against client-supplied sourceBytes
        int actualBytes = item.content() != null ? item.content().getBytes(StandardCharsets.UTF_8).length : 0;
        if (item.sourceBytes() != null && item.sourceBytes() != actualBytes) {
            throw new ContentValidationException("sourceBytes mismatch for " + item.documentCode() +
                    ": client provided " + item.sourceBytes() + " bytes but UTF-8 content is " + actualBytes + " bytes");
        }

        String lookupKey = evidenceId + ":" + item.documentCode() + ":" + item.versionLabel();
        VerifiedArticleEvidence verified = verifiedArticles.get(lookupKey);

        if (verified == null) {
            throw new ContentValidationException("Document " + item.documentCode() + " version " +
                    item.versionLabel() + " is not covered by review evidence " + evidenceId);
        }

        if (verified.sourceId() != null && item.sourceId() != null && !verified.sourceId().equals(item.sourceId())) {
            throw new ContentValidationException("Source ID mismatch for " + item.documentCode() +
                    ": expected " + verified.sourceId() + " but got " + item.sourceId());
        }

        if (!verified.contentSha256().equalsIgnoreCase(item.contentHash())) {
            throw new ContentValidationException("Content hash mismatch for document " + item.documentCode() +
                    " version " + item.versionLabel() + " under review evidence " + evidenceId +
                    ": expected " + verified.contentSha256() + " but got " + item.contentHash());
        }

        if (verified.contentPath() != null) {
            if (contentPath == null || contentPath.isBlank()) {
                throw new ContentValidationException("Missing required contentPath for " + item.documentCode() +
                        ": expected " + verified.contentPath());
            }
            if (!verified.contentPath().equals(contentPath)) {
                throw new ContentValidationException("Content path mismatch for " + item.documentCode() +
                        ": expected " + verified.contentPath() + " but got " + contentPath);
            }
        }

        // Lineage verification for derivative articles
        if (verified.baseDocumentId() != null) {
            if (item.lineageBaseDocumentId() != null && !verified.baseDocumentId().equals(item.lineageBaseDocumentId())) {
                throw new ContentValidationException("Lineage base document mismatch for " + item.documentCode() +
                        ": expected " + verified.baseDocumentId() + " but got " + item.lineageBaseDocumentId());
            }
            if (item.lineageBaseVersion() != null && !verified.baseVersion().equals(item.lineageBaseVersion())) {
                throw new ContentValidationException("Lineage base version mismatch for " + item.documentCode() +
                        ": expected " + verified.baseVersion() + " but got " + item.lineageBaseVersion());
            }
            if (item.lineageBaseHash() != null && !verified.baseSha256().equalsIgnoreCase(item.lineageBaseHash())) {
                throw new ContentValidationException("Lineage base hash mismatch for " + item.documentCode() +
                        ": expected " + verified.baseSha256() + " but got " + item.lineageBaseHash());
            }
        }

        if (item.content() != null && verified.bytes() != null && actualBytes != verified.bytes()) {
            throw new ContentValidationException("Byte count mismatch for document " + item.documentCode() +
                    ": expected " + verified.bytes() + " bytes from evidence but UTF-8 content is " + actualBytes + " bytes");
        }

        return Optional.of(verified);
    }
}
