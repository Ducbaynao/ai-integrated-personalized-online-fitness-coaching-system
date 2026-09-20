package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record LogoutRequest(
        @NotBlank @Size(min = 20, max = 2048) @JsonProperty("refreshToken") String refreshToken
) {
    public LogoutRequest {
        if (refreshToken != null) {
            refreshToken = refreshToken.trim();
        }
    }

    @Override
    public String toString() {
        return "LogoutRequest[" +
                "refreshToken=" + (refreshToken == null ? "null" : "[REDACTED]") +
                ']';
    }
}
