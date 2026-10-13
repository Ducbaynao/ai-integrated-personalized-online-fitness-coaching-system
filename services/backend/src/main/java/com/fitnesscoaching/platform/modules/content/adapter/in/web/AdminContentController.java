package com.fitnesscoaching.platform.modules.content.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ContentNotFoundException;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.content.adapter.in.web.dto.ArchiveKnowledgeVersionRequest;
import com.fitnesscoaching.platform.modules.content.adapter.in.web.dto.ImportCorpusBundleRequest;
import com.fitnesscoaching.platform.modules.content.adapter.in.web.dto.ImportCorpusResponse;
import com.fitnesscoaching.platform.modules.content.adapter.in.web.dto.KnowledgeDocumentResponse;
import com.fitnesscoaching.platform.modules.content.adapter.in.web.dto.KnowledgeVersionResponse;
import com.fitnesscoaching.platform.modules.content.adapter.in.web.dto.ReviewKnowledgeVersionRequest;
import com.fitnesscoaching.platform.modules.content.application.port.in.AdminKnowledgeQueryUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.in.ContentLifecycleUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.in.ImportKnowledgeCorpusUseCase;
import com.fitnesscoaching.platform.modules.content.domain.ImportCorpusResult;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/content/knowledge")
@PreAuthorize("hasRole('ADMIN')")
public class AdminContentController {

    private final ImportKnowledgeCorpusUseCase importUseCase;
    private final ContentLifecycleUseCase lifecycleUseCase;
    private final AdminKnowledgeQueryUseCase queryUseCase;
    private final UserAccountStatusQuery accountStatusQuery;
    private final UserPermissionQuery permissionQuery;
    private final UserRoleQuery userRoleQuery;

    public AdminContentController(
            ImportKnowledgeCorpusUseCase importUseCase,
            ContentLifecycleUseCase lifecycleUseCase,
            AdminKnowledgeQueryUseCase queryUseCase,
            UserAccountStatusQuery accountStatusQuery,
            UserPermissionQuery permissionQuery,
            UserRoleQuery userRoleQuery
    ) {
        this.importUseCase = importUseCase;
        this.lifecycleUseCase = lifecycleUseCase;
        this.queryUseCase = queryUseCase;
        this.accountStatusQuery = accountStatusQuery;
        this.permissionQuery = permissionQuery;
        this.userRoleQuery = userRoleQuery;
    }

    @PostMapping("/import")
    public ResponseEntity<ImportCorpusResponse> importCorpus(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ImportCorpusBundleRequest request
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        ImportCorpusResult result = importUseCase.importBundle(actorId, request.toDomain());
        return ResponseEntity.ok(ImportCorpusResponse.fromDomain(result));
    }

    @PostMapping("/versions/{versionId}/review")
    public ResponseEntity<Void> reviewVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID versionId,
            @Valid @RequestBody ReviewKnowledgeVersionRequest request
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        lifecycleUseCase.reviewVersion(actorId, versionId, request.decision(), request.notes());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/versions/{versionId}/publish")
    public ResponseEntity<Void> publishVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID versionId
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        lifecycleUseCase.publishVersion(actorId, versionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/versions/{versionId}/archive")
    public ResponseEntity<Void> archiveVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID versionId,
            @RequestBody(required = false) ArchiveKnowledgeVersionRequest request
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        String reason = request != null ? request.reason() : null;
        lifecycleUseCase.archiveVersion(actorId, versionId, reason);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/documents")
    public ResponseEntity<List<KnowledgeDocumentResponse>> listDocuments(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        validateActor(actorId);
        int offset = page * size;
        List<KnowledgeDocument> documents = queryUseCase.listDocuments(offset, size);
        return ResponseEntity.ok(documents.stream().map(KnowledgeDocumentResponse::fromDomain).toList());
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<KnowledgeDocumentResponse> getDocument(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID documentId
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        validateActor(actorId);
        KnowledgeDocument document = queryUseCase.getDocument(documentId)
                .orElseThrow(() -> new ContentNotFoundException("Document not found: " + documentId));
        return ResponseEntity.ok(KnowledgeDocumentResponse.fromDomain(document));
    }

    @GetMapping("/documents/{documentId}/versions")
    public ResponseEntity<List<KnowledgeVersionResponse>> listVersions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID documentId
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        validateActor(actorId);
        List<KnowledgeVersion> versions = queryUseCase.listVersions(documentId);
        return ResponseEntity.ok(versions.stream().map(KnowledgeVersionResponse::fromDomain).toList());
    }

    @GetMapping("/versions/{versionId}")
    public ResponseEntity<KnowledgeVersionResponse> getVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID versionId
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        validateActor(actorId);
        KnowledgeVersion version = queryUseCase.getVersion(versionId)
                .orElseThrow(() -> new ContentNotFoundException("Version not found: " + versionId));
        return ResponseEntity.ok(KnowledgeVersionResponse.fromDomain(version));
    }

    private void validateActor(UUID actorId) {
        if (actorId == null) {
            throw new AccessDeniedException("Actor ID is required");
        }
        AccountStatus status = accountStatusQuery.getAccountStatus(actorId)
                .orElseThrow(() -> new AccountUnavailableException("Actor account does not exist"));
        if (status != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Actor account is not active");
        }
        if (!permissionQuery.hasActivePermission(actorId, "KNOWLEDGE_IMPORT") &&
                !permissionQuery.hasActivePermission(actorId, "KNOWLEDGE_REVIEW") &&
                !userRoleQuery.hasActiveRole(actorId, "ADMIN")) {
            throw new AccessDeniedException("User lacks required content access permissions");
        }
    }
}
