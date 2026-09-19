package com.fitnesscoaching.platform.modules.auth;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.ConfirmEmailRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.LoginRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.LogoutRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RefreshTokenRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RegisterRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.SessionUserDto;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.TokenPairResponse;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.UserCapabilitiesDto;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.UserSettingsDto;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.LoginCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.LogoutSessionCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RefreshSessionCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.TokenPairResult;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.auth.domain.IssuedAccessToken;
import com.fitnesscoaching.platform.modules.auth.domain.UserAccountRecord;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.user.application.model.UserCapabilitiesView;
import com.fitnesscoaching.platform.modules.user.application.model.UserSettingsView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactionUnitTest {

    private static final String PASSWORD_SENTINEL = "password-secret-sentinel-123!";
    private static final String VERIFICATION_TOKEN_SENTINEL = "verification-token-secret-sentinel-45678";
    private static final String ACCESS_TOKEN_SENTINEL = "access-token-secret-sentinel-89012";
    private static final String REFRESH_TOKEN_SENTINEL = "refresh-token-secret-sentinel-34567";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Web DTO toString() excludes secrets and outputs [REDACTED]")
    void webDtoToStringRedactsSecrets() {
        // RegisterRequest
        RegisterRequest registerReq = new RegisterRequest(
                "athlete@example.com", PASSWORD_SENTINEL, "Alex", "en-US", "UTC");
        assertThat(registerReq.toString())
                .doesNotContain(PASSWORD_SENTINEL)
                .contains("password=[REDACTED]");

        // ConfirmEmailRequest
        ConfirmEmailRequest confirmReq = new ConfirmEmailRequest(VERIFICATION_TOKEN_SENTINEL);
        assertThat(confirmReq.toString())
                .doesNotContain(VERIFICATION_TOKEN_SENTINEL)
                .contains("token=[REDACTED]");

        // LoginRequest
        LoginRequest loginReq = new LoginRequest("athlete@example.com", PASSWORD_SENTINEL, "Pixel 9");
        assertThat(loginReq.toString())
                .doesNotContain(PASSWORD_SENTINEL)
                .contains("password=[REDACTED]");

        // RefreshTokenRequest
        RefreshTokenRequest refreshReq = new RefreshTokenRequest(REFRESH_TOKEN_SENTINEL, "Pixel 9");
        assertThat(refreshReq.toString())
                .doesNotContain(REFRESH_TOKEN_SENTINEL)
                .contains("refreshToken=[REDACTED]");

        // LogoutRequest
        LogoutRequest logoutReq = new LogoutRequest(REFRESH_TOKEN_SENTINEL);
        assertThat(logoutReq.toString())
                .doesNotContain(REFRESH_TOKEN_SENTINEL)
                .contains("refreshToken=[REDACTED]");

        // TokenPairResponse
        TokenPairResponse tokenPairResp = new TokenPairResponse(
                "Bearer",
                ACCESS_TOKEN_SENTINEL,
                Instant.now().plusSeconds(900),
                REFRESH_TOKEN_SENTINEL,
                Instant.now().plusSeconds(86400),
                new SessionUserDto(
                        UUID.randomUUID(), "athlete@example.com", "Alex", AccountStatus.ACTIVE,
                        "en-US", "UTC", Instant.now(), Instant.now(), null,
                        List.of("STUDENT"),
                        new UserCapabilitiesDto(true, false, false),
                        new UserSettingsDto(1, "METRIC", Map.of(), Map.of())
                )
        );
        assertThat(tokenPairResp.toString())
                .doesNotContain(ACCESS_TOKEN_SENTINEL)
                .doesNotContain(REFRESH_TOKEN_SENTINEL)
                .contains("accessToken=[REDACTED]")
                .contains("refreshToken=[REDACTED]");
    }

    @Test
    @DisplayName("Application Command and Result toString() excludes secrets and outputs [REDACTED]")
    void applicationRecordsToStringRedactSecrets() {
        // RegisterUserCommand
        RegisterUserCommand registerCmd = new RegisterUserCommand(
                "athlete@example.com", PASSWORD_SENTINEL, "Alex", "en-US", "UTC");
        assertThat(registerCmd.toString())
                .doesNotContain(PASSWORD_SENTINEL)
                .contains("password=[REDACTED]");

        // ConfirmEmailCommand
        ConfirmEmailCommand confirmCmd = new ConfirmEmailCommand(VERIFICATION_TOKEN_SENTINEL);
        assertThat(confirmCmd.toString())
                .doesNotContain(VERIFICATION_TOKEN_SENTINEL)
                .contains("token=[REDACTED]");

        // LoginCommand
        LoginCommand loginCmd = new LoginCommand("athlete@example.com", PASSWORD_SENTINEL, "Pixel 9");
        assertThat(loginCmd.toString())
                .doesNotContain(PASSWORD_SENTINEL)
                .contains("password=[REDACTED]");

        // RefreshSessionCommand
        RefreshSessionCommand refreshCmd = new RefreshSessionCommand(REFRESH_TOKEN_SENTINEL, "Pixel 9");
        assertThat(refreshCmd.toString())
                .doesNotContain(REFRESH_TOKEN_SENTINEL)
                .contains("refreshToken=[REDACTED]");

        // LogoutSessionCommand
        LogoutSessionCommand logoutCmd = new LogoutSessionCommand(UUID.randomUUID(), REFRESH_TOKEN_SENTINEL);
        assertThat(logoutCmd.toString())
                .doesNotContain(REFRESH_TOKEN_SENTINEL)
                .contains("refreshToken=[REDACTED]");

        // TokenPairResult
        TokenPairResult tokenPairResult = new TokenPairResult(
                ACCESS_TOKEN_SENTINEL,
                Instant.now().plusSeconds(900),
                REFRESH_TOKEN_SENTINEL,
                Instant.now().plusSeconds(86400),
                new CurrentUserView(
                        UUID.randomUUID(), "athlete@example.com", "Alex", AccountStatus.ACTIVE,
                        "en-US", "UTC", Instant.now(), Instant.now(), null,
                        List.of("STUDENT"),
                        new UserCapabilitiesView(true, false, false),
                        UserSettingsView.defaults()
                )
        );
        assertThat(tokenPairResult.toString())
                .doesNotContain(ACCESS_TOKEN_SENTINEL)
                .doesNotContain(REFRESH_TOKEN_SENTINEL)
                .contains("accessToken=[REDACTED]")
                .contains("refreshToken=[REDACTED]");

        // IssuedAccessToken
        IssuedAccessToken issuedToken = new IssuedAccessToken(ACCESS_TOKEN_SENTINEL, Instant.now().plusSeconds(900));
        assertThat(issuedToken.toString())
                .doesNotContain(ACCESS_TOKEN_SENTINEL)
                .contains("value=[REDACTED]");

        // UserAccountRecord
        UserAccountRecord userAccount = new UserAccountRecord(
                UUID.randomUUID(), "athlete@example.com", PASSWORD_SENTINEL, "Alex",
                AccountStatus.ACTIVE, "en-US", "UTC", Instant.now(), Instant.now(), Instant.now()
        );
        assertThat(userAccount.toString())
                .doesNotContain(PASSWORD_SENTINEL)
                .contains("passwordHash=[REDACTED]");
    }

    @Test
    @DisplayName("toString() safely handles null secret values without throwing NPE")
    void toStringHandlesNullSecretsSafely() {
        RegisterRequest registerReq = new RegisterRequest(null, null, null, null, null);
        assertThat(registerReq.toString()).contains("password=null");

        ConfirmEmailRequest confirmReq = new ConfirmEmailRequest(null);
        assertThat(confirmReq.toString()).contains("token=null");

        LoginRequest loginReq = new LoginRequest(null, null, null);
        assertThat(loginReq.toString()).contains("password=null");

        RefreshTokenRequest refreshReq = new RefreshTokenRequest(null, null);
        assertThat(refreshReq.toString()).contains("refreshToken=null");

        LogoutRequest logoutReq = new LogoutRequest(null);
        assertThat(logoutReq.toString()).contains("refreshToken=null");

        TokenPairResponse tokenPairResp = new TokenPairResponse(null, null, null, null, null, null);
        assertThat(tokenPairResp.toString())
                .contains("accessToken=null")
                .contains("refreshToken=null");
    }

    @Test
    @DisplayName("JSON serialization and deserialization remain functional and preserve secrets in payload")
    void jsonSerializationAndDeserializationUnchanged() throws Exception {
        // RegisterRequest
        RegisterRequest registerReq = new RegisterRequest(
                "athlete@example.com", PASSWORD_SENTINEL, "Alex", "en-US", "UTC");
        String registerJson = objectMapper.writeValueAsString(registerReq);
        assertThat(registerJson).contains(PASSWORD_SENTINEL);
        RegisterRequest deserializedReg = objectMapper.readValue(registerJson, RegisterRequest.class);
        assertThat(deserializedReg.password()).isEqualTo(PASSWORD_SENTINEL);

        // ConfirmEmailRequest
        ConfirmEmailRequest confirmReq = new ConfirmEmailRequest(VERIFICATION_TOKEN_SENTINEL);
        String confirmJson = objectMapper.writeValueAsString(confirmReq);
        assertThat(confirmJson).contains(VERIFICATION_TOKEN_SENTINEL);
        ConfirmEmailRequest deserializedConfirm = objectMapper.readValue(confirmJson, ConfirmEmailRequest.class);
        assertThat(deserializedConfirm.token()).isEqualTo(VERIFICATION_TOKEN_SENTINEL);

        // LoginRequest
        LoginRequest loginReq = new LoginRequest("athlete@example.com", PASSWORD_SENTINEL, "Pixel 9");
        String loginJson = objectMapper.writeValueAsString(loginReq);
        assertThat(loginJson).contains(PASSWORD_SENTINEL);
        LoginRequest deserializedLogin = objectMapper.readValue(loginJson, LoginRequest.class);
        assertThat(deserializedLogin.password()).isEqualTo(PASSWORD_SENTINEL);

        // RefreshTokenRequest
        RefreshTokenRequest refreshReq = new RefreshTokenRequest(REFRESH_TOKEN_SENTINEL, "Pixel 9");
        String refreshJson = objectMapper.writeValueAsString(refreshReq);
        assertThat(refreshJson).contains(REFRESH_TOKEN_SENTINEL);
        RefreshTokenRequest deserializedRefresh = objectMapper.readValue(refreshJson, RefreshTokenRequest.class);
        assertThat(deserializedRefresh.refreshToken()).isEqualTo(REFRESH_TOKEN_SENTINEL);

        // LogoutRequest
        LogoutRequest logoutReq = new LogoutRequest(REFRESH_TOKEN_SENTINEL);
        String logoutJson = objectMapper.writeValueAsString(logoutReq);
        assertThat(logoutJson).contains(REFRESH_TOKEN_SENTINEL);
        LogoutRequest deserializedLogout = objectMapper.readValue(logoutJson, LogoutRequest.class);
        assertThat(deserializedLogout.refreshToken()).isEqualTo(REFRESH_TOKEN_SENTINEL);

        // TokenPairResponse
        Instant now = Instant.parse("2026-09-20T08:00:00Z");
        TokenPairResponse tokenPairResp = new TokenPairResponse(
                "Bearer",
                ACCESS_TOKEN_SENTINEL,
                now.plusSeconds(900),
                REFRESH_TOKEN_SENTINEL,
                now.plusSeconds(86400),
                new SessionUserDto(
                        UUID.randomUUID(), "athlete@example.com", "Alex", AccountStatus.ACTIVE,
                        "en-US", "UTC", now, now, null,
                        List.of("STUDENT"),
                        new UserCapabilitiesDto(true, false, false),
                        new UserSettingsDto(1, "METRIC", Map.of(), Map.of())
                )
        );
        String tokenPairJson = objectMapper.writeValueAsString(tokenPairResp);
        assertThat(tokenPairJson).contains(ACCESS_TOKEN_SENTINEL);
        assertThat(tokenPairJson).contains(REFRESH_TOKEN_SENTINEL);
        TokenPairResponse deserializedTokenPair = objectMapper.readValue(tokenPairJson, TokenPairResponse.class);
        assertThat(deserializedTokenPair.accessToken()).isEqualTo(ACCESS_TOKEN_SENTINEL);
        assertThat(deserializedTokenPair.refreshToken()).isEqualTo(REFRESH_TOKEN_SENTINEL);
    }
}
