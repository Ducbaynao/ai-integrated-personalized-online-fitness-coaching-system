package com.fitnesscoaching.platform.modules.trainer.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationAlreadyActiveException;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationNotFoundException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerProfileNotFoundException;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetCurrentTrainerApplicationUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.SubmitTrainerApplicationCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.SubmitTrainerApplicationUseCase;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TrainerApplicationController.class)
@Import({
        SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ClockConfig.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        JacksonConfig.class
})
class TrainerApplicationControllerUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SubmitTrainerApplicationUseCase submitTrainerApplicationUseCase;

    @MockitoBean
    private GetCurrentTrainerApplicationUseCase getCurrentTrainerApplicationUseCase;

    @MockitoBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID APP_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant FIXED_TIME = Instant.parse("2026-09-22T08:00:00Z");

    private TrainerApplication sampleApplication() {
        return new TrainerApplication(
                APP_ID,
                USER_ID,
                TrainerVerificationStatus.PENDING,
                FIXED_TIME,
                null,
                null,
                null,
                "Applicant note",
                List.of(),
                List.of(),
                FIXED_TIME,
                FIXED_TIME
        );
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications: returns 201 Created on valid submission without reviewNotes")
    void submitApplication_returns201() throws Exception {
        when(submitTrainerApplicationUseCase.submitApplication(any(SubmitTrainerApplicationCommand.class)))
                .thenReturn(sampleApplication());

        String json = """
                {
                    "certificateIds": ["33333333-3333-3333-3333-333333333333"],
                    "documentMediaIds": ["44444444-4444-4444-4444-444444444444"],
                    "applicantNote": "Please verify my certificate."
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/trainer-applications/me/current"))
                .andExpect(jsonPath("$.id", is(APP_ID.toString())))
                .andExpect(jsonPath("$.trainerId", is(USER_ID.toString())))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.submittedAt", notNullValue()))
                .andExpect(jsonPath("$.reviewedAt", nullValue()))
                .andExpect(jsonPath("$.rejectionReason", nullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()))
                .andExpect(jsonPath("$.reviewNotes").doesNotExist())
                .andExpect(jsonPath("$.review_notes").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications: returns 401 Unauthorized when unauthenticated")
    void submitApplication_unauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/trainer-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications: returns 400 Validation Error when applicantNote exceeds 2000 chars")
    void submitApplication_applicantNoteTooLong_returns400() throws Exception {
        String longNote = "a".repeat(2001);
        String json = String.format("{\"applicantNote\": \"%s\"}", longNote);

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications: returns 400 Bad Request on unknown fields")
    void submitApplication_unknownFields_returns400() throws Exception {
        String json = """
                {
                    "unknownField": "bad-value",
                    "applicantNote": "Note"
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications: returns 400 when certificateIds contains null item")
    void submitApplication_nullItemInCertificateIds_returns400() throws Exception {
        String json = """
                {
                    "certificateIds": [null]
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", containsString("certificateIds")))
                .andExpect(jsonPath("$.fieldErrors[0].message", is("Certificate ID cannot be null")));
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications: returns 400 when documentMediaIds contains null item")
    void submitApplication_nullItemInDocumentMediaIds_returns400() throws Exception {
        String json = """
                {
                    "documentMediaIds": [null]
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", containsString("documentMediaIds")))
                .andExpect(jsonPath("$.fieldErrors[0].message", is("Document media ID cannot be null")));
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications: returns 403 Forbidden when capability unavailable")
    void submitApplication_capabilityUnavailable_returns403() throws Exception {
        when(submitTrainerApplicationUseCase.submitApplication(any(SubmitTrainerApplicationCommand.class)))
                .thenThrow(new TrainerCapabilityUnavailableException("Trainer capability is not active"));

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_CAPABILITY_UNAVAILABLE")));
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications: returns 403 Forbidden when account unavailable")
    void submitApplication_accountUnavailable_returns403() throws Exception {
        when(submitTrainerApplicationUseCase.submitApplication(any(SubmitTrainerApplicationCommand.class)))
                .thenThrow(new AccountUnavailableException("Account is suspended"));

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications: returns 404 Not Found when trainer profile does not exist")
    void submitApplication_missingProfile_returns404() throws Exception {
        when(submitTrainerApplicationUseCase.submitApplication(any(SubmitTrainerApplicationCommand.class)))
                .thenThrow(new TrainerProfileNotFoundException("Trainer profile not found"));

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_PROFILE_NOT_FOUND")));
    }

    @Test
    @DisplayName("POST /api/v1/trainer-applications: returns 409 Conflict when active application already exists")
    void submitApplication_alreadyActive_returns409() throws Exception {
        when(submitTrainerApplicationUseCase.submitApplication(any(SubmitTrainerApplicationCommand.class)))
                .thenThrow(new TrainerApplicationAlreadyActiveException("Active application already exists"));

        mockMvc.perform(post("/api/v1/trainer-applications")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_APPLICATION_ALREADY_ACTIVE")));
    }

    @Test
    @DisplayName("GET /api/v1/trainer-applications/me/current: returns 200 OK with current application")
    void getCurrentApplication_returns200() throws Exception {
        when(getCurrentTrainerApplicationUseCase.getCurrentApplication(USER_ID))
                .thenReturn(sampleApplication());

        mockMvc.perform(get("/api/v1/trainer-applications/me/current")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(APP_ID.toString())))
                .andExpect(jsonPath("$.trainerId", is(USER_ID.toString())))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.submittedAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()))
                .andExpect(jsonPath("$.reviewNotes").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/trainer-applications/me/current: returns 401 when unauthenticated")
    void getCurrentApplication_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/trainer-applications/me/current"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/trainer-applications/me/current: returns 404 when no application found")
    void getCurrentApplication_notFound_returns404() throws Exception {
        when(getCurrentTrainerApplicationUseCase.getCurrentApplication(USER_ID))
                .thenThrow(new TrainerApplicationNotFoundException("No trainer application found"));

        mockMvc.perform(get("/api/v1/trainer-applications/me/current")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_APPLICATION_NOT_FOUND")));
    }

    @Test
    @DisplayName("GET /api/v1/trainer-applications/me/current: returns 404 when trainer profile not found")
    void getCurrentApplication_profileNotFound_returns404() throws Exception {
        when(getCurrentTrainerApplicationUseCase.getCurrentApplication(USER_ID))
                .thenThrow(new TrainerProfileNotFoundException("Trainer profile not found"));

        mockMvc.perform(get("/api/v1/trainer-applications/me/current")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_PROFILE_NOT_FOUND")));
    }

    @Test
    @DisplayName("GET /api/v1/trainer-applications/me/current: returns 403 when trainer capability unavailable")
    void getCurrentApplication_capabilityUnavailable_returns403() throws Exception {
        when(getCurrentTrainerApplicationUseCase.getCurrentApplication(USER_ID))
                .thenThrow(new TrainerCapabilityUnavailableException("Trainer capability is not active"));

        mockMvc.perform(get("/api/v1/trainer-applications/me/current")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_CAPABILITY_UNAVAILABLE")));
    }

    @Test
    @DisplayName("GET /api/v1/trainer-applications/me/current: returns 403 when account unavailable")
    void getCurrentApplication_accountUnavailable_returns403() throws Exception {
        when(getCurrentTrainerApplicationUseCase.getCurrentApplication(USER_ID))
                .thenThrow(new AccountUnavailableException("Account is suspended"));

        mockMvc.perform(get("/api/v1/trainer-applications/me/current")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }
}
