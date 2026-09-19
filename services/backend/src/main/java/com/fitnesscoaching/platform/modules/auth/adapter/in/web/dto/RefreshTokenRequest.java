package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RefreshTokenRequest(
        @NotBlank @Size(min = 20, max = 2048) @JsonProperty("refreshToken") String refreshToken,
        @Size(max = 200) @JsonProperty("deviceName") String deviceName
) {
    public RefreshTokenRequest {
        if (refreshToken != null) refreshToken = refreshToken.trim();
        if (deviceName != null) deviceName = deviceName.trim();
    }
}
