package com.fitnesscoaching.platform.modules.administration.application.model;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserSummary(
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
}
