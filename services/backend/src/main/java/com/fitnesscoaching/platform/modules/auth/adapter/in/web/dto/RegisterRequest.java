package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a well-formed email address")
        @Size(max = 320, message = "Email cannot exceed 320 characters")
        @JsonProperty("email")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
        @JsonProperty("password")
        String password,

        @NotBlank(message = "Display name is required")
        @Size(min = 1, max = 160, message = "Display name must be between 1 and 160 characters")
        @JsonProperty("displayName")
        String displayName,

        @Size(max = 16, message = "Preferred locale cannot exceed 16 characters")
        @JsonProperty("preferredLocale")
        String preferredLocale,

        @Size(max = 64, message = "Timezone cannot exceed 64 characters")
        @JsonProperty("timezone")
        String timezone
) {
    public RegisterRequest {
        if (email != null) {
            email = email.trim();
        }
    }

    @Override
    public String toString() {
        return "RegisterRequest[" +
                "email=" + email +
                ", password=" + (password == null ? "null" : "[REDACTED]") +
                ", displayName=" + displayName +
                ", preferredLocale=" + preferredLocale +
                ", timezone=" + timezone +
                ']';
    }
}
