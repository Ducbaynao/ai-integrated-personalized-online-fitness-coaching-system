package com.fitnesscoaching.platform.modules.administration.adapter.in.web;

import com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto.AdminDashboardSummaryResponse;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminDashboardSummary;
import com.fitnesscoaching.platform.modules.administration.application.port.in.AdminDashboardUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardController {

    private final AdminDashboardUseCase dashboardUseCase;

    public AdminDashboardController(AdminDashboardUseCase dashboardUseCase) {
        this.dashboardUseCase = dashboardUseCase;
    }

    @GetMapping("/summary")
    public ResponseEntity<AdminDashboardSummaryResponse> getSummary(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        AdminDashboardSummary summary = dashboardUseCase.getDashboardSummary(adminUserId);
        return ResponseEntity.ok(AdminDashboardSummaryResponse.fromDomain(summary));
    }
}
