package com.fitnesscoaching.platform.modules.user.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.adapter.in.web.dto.UpdateCurrentUserRequest;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.user.application.model.UserCapabilitiesView;
import com.fitnesscoaching.platform.modules.user.application.model.UserSettingsView;
import com.fitnesscoaching.platform.modules.user.application.port.in.GetCurrentUserUseCase;
import com.fitnesscoaching.platform.modules.user.application.port.in.UpdateCurrentUserCommand;
import com.fitnesscoaching.platform.modules.user.application.port.in.UpdateCurrentUserUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CurrentUserController.class)
@Import({
        SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ClockConfig.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        JacksonConfig.class
})
class CurrentUserControllerUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GetCurrentUserUseCase getCurrentUserUseCase;

    @MockitoBean
    private UpdateCurrentUserUseCase updateCurrentUserUseCase;

    @MockitoBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    private CurrentUserView createSampleView(UUID userId) {
        return new CurrentUserView(
                userId,
                "athlete@example.com",
                "Alice Athlete",
                AccountStatus.ACTIVE,
                "vi-VN",
                "Asia/Ho_Chi_Minh",
                Instant.parse("2026-09-01T12:00:00Z"),
                Instant.parse("2026-09-01T12:00:00Z"),
                "+84901234567",
                List.of("STUDENT"),
                new UserCapabilitiesView(true, false, false),
                new UserSettingsView(1, "METRIC", Map.of("theme", "dark"), Map.of("shareProgress", false))
        );
    }

    @Test
    @DisplayName("GET /users/me uses JWT subject as userId and returns 200 with contract DTO")
    void getCurrentUser_authenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        when(getCurrentUserUseCase.getCurrentUser(userId)).thenReturn(createSampleView(userId));

        mockMvc.perform(get("/api/v1/users/me")
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(userId.toString())))
                .andExpect(jsonPath("$.email", is("athlete@example.com")))
                .andExpect(jsonPath("$.displayName", is("Alice Athlete")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.preferredLocale", is("vi-VN")))
                .andExpect(jsonPath("$.timezone", is("Asia/Ho_Chi_Minh")))
                .andExpect(jsonPath("$.phoneNumber", is("+84901234567")))
                .andExpect(jsonPath("$.roles[0]", is("STUDENT")))
                .andExpect(jsonPath("$.capabilities.hasStudentProfile", is(true)))
                .andExpect(jsonPath("$.capabilities.hasTrainerProfile", is(false)))
                .andExpect(jsonPath("$.capabilities.canCoach", is(false)))
                .andExpect(jsonPath("$.settings.weekStartsOn", is(1)))
                .andExpect(jsonPath("$.settings.measurementSystem", is("METRIC")))
                .andExpect(jsonPath("$.settings.accessibilityPreferences.theme", is("dark")))
                .andExpect(jsonPath("$.settings.privacyPreferences.shareProgress", is(false)));

        verify(getCurrentUserUseCase).getCurrentUser(userId);
    }

    @Test
    @DisplayName("GET /users/me without authentication returns 401 UNAUTHORIZED envelope")
    void getCurrentUser_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.requestId", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors", empty()));
    }

    @Test
    @DisplayName("GET /users/me when user does not exist returns 401 UNAUTHORIZED envelope")
    void getCurrentUser_userNotFound() throws Exception {
        UUID userId = UUID.randomUUID();
        when(getCurrentUserUseCase.getCurrentUser(userId)).thenThrow(new UserNotFoundException("User not found: " + userId));

        mockMvc.perform(get("/api/v1/users/me")
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")))
                .andExpect(jsonPath("$.message", is("Authenticated user does not exist.")))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.requestId", notNullValue()));
    }

    @Test
    @DisplayName("PATCH /users/me uses JWT subject and maps partial update fields into command")
    void updateCurrentUser_partialUpdateSuccess() throws Exception {
        UUID userId = UUID.randomUUID();
        when(updateCurrentUserUseCase.updateCurrentUser(any())).thenReturn(createSampleView(userId));

        String body = """
                {
                    "displayName": "Alice Updated",
                    "phoneNumber": null,
                    "settings": {
                        "weekStartsOn": 0,
                        "measurementSystem": "IMPERIAL"
                    }
                }
                """;

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(userId.toString())))
                .andExpect(jsonPath("$.displayName", is("Alice Athlete")));

        ArgumentCaptor<UpdateCurrentUserCommand> captor = ArgumentCaptor.forClass(UpdateCurrentUserCommand.class);
        verify(updateCurrentUserUseCase).updateCurrentUser(captor.capture());
        UpdateCurrentUserCommand cmd = captor.getValue();

        assertThat(cmd.userId()).isEqualTo(userId);
        assertThat(cmd.displayName().isSpecified()).isTrue();
        assertThat(cmd.displayName().value()).isEqualTo("Alice Updated");

        assertThat(cmd.phoneNumber().isSpecified()).isTrue();
        assertThat(cmd.phoneNumber().isNull()).isTrue(); // explicit null!

        assertThat(cmd.preferredLocale().isSpecified()).isFalse(); // omitted
        assertThat(cmd.timezone().isSpecified()).isFalse(); // omitted

        assertThat(cmd.settings().isSpecified()).isTrue();
        assertThat(cmd.settings().value().weekStartsOn().isSpecified()).isTrue();
        assertThat(cmd.settings().value().weekStartsOn().value()).isEqualTo(0);
        assertThat(cmd.settings().value().measurementSystem().isSpecified()).isTrue();
        assertThat(cmd.settings().value().measurementSystem().value()).isEqualTo("IMPERIAL");
        assertThat(cmd.settings().value().accessibilityPreferences().isSpecified()).isFalse();
        assertThat(cmd.settings().value().privacyPreferences().isSpecified()).isFalse();
    }

    @Test
    @DisplayName("PATCH /users/me without authentication returns 401 UNAUTHORIZED envelope")
    void updateCurrentUser_unauthenticated() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"Alice\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));
    }

    @Test
    @DisplayName("PATCH /users/me with empty request body {} returns 400 VALIDATION_FAILED envelope")
    void updateCurrentUser_emptyBodyValidationFailed() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.requestId", notNullValue()));
    }

    @Test
    @DisplayName("PATCH /users/me with invalid fields returns 400 VALIDATION_FAILED envelope")
    void updateCurrentUser_invalidFields() throws Exception {
        UUID userId = UUID.randomUUID();

        // 1. Invalid weekStartsOn (> 6)
        String invalidWeek = """
                {
                    "settings": {
                        "weekStartsOn": 7
                    }
                }
                """;
        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidWeek)
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 2. Invalid measurementSystem
        String invalidSystem = """
                {
                    "settings": {
                        "measurementSystem": "UNKNOWN_UNIT"
                    }
                }
                """;
        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidSystem)
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        // 3. Unknown property (e.g. email or status which cannot be updated by client)
        String unknownProp = """
                {
                    "email": "hacked@example.com"
                }
                """;
        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownProp)
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("PATCH /users/me with empty settings object {\"settings\": {}} is valid and accepted")
    void updateCurrentUser_emptySettingsObjectAccepted() throws Exception {
        UUID userId = UUID.randomUUID();
        when(updateCurrentUserUseCase.updateCurrentUser(any())).thenReturn(createSampleView(userId));

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\": {}}")
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(userId.toString())));

        ArgumentCaptor<UpdateCurrentUserCommand> captor = ArgumentCaptor.forClass(UpdateCurrentUserCommand.class);
        verify(updateCurrentUserUseCase).updateCurrentUser(captor.capture());
        UpdateCurrentUserCommand cmd = captor.getValue();

        assertThat(cmd.userId()).isEqualTo(userId);
        assertThat(cmd.displayName().isSpecified()).isFalse();
        assertThat(cmd.phoneNumber().isSpecified()).isFalse();
        assertThat(cmd.settings().isSpecified()).isTrue();
        assertThat(cmd.settings().value().hasUpdates()).isFalse();
    }

    @Test
    @DisplayName("PATCH /users/me with null settings {\"settings\": null} returns 400 VALIDATION_FAILED")
    void updateCurrentUser_nullSettingsRejected() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\": null}")
                        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }
}
