package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ConfirmEmailRequest(
        @NotBlank(message = "Verification token is required")
        @Size(min = 20, max = 512, message = "Token length must be between 20 and 512 characters")
        @JsonProperty("token")
        String token
) {
    public ConfirmEmailRequest {
        if (token != null) {
            token = token.trim();
        }
    }

    @Override
    public String toString() {
        return "ConfirmEmailRequest[" +
                "token=" + (token == null ? "null" : "[REDACTED]") +
                ']';
    }
}
