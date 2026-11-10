package com.fitnesscoaching.platform.modules.administration.application.model;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserDetail(
        UUID id,
        String email,
        String displayName,
        String phoneNumber,
        AccountStatus status,
        String preferredLocale,
        String timezone,
        Instant emailVerifiedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        List<String> roles,
        List<String> permissions,
        StudentProfileInfo studentProfile,
        TrainerProfileInfo trainerProfile,
        List<AccountStatusHistoryEntry> statusHistory
) {
}
