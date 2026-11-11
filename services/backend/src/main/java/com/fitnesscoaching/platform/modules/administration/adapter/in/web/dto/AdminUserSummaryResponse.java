package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserSummary;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserSummaryResponse(
        UUID id,
        String email,
        String displayName,
        String phoneNumber,
        AccountStatus status,
        List<String> roles,
        boolean hasStudentProfile,
        boolean hasTrainerProfile,
        String trainerVerificationStatus,
        Instant createdAt,
        Instant lastLoginAt
) {
    public static AdminUserSummaryResponse fromDomain(AdminUserSummary summary) {
        return new AdminUserSummaryResponse(
                summary.id(),
                summary.email(),
                summary.displayName(),
                summary.phoneNumber(),
                summary.status(),
                summary.roles(),
                summary.hasStudentProfile(),
                summary.hasTrainerProfile(),
                summary.trainerVerificationStatus(),
                summary.createdAt(),
                summary.lastLoginAt()
        );
    }
}
