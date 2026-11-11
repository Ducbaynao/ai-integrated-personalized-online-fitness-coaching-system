package com.fitnesscoaching.platform.modules.moderation.adapter.in.web;

import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.CreateModerationCaseRequest;
import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.ModerationActionResponse;
import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.ModerationCaseDetailResponse;
import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.ModerationCasePageResponse;
import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.ModerationCaseSummaryResponse;
import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.PerformModerationActionRequest;
import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.UpdateModerationStatusRequest;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationActionModel;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCaseDetail;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCasePage;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCaseSummary;
import com.fitnesscoaching.platform.modules.moderation.application.port.in.ModerationUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/moderation-cases")
@PreAuthorize("hasRole('ADMIN')")
public class AdminModerationController {

    private final ModerationUseCase moderationUseCase;

    public AdminModerationController(ModerationUseCase moderationUseCase) {
        this.moderationUseCase = moderationUseCase;
    }

    @GetMapping
    public ResponseEntity<ModerationCasePageResponse> searchModerationCases(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        ModerationCasePage result = moderationUseCase.searchModerationCases(adminUserId, status, priority, page, size);
        return ResponseEntity.ok(ModerationCasePageResponse.fromDomain(result));
    }

    @PostMapping
    public ResponseEntity<ModerationCaseSummaryResponse> createModerationCase(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateModerationCaseRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        ModerationCaseSummary summary = moderationUseCase.createModerationCase(adminUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ModerationCaseSummaryResponse.fromDomain(summary));
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<ModerationCaseDetailResponse> getModerationCaseDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID caseId
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        ModerationCaseDetail detail = moderationUseCase.getModerationCaseDetail(adminUserId, caseId);
        return ResponseEntity.ok(ModerationCaseDetailResponse.fromDomain(detail));
    }

    @PostMapping("/{caseId}/status")
    public ResponseEntity<ModerationCaseDetailResponse> updateStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID caseId,
            @Valid @RequestBody UpdateModerationStatusRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        ModerationCaseDetail detail = moderationUseCase.updateStatus(adminUserId, caseId, request);
        return ResponseEntity.ok(ModerationCaseDetailResponse.fromDomain(detail));
    }

    @PostMapping("/{caseId}/actions")
    public ResponseEntity<ModerationActionResponse> performAction(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID caseId,
            @Valid @RequestBody PerformModerationActionRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        ModerationActionModel action = moderationUseCase.performAction(adminUserId, caseId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ModerationActionResponse.fromDomain(action));
    }
}
