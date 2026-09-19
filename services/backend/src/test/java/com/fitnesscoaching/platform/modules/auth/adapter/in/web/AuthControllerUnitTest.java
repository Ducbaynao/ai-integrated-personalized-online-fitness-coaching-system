package com.fitnesscoaching.platform.modules.auth.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.EmailAlreadyRegisteredException;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.exception.InvalidOrExpiredTokenException;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.ConfirmEmailRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RegisterRequest;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserResult;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserUseCase;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, ClockConfig.class, GlobalExceptionHandler.class, RequestIdFilter.class, com.fitnesscoaching.platform.common.config.JacksonConfig.class})
class AuthControllerUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RegisterUserUseCase registerUserUseCase;

    @MockitoBean
    private ConfirmEmailUseCase confirmEmailUseCase;

    @Test
    @DisplayName("POST /api/v1/auth/registrations returns 201 with Location header and user summary")
    void registerReturns201() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-19T12:00:00Z");

        RegisterUserResult result = new RegisterUserResult(
                userId,
                "athlete@example.com",
                "Alex Runner",
                AccountStatus.PENDING_VERIFICATION,
                "vi-VN",
                "Asia/Ho_Chi_Minh",
                null,
                now
        );

        when(registerUserUseCase.register(any(RegisterUserCommand.class))).thenReturn(result);

        RegisterRequest request = new RegisterRequest(
                "athlete@example.com",
                "StrongPassword123!",
                "Alex Runner",
                "vi-VN",
                "Asia/Ho_Chi_Minh"
        );

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/users/" + userId))
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.verificationRequired", is(true)))
                .andExpect(jsonPath("$.user.id", is(userId.toString())))
                .andExpect(jsonPath("$.user.email", is("athlete@example.com")))
                .andExpect(jsonPath("$.user.displayName", is("Alex Runner")))
                .andExpect(jsonPath("$.user.status", is("PENDING_VERIFICATION")))
                .andExpect(jsonPath("$.user.preferredLocale", is("vi-VN")))
                .andExpect(jsonPath("$.user.timezone", is("Asia/Ho_Chi_Minh")))
                .andExpect(jsonPath("$.user.emailVerifiedAt").doesNotExist())
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user.token").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/auth/registrations rejects unknown JSON properties per additionalProperties: false")
    void registerRejectsUnknownProperties() throws Exception {
        String jsonWithUnknownProperty = """
            {
                "email": "athlete@example.com",
                "password": "StrongPassword123!",
                "displayName": "Alex Runner",
                "maliciousRole": "ADMINISTRATOR"
            }
        """;

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithUnknownProperty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", containsString("Malformed request body")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/registrations preserves valid X-Request-ID")
    void registerPreservesValidRequestId() throws Exception {
        UUID userId = UUID.randomUUID();
        when(registerUserUseCase.register(any(RegisterUserCommand.class))).thenReturn(new RegisterUserResult(
                userId, "req@example.com", "Req User", AccountStatus.PENDING_VERIFICATION, "vi-VN", "Asia/Ho_Chi_Minh", null, Instant.now()
        ));

        RegisterRequest request = new RegisterRequest("req@example.com", "StrongPassword123!", "Req User", null, null);
        String customRequestId = "trace-client-12345-abcde";

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("X-Request-ID", customRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-ID", customRequestId));
    }

    @Test
    @DisplayName("POST /api/v1/auth/registrations replaces malformed or oversized X-Request-ID with safe UUID")
    void registerSanitizesMalformedRequestId() throws Exception {
        UUID userId = UUID.randomUUID();
        when(registerUserUseCase.register(any(RegisterUserCommand.class))).thenReturn(new RegisterUserResult(
                userId, "req@example.com", "Req User", AccountStatus.PENDING_VERIFICATION, "vi-VN", "Asia/Ho_Chi_Minh", null, Instant.now()
        ));

        RegisterRequest request = new RegisterRequest("req@example.com", "StrongPassword123!", "Req User", null, null);

        // CRLF injection attempt
        String malformedHeader = "bad-id\r\nInjected-Header: evil";

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("X-Request-ID", malformedHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-ID", not(malformedHeader)))
                .andExpect(header().string("X-Request-ID", matchesPattern("^[0-9a-fA-F-]{36}$")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/registrations returns 400 VALIDATION_FAILED on invalid input")
    void registerValidationFailed() throws Exception {
        RegisterRequest invalidRequest = new RegisterRequest(
                "not-an-email",
                "short", // min length is 8
                "",      // displayName cannot be blank
                null,
                null
        );

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", is("Request validation failed")))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.requestId", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors", hasSize(4)));
    }

    @Test
    @DisplayName("POST /api/v1/auth/registrations returns 409 EMAIL_ALREADY_REGISTERED when email already exists")
    void registerDuplicateEmailReturns409() throws Exception {
        when(registerUserUseCase.register(any(RegisterUserCommand.class)))
                .thenThrow(new EmailAlreadyRegisteredException("Email is already registered."));

        RegisterRequest request = new RegisterRequest(
                "existing@example.com",
                "StrongPassword123!",
                "Existing Athlete",
                null,
                null
        );

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("EMAIL_ALREADY_REGISTERED")))
                .andExpect(jsonPath("$.message", containsString("already registered")))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.requestId", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors", empty()));
    }

    @Test
    @DisplayName("POST /api/v1/auth/email-verifications/confirmations returns 204 on valid token")
    void confirmEmailReturns204() throws Exception {
        doNothing().when(confirmEmailUseCase).confirmEmail(any(ConfirmEmailCommand.class));

        ConfirmEmailRequest request = new ConfirmEmailRequest("valid-token-value-with-at-least-20-chars");

        mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("X-Request-ID"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/email-verifications/confirmations returns 400 VALIDATION_FAILED when token is too short")
    void confirmEmailValidationFailed() throws Exception {
        ConfirmEmailRequest request = new ConfirmEmailRequest("short-token"); // < 20 characters

        mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("token")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/email-verifications/confirmations returns 400 INVALID_OR_EXPIRED_TOKEN when token is rejected by domain")
    void confirmEmailInvalidOrExpiredTokenReturns400() throws Exception {
        doThrow(new InvalidOrExpiredTokenException("The verification token is invalid, expired, or has already been used."))
                .when(confirmEmailUseCase).confirmEmail(any(ConfirmEmailCommand.class));

        ConfirmEmailRequest request = new ConfirmEmailRequest("unknown-or-expired-token-value-12345");

        mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_OR_EXPIRED_TOKEN")))
                .andExpect(jsonPath("$.message", containsString("invalid, expired, or has already been used")))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.requestId", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors", empty()));
    }
}
