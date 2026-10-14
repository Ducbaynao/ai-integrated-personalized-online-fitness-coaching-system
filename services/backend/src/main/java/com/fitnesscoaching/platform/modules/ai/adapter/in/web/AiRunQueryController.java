package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AiRunResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiRunF01UseCase;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/runs")
public class AiRunQueryController {

    private final AiRunF01UseCase aiRunUseCase;

    public AiRunQueryController(AiRunF01UseCase aiRunUseCase) {
        this.aiRunUseCase = aiRunUseCase;
    }

    @GetMapping("/{runId}")
    public ResponseEntity<AiRunResponse> getRun(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID runId
    ) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new AiRunAccessDeniedException("Authentication required to query AI Run");
        }

        UUID actorId = UUID.fromString(jwt.getSubject());
        AiRun run = aiRunUseCase.getF01Run(actorId, runId);
        return ResponseEntity.ok(AiRunResponse.fromDomain(run));
    }
}
