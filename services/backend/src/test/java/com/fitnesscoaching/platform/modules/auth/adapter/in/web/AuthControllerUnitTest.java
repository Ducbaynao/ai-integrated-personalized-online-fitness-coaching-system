package com.fitnesscoaching.platform.modules.auth.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.EmailAlreadyRegisteredException;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.exception.InvalidCredentialsException;
import com.fitnesscoaching.platform.common.exception.InvalidOrExpiredTokenException;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.ConfirmEmailRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.LoginRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.LogoutRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RefreshTokenRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RegisterRequest;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserResult;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.LoginUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.LogoutSessionCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.LogoutSessionUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RefreshSessionUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.TokenPairResult;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.auth.domain.UserAccountRecord;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.user.application.model.UserCapabilitiesView;
import com.fitnesscoaching.platform.modules.user.application.model.UserSettingsView;

import java.util.List;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({
        SecurityConfig.class,
        com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint.class,
        com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler.class,
        ClockConfig.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        com.fitnesscoaching.platform.common.config.JacksonConfig.class
})
class AuthControllerUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RegisterUserUseCase registerUserUseCase;

    @MockitoBean
    private ConfirmEmailUseCase confirmEmailUseCase;

    @MockitoBean
    private LoginUseCase loginUseCase;

    @MockitoBean
    private RefreshSessionUseCase refreshSessionUseCase;

    @MockitoBean
    private LogoutSessionUseCase logoutSessionUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

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

    @Test
    @DisplayName("POST /api/v1/auth/sessions returns the token pair and safe current-user projection")
    void loginReturnsTokenPair() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-19T10:00:00Z");
        Instant verifiedAt = Instant.parse("2026-09-19T11:00:00Z");
        CurrentUserView user = new CurrentUserView(
                userId, "active@example.com", "Active User", AccountStatus.ACTIVE,
                "vi-VN", "Asia/Ho_Chi_Minh", verifiedAt, createdAt, null, List.of("STUDENT"),
                new UserCapabilitiesView(true, false, false),
                UserSettingsView.defaults()
        );
        when(loginUseCase.login(any())).thenReturn(new TokenPairResult(
                "access.jwt.value", Instant.parse("2026-09-19T12:15:00Z"),
                "raw-refresh-token-with-more-than-20-chars", Instant.parse("2026-10-19T12:00:00Z"), user));

        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"active@example.com","password":"StrongPassword123!","deviceName":"Pixel 9"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.accessToken", is("access.jwt.value")))
                .andExpect(jsonPath("$.refreshToken", is("raw-refresh-token-with-more-than-20-chars")))
                .andExpect(jsonPath("$.user.id", is(userId.toString())))
                .andExpect(jsonPath("$.user.roles[0]", is("STUDENT")))
                .andExpect(jsonPath("$.user.capabilities.hasStudentProfile", is(true)))
                .andExpect(jsonPath("$.user.capabilities.canCoach", is(false)))
                .andExpect(jsonPath("$.user.settings.measurementSystem", is("METRIC")))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/auth/sessions returns stable 401 for invalid credentials")
    void loginInvalidCredentialsReturns401() throws Exception {
        when(loginUseCase.login(any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"unknown@example.com","password":"WrongPassword123!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("INVALID_CREDENTIALS")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/token-refreshes rejects unknown JSON properties")
    void refreshRejectsUnknownProperties() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"refresh-token-with-more-than-20-chars","role":"ADMINISTRATOR"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/sessions returns 401 when unauthenticated")
    void logoutUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"valid-token-with-more-than-20-chars"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/sessions returns 204 when authenticated with valid token")
    void logoutAuthenticatedReturns204() throws Exception {
        UUID userId = UUID.randomUUID();
        String refreshToken = "valid-token-with-more-than-20-chars";

        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(refreshToken))))
                .andExpect(status().isNoContent());

        verify(logoutSessionUseCase).logout(new LogoutSessionCommand(userId, refreshToken));
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/sessions returns 400 when refreshToken is blank")
    void logoutBlankRefreshTokenReturns400() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/sessions returns 400 when refreshToken is too short")
    void logoutTooShortRefreshTokenReturns400() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"too-short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/sessions rejects unknown JSON properties")
    void logoutUnknownPropertiesReturns400() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"valid-token-with-more-than-20-chars","unknown":"property"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Spring MVC logging does not leak secret sentinels even when DEBUG logging is active")
    void springMvcLoggingDoesNotLeakSecretsAtDebug() throws Exception {
        Logger webLogger = (Logger) LoggerFactory.getLogger("org.springframework.web");
        Level originalLevel = webLogger.getLevel();
        webLogger.setLevel(Level.DEBUG);

        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        webLogger.addAppender(listAppender);

        String passwordSentinel = "password-secret-sentinel-xyz";
        String verificationTokenSentinel = "verification-token-secret-sentinel-xyz";
        String refreshTokenSentinel = "refresh-token-secret-sentinel-xyz";
        String accessTokenSentinel = "access-token-secret-sentinel-xyz";

        try {
            // 1. Register with password sentinel
            UUID userId = UUID.randomUUID();
            when(registerUserUseCase.register(any())).thenReturn(new RegisterUserResult(
                    userId, "athlete@example.com", "Alex", AccountStatus.ACTIVE, "en-US", "UTC", null, Instant.now()));

            mockMvc.perform(post("/api/v1/auth/registrations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RegisterRequest(
                                    "athlete@example.com", passwordSentinel, "Alex", "en-US", "UTC"))))
                    .andExpect(status().isCreated());

            // 2. Confirm email with token sentinel
            doNothing().when(confirmEmailUseCase).confirmEmail(any());
            mockMvc.perform(post("/api/v1/auth/email-verifications/confirmations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ConfirmEmailRequest(verificationTokenSentinel))))
                    .andExpect(status().isNoContent());

            // 3. Login returning token pair with secret sentinels
            when(loginUseCase.login(any())).thenReturn(new TokenPairResult(
                    accessTokenSentinel,
                    Instant.now().plusSeconds(900),
                    refreshTokenSentinel,
                    Instant.now().plusSeconds(86400),
                    new CurrentUserView(
                            userId, "athlete@example.com", "Alex", AccountStatus.ACTIVE,
                            "en-US", "UTC", Instant.now(), Instant.now(), null,
                            List.of("STUDENT"),
                            new UserCapabilitiesView(true, false, false),
                            UserSettingsView.defaults()
                    )
            ));

            mockMvc.perform(post("/api/v1/auth/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(
                                    "athlete@example.com", passwordSentinel, "Pixel 9"))))
                    .andExpect(status().isOk());

            // 4. Refresh token
            when(refreshSessionUseCase.refresh(any())).thenReturn(new TokenPairResult(
                    accessTokenSentinel,
                    Instant.now().plusSeconds(900),
                    refreshTokenSentinel,
                    Instant.now().plusSeconds(86400),
                    new CurrentUserView(
                            userId, "athlete@example.com", "Alex", AccountStatus.ACTIVE,
                            "en-US", "UTC", Instant.now(), Instant.now(), null,
                            List.of("STUDENT"),
                            new UserCapabilitiesView(true, false, false),
                            UserSettingsView.defaults()
                    )
            ));

            mockMvc.perform(post("/api/v1/auth/token-refreshes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RefreshTokenRequest(
                                    refreshTokenSentinel, "Pixel 9"))))
                    .andExpect(status().isOk());

            // 5. Logout
            mockMvc.perform(delete("/api/v1/auth/sessions")
                            .with(jwt().jwt(jwt -> jwt.subject(userId.toString())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LogoutRequest(refreshTokenSentinel))))
                    .andExpect(status().isNoContent());

            // Assert that none of the log messages contain the raw secret sentinels
            for (ILoggingEvent event : listAppender.list) {
                String msg = event.getFormattedMessage();
                assertThat(msg).doesNotContain(passwordSentinel);
                assertThat(msg).doesNotContain(verificationTokenSentinel);
                assertThat(msg).doesNotContain(refreshTokenSentinel);
                assertThat(msg).doesNotContain(accessTokenSentinel);
            }
        } finally {
            webLogger.detachAppender(listAppender);
            webLogger.setLevel(originalLevel);
        }
    }

    @Test
    @DisplayName("Spring MVC validation failure log does not leak invalid password sentinel")
    void validationFailureLogDoesNotLeakPassword() throws Exception {
        Logger webLogger = (Logger) LoggerFactory.getLogger("org.springframework.web");
        Level originalLevel = webLogger.getLevel();
        webLogger.setLevel(Level.DEBUG);

        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        webLogger.addAppender(listAppender);

        String shortPasswordSentinel = "pwd-123";

        try {
            mockMvc.perform(post("/api/v1/auth/registrations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RegisterRequest(
                                    "athlete@example.com", shortPasswordSentinel, "Alex", "en-US", "UTC"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

            for (ILoggingEvent event : listAppender.list) {
                String msg = event.getFormattedMessage();
                assertThat(msg).doesNotContain(shortPasswordSentinel);
            }
        } finally {
            webLogger.detachAppender(listAppender);
            webLogger.setLevel(originalLevel);
        }
    }
}
