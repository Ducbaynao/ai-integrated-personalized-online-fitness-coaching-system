package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.ToggleAiFeatureApiRequest;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiFeatureControlUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ai/features")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAiFeatureController {

    private final AiFeatureControlUseCase featureControlUseCase;

    public AdminAiFeatureController(AiFeatureControlUseCase featureControlUseCase) {
        this.featureControlUseCase = featureControlUseCase;
    }

    @GetMapping("/f01")
    public ResponseEntity<AiFeatureControlUseCase.AiFeatureStatusDto> getF01Status() {
        return ResponseEntity.ok(featureControlUseCase.getF01Status());
    }

    @PostMapping("/f01/toggle")
    public ResponseEntity<AiFeatureControlUseCase.AiFeatureStatusDto> toggleF01(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ToggleAiFeatureApiRequest request
    ) {
        UUID actorId = extractActorId(jwt);
        AiFeatureControlUseCase.AiFeatureStatusDto updated = featureControlUseCase.setF01Enabled(
                actorId,
                request.enabled(),
                request.reason()
        );
        return ResponseEntity.ok(updated);
    }

    private UUID extractActorId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new AiRunAccessDeniedException("Authentication required for administrative feature control");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new AiRunAccessDeniedException("Invalid authenticated user ID in token subject");
        }
    }
}
