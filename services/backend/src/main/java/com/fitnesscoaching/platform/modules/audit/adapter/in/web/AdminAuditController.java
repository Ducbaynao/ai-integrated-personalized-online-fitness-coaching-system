package com.fitnesscoaching.platform.modules.audit.adapter.in.web;

import com.fitnesscoaching.platform.modules.audit.adapter.in.web.dto.AuditLogEntryResponse;
import com.fitnesscoaching.platform.modules.audit.adapter.in.web.dto.AuditLogPageResponse;
import com.fitnesscoaching.platform.modules.audit.application.model.AuditLogEntry;
import com.fitnesscoaching.platform.modules.audit.application.model.AuditLogPage;
import com.fitnesscoaching.platform.modules.audit.application.port.in.AuditQueryUseCase;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditController {

    private final AuditQueryUseCase auditQueryUseCase;

    public AdminAuditController(AuditQueryUseCase auditQueryUseCase) {
        this.auditQueryUseCase = auditQueryUseCase;
    }

    @GetMapping
    public ResponseEntity<AuditLogPageResponse> searchAuditLogs(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        AuditLogPage result = auditQueryUseCase.searchAuditLogs(adminUserId, actorUserId, action, targetType, targetId, from, to, page, size);
        return ResponseEntity.ok(AuditLogPageResponse.fromDomain(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AuditLogEntryResponse> getAuditLogEntry(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        AuditLogEntry entry = auditQueryUseCase.getAuditLogEntry(adminUserId, id);
        return ResponseEntity.ok(AuditLogEntryResponse.fromDomain(entry));
    }
}
