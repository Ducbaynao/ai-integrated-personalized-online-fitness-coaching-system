package com.fitnesscoaching.platform.modules.administration.adapter.in.web;

import com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto.StepUpRequest;
import com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto.StepUpResponse;
import com.fitnesscoaching.platform.modules.administration.application.model.StepUpResult;
import com.fitnesscoaching.platform.modules.administration.application.port.in.AdminStepUpUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/auth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuthController {

    private final AdminStepUpUseCase stepUpUseCase;

    public AdminAuthController(AdminStepUpUseCase stepUpUseCase) {
        this.stepUpUseCase = stepUpUseCase;
    }

    @PostMapping("/step-up")
    public ResponseEntity<StepUpResponse> issueStepUp(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody StepUpRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        StepUpResult result = stepUpUseCase.verifyAndIssueStepUp(
                adminUserId,
                request.actionCode(),
                request.password()
        );
        return ResponseEntity.ok(new StepUpResponse(result.stepUpAuthenticationId(), result.expiresAt()));
    }
}
