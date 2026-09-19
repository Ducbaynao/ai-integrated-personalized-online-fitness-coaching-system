package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record UserSummaryDto(
        @JsonProperty("id")
        UUID id,

        @JsonProperty("email")
        String email,

        @JsonProperty("displayName")
        String displayName,

        @JsonProperty("status")
        AccountStatus status,

        @JsonProperty("preferredLocale")
        String preferredLocale,

        @JsonProperty("timezone")
        String timezone,

        @JsonProperty("emailVerifiedAt")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant emailVerifiedAt,

        @JsonProperty("createdAt")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant createdAt
) {
}
