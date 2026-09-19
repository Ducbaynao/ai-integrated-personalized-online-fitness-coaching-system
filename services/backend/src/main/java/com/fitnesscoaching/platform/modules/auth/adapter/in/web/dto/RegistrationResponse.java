package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RegistrationResponse(
        @JsonProperty("user")
        UserSummaryDto user,

        @JsonProperty("verificationRequired")
        boolean verificationRequired
) {
    public static RegistrationResponse of(UserSummaryDto user) {
        return new RegistrationResponse(user, true);
    }
}
