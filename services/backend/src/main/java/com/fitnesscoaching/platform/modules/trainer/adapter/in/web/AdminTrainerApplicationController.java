package com.fitnesscoaching.platform.modules.trainer.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.AdminTrainerApplicationPageResponse;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.AdminTrainerApplicationResponse;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.DecideTrainerApplicationRequest;
import com.fitnesscoaching.platform.modules.trainer.application.model.AdminTrainerApplicationPage;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.DecideTrainerApplicationCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.DecideTrainerApplicationUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationDetailUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationsQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationsUseCase;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/trainer-applications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTrainerApplicationController {

    private final GetAdminTrainerApplicationsUseCase getApplicationsUseCase;
    private final GetAdminTrainerApplicationDetailUseCase getApplicationDetailUseCase;
    private final DecideTrainerApplicationUseCase decideApplicationUseCase;

    public AdminTrainerApplicationController(
            GetAdminTrainerApplicationsUseCase getApplicationsUseCase,
            GetAdminTrainerApplicationDetailUseCase getApplicationDetailUseCase,
            DecideTrainerApplicationUseCase decideApplicationUseCase
    ) {
        this.getApplicationsUseCase = getApplicationsUseCase;
        this.getApplicationDetailUseCase = getApplicationDetailUseCase;
        this.decideApplicationUseCase = decideApplicationUseCase;
    }

    @GetMapping
    public ResponseEntity<AdminTrainerApplicationPageResponse> getApplications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());

        TrainerVerificationStatus verificationStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                verificationStatus = TrainerVerificationStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ApplicationValidationException("Invalid status parameter: " + status,
                        List.of(new FieldErrorDto("status", "Invalid", "Status must be a valid verification status")));
            }
        }

        GetAdminTrainerApplicationsQuery query = new GetAdminTrainerApplicationsQuery(
                adminUserId,
                verificationStatus,
                page,
                size
        );
        AdminTrainerApplicationPage resultPage = getApplicationsUseCase.getApplications(query);
        return ResponseEntity.ok(AdminTrainerApplicationPageResponse.fromDomain(resultPage));
    }

    @GetMapping("/{applicationId}")
    public ResponseEntity<AdminTrainerApplicationResponse> getApplicationDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        TrainerApplication app = getApplicationDetailUseCase.getApplicationDetail(adminUserId, applicationId);
        return ResponseEntity.ok(AdminTrainerApplicationResponse.fromDomain(app));
    }

    @PostMapping("/{applicationId}/decisions")
    public ResponseEntity<AdminTrainerApplicationResponse> decideApplication(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId,
            @Valid @RequestBody DecideTrainerApplicationRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        DecideTrainerApplicationCommand command = request.toCommand(adminUserId, applicationId);
        TrainerApplication app = decideApplicationUseCase.decideApplication(command);
        return ResponseEntity.ok(AdminTrainerApplicationResponse.fromDomain(app));
    }
}
