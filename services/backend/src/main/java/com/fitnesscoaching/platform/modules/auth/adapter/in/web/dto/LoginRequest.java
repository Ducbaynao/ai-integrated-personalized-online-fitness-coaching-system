package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record LoginRequest(
        @NotBlank @Email @Size(max = 320) @JsonProperty("email") String email,
        @NotBlank @Size(max = 128) @JsonProperty("password") String password,
        @Size(max = 200) @JsonProperty("deviceName") String deviceName
) {
    public LoginRequest {
        if (email != null) email = email.trim();
        if (deviceName != null) deviceName = deviceName.trim();
    }

    @Override
    public String toString() {
        return "LoginRequest[" +
                "email=" + email +
                ", password=" + (password == null ? "null" : "[REDACTED]") +
                ", deviceName=" + deviceName +
                ']';
    }
}
