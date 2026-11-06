package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiProgressSummarizationUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST Controller for F04 AI Progress Summarization and readback.
 * Maps endpoints to Spring Security JWT principal and handles status mapping.
 */
@RestController
@RequestMapping({"/api/v1/ai/progress", "/ai/progress"})
public class AiProgressSummaryController {

    private final AiProgressSummarizationUseCase summarizationUseCase;

    public AiProgressSummaryController(AiProgressSummarizationUseCase summarizationUseCase) {
        this.summarizationUseCase = summarizationUseCase;
    }

    @PostMapping("/summary")
    public ResponseEntity<F04ProgressSummaryResponse> summarizeProgress(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody F04ProgressSummaryApiRequest request
    ) {
        UUID actorId = extractActorId(jwt);
        if (request == null) {
            throw new AiRunValidationException("Request body must not be null");
        }

        F04ProgressSummaryResponse response = summarizationUseCase.summarizeProgress(actorId, request);
        return mapToResponseEntity(response);
    }

    @GetMapping("/summary/{runId}")
    public ResponseEntity<F04ProgressSummaryResponse> getProgressSummary(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID runId
    ) {
        UUID actorId = extractActorId(jwt);
        F04ProgressSummaryResponse response = summarizationUseCase.getProgressSummary(actorId, runId);
        return mapToResponseEntity(response);
    }

    private UUID extractActorId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new AiRunAccessDeniedException("Authentication required to access progress summary");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new AiRunAccessDeniedException("Invalid authenticated user ID in token subject");
        }
    }

    private ResponseEntity<F04ProgressSummaryResponse> mapToResponseEntity(F04ProgressSummaryResponse response) {
        String runStatus = response.runStatus();
        String resultType = response.resultType();
        String reasonCode = response.reasonCode();

        if ("SUCCEEDED".equalsIgnoreCase(runStatus)) {
            return ResponseEntity.ok(response);
        }

        if ("REJECTED_BY_VALIDATOR".equalsIgnoreCase(runStatus)) {
            if ("BLOCKED".equalsIgnoreCase(resultType)) {
                return ResponseEntity.ok(response);
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
        }

        if ("FAILED".equalsIgnoreCase(runStatus)) {
            if ("TIMEOUT".equalsIgnoreCase(reasonCode)) {
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(response);
            }
            if ("MODEL_ERROR".equalsIgnoreCase(reasonCode) || "PROVIDER_ERROR".equalsIgnoreCase(reasonCode)) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
