package com.fitnesscoaching.platform.modules.support.adapter.in.web;

import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.AddSupportCaseNoteRequest;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.AssignSupportCaseRequest;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.CreateSupportCaseRequest;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.SupportCaseDetailResponse;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.SupportCasePageResponse;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.SupportCaseSummaryResponse;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.UpdateSupportCaseStatusRequest;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseDetail;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCasePage;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseSummary;
import com.fitnesscoaching.platform.modules.support.application.port.in.SupportCaseUseCase;
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
@RequestMapping("/api/v1/admin/support-cases")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSupportCaseController {

    private final SupportCaseUseCase supportCaseUseCase;

    public AdminSupportCaseController(SupportCaseUseCase supportCaseUseCase) {
        this.supportCaseUseCase = supportCaseUseCase;
    }

    @GetMapping
    public ResponseEntity<SupportCasePageResponse> searchSupportCases(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        SupportCasePage result = supportCaseUseCase.searchSupportCases(adminUserId, search, status, priority, page, size);
        return ResponseEntity.ok(SupportCasePageResponse.fromDomain(result));
    }

    @PostMapping
    public ResponseEntity<SupportCaseSummaryResponse> createSupportCase(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateSupportCaseRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        SupportCaseSummary summary = supportCaseUseCase.createSupportCase(adminUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(SupportCaseSummaryResponse.fromDomain(summary));
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<SupportCaseDetailResponse> getSupportCaseDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID caseId
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        SupportCaseDetail detail = supportCaseUseCase.getSupportCaseDetail(adminUserId, caseId);
        return ResponseEntity.ok(SupportCaseDetailResponse.fromDomain(detail));
    }

    @PostMapping("/{caseId}/status")
    public ResponseEntity<SupportCaseDetailResponse> updateStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID caseId,
            @Valid @RequestBody UpdateSupportCaseStatusRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        SupportCaseDetail detail = supportCaseUseCase.updateStatus(adminUserId, caseId, request);
        return ResponseEntity.ok(SupportCaseDetailResponse.fromDomain(detail));
    }

    @PostMapping("/{caseId}/assign")
    public ResponseEntity<SupportCaseDetailResponse> assignCase(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID caseId,
            @Valid @RequestBody AssignSupportCaseRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        SupportCaseDetail detail = supportCaseUseCase.assignCase(adminUserId, caseId, request);
        return ResponseEntity.ok(SupportCaseDetailResponse.fromDomain(detail));
    }

    @PostMapping("/{caseId}/notes")
    public ResponseEntity<SupportCaseDetailResponse> addNote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID caseId,
            @Valid @RequestBody AddSupportCaseNoteRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        SupportCaseDetail detail = supportCaseUseCase.addNote(adminUserId, caseId, request);
        return ResponseEntity.ok(SupportCaseDetailResponse.fromDomain(detail));
    }
}
