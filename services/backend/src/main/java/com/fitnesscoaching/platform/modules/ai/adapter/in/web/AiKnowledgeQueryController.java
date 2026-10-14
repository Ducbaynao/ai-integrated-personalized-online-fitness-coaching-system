package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiKnowledgeQueryUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Spring REST Controller for F01 General Fitness Knowledge queries.
 * Receives public client requests at POST /api/v1/ai/knowledge/query,
 * enforces JWT identity, and maps orchestration outcome to contract HTTP status codes.
 */
@RestController
@RequestMapping("/api/v1/ai/knowledge")
public class AiKnowledgeQueryController {

    private final AiKnowledgeQueryUseCase queryUseCase;

    public AiKnowledgeQueryController(AiKnowledgeQueryUseCase queryUseCase) {
        this.queryUseCase = queryUseCase;
    }

    @PostMapping("/query")
    public ResponseEntity<F01KnowledgeQueryResponse> queryKnowledge(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody F01KnowledgeQueryApiRequest request
    ) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new AiRunAccessDeniedException("Authentication required to query AI knowledge");
        }
        if (request == null) {
            throw new AiRunValidationException("Request body must not be null");
        }

        UUID actorId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new AiRunAccessDeniedException("Invalid authenticated user ID in token subject");
        }

        F01KnowledgeQueryResponse response = queryUseCase.queryKnowledge(actorId, request);
        return mapToResponseEntity(response);
    }

    private ResponseEntity<F01KnowledgeQueryResponse> mapToResponseEntity(F01KnowledgeQueryResponse response) {
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
