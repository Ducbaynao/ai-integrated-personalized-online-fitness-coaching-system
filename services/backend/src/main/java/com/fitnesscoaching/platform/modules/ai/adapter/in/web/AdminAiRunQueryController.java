package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AdminAiRunQueryUseCase;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ai/runs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAiRunQueryController {

    private final AdminAiRunQueryUseCase queryUseCase;

    public AdminAiRunQueryController(AdminAiRunQueryUseCase queryUseCase) {
        this.queryUseCase = queryUseCase;
    }

    @GetMapping
    public ResponseEntity<AdminAiRunQueryUseCase.AdminAiRunPageDto> listRuns(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) AiRunStatus status,
            @RequestParam(required = false) String requestType
    ) {
        UUID actorId = extractActorId(jwt);
        AdminAiRunQueryUseCase.AdminAiRunPageDto response = queryUseCase.listRuns(actorId, page, size, status, requestType);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<AdminAiRunQueryUseCase.AdminAiRunDetailDto> getRunDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID runId
    ) {
        UUID actorId = extractActorId(jwt);
        AdminAiRunQueryUseCase.AdminAiRunDetailDto response = queryUseCase.getRunDetail(actorId, runId);
        return ResponseEntity.ok(response);
    }

    private UUID extractActorId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new AiRunAccessDeniedException("Authentication required for administrative AI run query");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new AiRunAccessDeniedException("Invalid authenticated user ID in token subject");
        }
    }
}
