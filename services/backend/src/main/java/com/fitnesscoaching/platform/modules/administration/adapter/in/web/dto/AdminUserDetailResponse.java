package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserDetail;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserDetailResponse(
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
        StudentProfileDto studentProfile,
        TrainerProfileDto trainerProfile,
        List<AccountStatusHistoryDto> statusHistory
) {
    public static AdminUserDetailResponse fromDomain(AdminUserDetail detail) {
        return new AdminUserDetailResponse(
                detail.id(),
                detail.email(),
                detail.displayName(),
                detail.phoneNumber(),
                detail.status(),
                detail.preferredLocale(),
                detail.timezone(),
                detail.emailVerifiedAt(),
                detail.createdAt(),
                detail.updatedAt(),
                detail.lastLoginAt(),
                detail.roles(),
                detail.permissions(),
                StudentProfileDto.fromDomain(detail.studentProfile()),
                TrainerProfileDto.fromDomain(detail.trainerProfile()),
                detail.statusHistory().stream().map(AccountStatusHistoryDto::fromDomain).toList()
        );
    }
}
