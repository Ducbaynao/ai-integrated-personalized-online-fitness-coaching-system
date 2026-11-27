package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.application.service.AiAgentConversationService;
import com.fitnesscoaching.platform.modules.ai.application.service.AiAgentConversationService.ConversationDetail;
import com.fitnesscoaching.platform.modules.ai.application.service.AiAgentConversationService.ConversationSummary;
import com.fitnesscoaching.platform.modules.ai.application.service.AiAgentConversationService.TurnView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Public REST API for scoped AI Agent conversations (AG-M4).
 * Enforces JWT authentication, capability/coaching authority, and strict conversation ownership.
 */
@RestController
@RequestMapping("/api/v1/ai/conversations")
public class AiAgentConversationController {

    private final AiAgentConversationService conversationService;

    public AiAgentConversationController(AiAgentConversationService conversationService) {
        this.conversationService = conversationService;
    }

    public record CreateConversationRequest(String actorRole, UUID subjectId, String title) {}

    public record SendTurnRequest(String message, UUID turnId, String operationKey) {}

    private UUID extractActorId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new AiRunAccessDeniedException("Authentication required.");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new AiRunAccessDeniedException("Invalid authenticated user ID in token subject.");
        }
    }

    @PostMapping
    public ResponseEntity<ConversationSummary> createConversation(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) CreateConversationRequest request
    ) {
        UUID actorId = extractActorId(jwt);
        String role = request != null ? request.actorRole() : "STUDENT";
        UUID subjectId = request != null ? request.subjectId() : null;
        String title = request != null ? request.title() : null;
        return ResponseEntity.ok(conversationService.createConversation(actorId, role, subjectId, title));
    }

    @GetMapping
    public ResponseEntity<List<ConversationSummary>> listConversations(@AuthenticationPrincipal Jwt jwt) {
        UUID actorId = extractActorId(jwt);
        return ResponseEntity.ok(conversationService.listConversations(actorId));
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationDetail> getConversation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID conversationId
    ) {
        UUID actorId = extractActorId(jwt);
        return ResponseEntity.ok(conversationService.getConversation(actorId, conversationId));
    }

    @PostMapping("/{conversationId}/turns")
    public ResponseEntity<TurnView> sendTurn(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID conversationId,
            @RequestBody SendTurnRequest request
    ) {
        UUID actorId = extractActorId(jwt);
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new AiRunValidationException("message must not be blank.");
        }
        return ResponseEntity.ok(conversationService.sendTurn(
                actorId,
                conversationId,
                request.message(),
                request.turnId(),
                request.operationKey()
        ));
    }

    @PostMapping("/{conversationId}/turns/{turnId}/cancel")
    public ResponseEntity<TurnView> cancelTurn(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID conversationId,
            @PathVariable UUID turnId
    ) {
        UUID actorId = extractActorId(jwt);
        return ResponseEntity.ok(conversationService.cancelTurn(actorId, conversationId, turnId));
    }
}
