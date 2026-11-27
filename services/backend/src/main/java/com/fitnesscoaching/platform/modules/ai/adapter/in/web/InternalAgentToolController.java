package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties;
import com.fitnesscoaching.platform.modules.ai.application.service.AgentToolGatewayService;
import com.fitnesscoaching.platform.modules.ai.application.service.AgentToolGatewayService.ToolCallInput;
import com.fitnesscoaching.platform.modules.ai.application.service.AgentToolGatewayService.ToolCallOutput;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal REST gateway for FastAPI agent tool calls.
 * Authenticated via X-Internal-Service-Key and subject to Spring Boot object/run authorization.
 */
@RestController
@RequestMapping("/internal/v1/agent/tools")
public class InternalAgentToolController {

    private final AgentToolGatewayService gatewayService;
    private final AiServiceProperties aiProperties;

    public InternalAgentToolController(
            AgentToolGatewayService gatewayService,
            AiServiceProperties aiProperties
    ) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    @ExceptionHandler(AiRunAccessDeniedException.class)
    public ResponseEntity<ToolCallOutput> handleDenied(AiRunAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                new ToolCallOutput("DENIED", ex.getMessage(), Map.of(), List.of(), null)
        );
    }

    private boolean isAuthorizedKey(String key) {
        return key != null && key.equals(aiProperties.internalServiceKey());
    }

    @PostMapping("/current-plan")
    public ResponseEntity<ToolCallOutput> currentPlan(
            @RequestHeader(value = "X-Internal-Service-Key", required = false) String internalKey,
            @RequestBody ToolCallInput input
    ) {
        if (!isAuthorizedKey(internalKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(gatewayService.getCurrentPlan(input));
    }

    @PostMapping("/knowledge-access")
    public ResponseEntity<ToolCallOutput> knowledgeAccess(
            @RequestHeader(value = "X-Internal-Service-Key", required = false) String internalKey,
            @RequestBody ToolCallInput input
    ) {
        if (!isAuthorizedKey(internalKey)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(gatewayService.authorizeKnowledgeSearch(input));
    }

    @PostMapping("/progress-summary")
    public ResponseEntity<ToolCallOutput> progressSummary(
            @RequestHeader(value = "X-Internal-Service-Key", required = false) String internalKey,
            @RequestBody ToolCallInput input
    ) {
        if (!isAuthorizedKey(internalKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(gatewayService.getProgressSummary(input));
    }

    @PostMapping("/availability")
    public ResponseEntity<ToolCallOutput> availability(
            @RequestHeader(value = "X-Internal-Service-Key", required = false) String internalKey,
            @RequestBody ToolCallInput input
    ) {
        if (!isAuthorizedKey(internalKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(gatewayService.getAvailability(input));
    }

    @PostMapping("/prepare-workout-adjustment")
    public ResponseEntity<ToolCallOutput> prepareWorkoutAdjustment(
            @RequestHeader(value = "X-Internal-Service-Key", required = false) String internalKey,
            @RequestBody ToolCallInput input
    ) {
        if (!isAuthorizedKey(internalKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(gatewayService.prepareWorkoutAdjustment(input));
    }

    @PostMapping("/prepare-reschedule")
    public ResponseEntity<ToolCallOutput> prepareReschedule(
            @RequestHeader(value = "X-Internal-Service-Key", required = false) String internalKey,
            @RequestBody ToolCallInput input
    ) {
        if (!isAuthorizedKey(internalKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(gatewayService.prepareReschedule(input));
    }
}
