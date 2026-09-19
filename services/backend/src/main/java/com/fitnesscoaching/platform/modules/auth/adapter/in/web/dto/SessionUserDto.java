package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SessionUserDto(
        UUID id,
        String email,
        String displayName,
        AccountStatus status,
        String preferredLocale,
        String timezone,
        Instant emailVerifiedAt,
        Instant createdAt,
        String phoneNumber,
        List<String> roles,
        UserCapabilitiesDto capabilities,
        UserSettingsDto settings
) {
}
