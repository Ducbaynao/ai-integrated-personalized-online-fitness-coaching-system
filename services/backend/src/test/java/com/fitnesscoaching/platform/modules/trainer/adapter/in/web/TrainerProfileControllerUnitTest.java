package com.fitnesscoaching.platform.modules.trainer.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityRevokedException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerProfileAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.TrainerProfileNotFoundException;
import com.fitnesscoaching.platform.common.exception.TrainerSlugAlreadyExistsException;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.CreateTrainerProfileCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.CreateTrainerProfileUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetTrainerProfileUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.UpdateTrainerProfileCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.UpdateTrainerProfileUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerProfileView;
import com.fitnesscoaching.platform.modules.trainer.domain.CoachingEligibility;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerActivityStatus;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TrainerProfileController.class)
@Import({
        SecurityConfig.class,
        JacksonConfig.class,
        ClockConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        RequestIdFilter.class
})
class TrainerProfileControllerUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreateTrainerProfileUseCase createTrainerProfileUseCase;

    @MockitoBean
    private GetTrainerProfileUseCase getTrainerProfileUseCase;

    @MockitoBean
    private UpdateTrainerProfileUseCase updateTrainerProfileUseCase;

    @MockitoBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private TrainerProfile sampleTrainerProfile(UUID userId) {
        return new TrainerProfile(
                userId,
                "coach-alex",
                "Certified strength & conditioning coach with 8 years experience.",
                new BigDecimal("8.5"),
                true,
                TrainerVerificationStatus.NOT_SUBMITTED,
                TrainerActivityStatus.ACTIVE,
                null,
                null,
                true,
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-20T10:00:00Z")
        );
    }

    private TrainerProfileView sampleTrainerProfileView(UUID userId) {
        return new TrainerProfileView(
                sampleTrainerProfile(userId),
                new CoachingEligibility(false, List.of("APPLICATION_NOT_SUBMITTED"))
        );
    }

    // ==========================================
    // POST /api/v1/trainer-profiles
    // ==========================================

    @Test
    @DisplayName("POST /trainer-profiles creates trainer profile and returns 201 with Location header and eligible=false")
    void createProfile_success() throws Exception {
        when(createTrainerProfileUseCase.createTrainerProfile(any(CreateTrainerProfileCommand.class)))
                .thenReturn(sampleTrainerProfileView(USER_ID));

        String json = """
                {
                    "publicSlug": "coach-alex",
                    "bio": "Certified strength & conditioning coach with 8 years experience.",
                    "yearsExperience": 8.5,
                    "acceptingStudents": true
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/trainer-profiles/me"))
                .andExpect(jsonPath("$.userId", is(USER_ID.toString())))
                .andExpect(jsonPath("$.publicSlug", is("coach-alex")))
                .andExpect(jsonPath("$.bio", is("Certified strength & conditioning coach with 8 years experience.")))
                .andExpect(jsonPath("$.yearsExperience", is(8.5)))
                .andExpect(jsonPath("$.verificationStatus", is("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.activityStatus", is("ACTIVE")))
                .andExpect(jsonPath("$.acceptingStudents", is(true)))
                .andExpect(jsonPath("$.verifiedAt", nullValue()))
                .andExpect(jsonPath("$.coachingEligibility.eligible", is(false)))
                .andExpect(jsonPath("$.coachingEligibility.blockingReasons", hasItem("APPLICATION_NOT_SUBMITTED")));

        ArgumentCaptor<CreateTrainerProfileCommand> captor = ArgumentCaptor.forClass(CreateTrainerProfileCommand.class);
        verify(createTrainerProfileUseCase).createTrainerProfile(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().publicSlug()).isEqualTo("coach-alex");
        assertThat(captor.getValue().yearsExperience()).isEqualByComparingTo("8.5");
        assertThat(captor.getValue().acceptingStudents()).isTrue();
    }

    @Test
    @DisplayName("POST /trainer-profiles defaults acceptingStudents to false when omitted")
    void createProfile_omittedAcceptingStudents_defaultsToFalse() throws Exception {
        when(createTrainerProfileUseCase.createTrainerProfile(any(CreateTrainerProfileCommand.class)))
                .thenReturn(sampleTrainerProfileView(USER_ID));

        String json = """
                {
                    "publicSlug": "coach-alex"
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateTrainerProfileCommand> captor = ArgumentCaptor.forClass(CreateTrainerProfileCommand.class);
        verify(createTrainerProfileUseCase).createTrainerProfile(captor.capture());
        assertThat(captor.getValue().acceptingStudents()).isFalse();
    }

    @Test
    @DisplayName("POST /trainer-profiles unauthenticated returns 401")
    void createProfile_unauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicSlug\": \"coach-alex\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /trainer-profiles with invalid slug pattern returns 400")
    void createProfile_invalidSlug_returns400() throws Exception {
        String json = """
                {
                    "publicSlug": "Coach Alex!"
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("publicSlug")));
    }

    @Test
    @DisplayName("POST /trainer-profiles with negative yearsExperience returns 400")
    void createProfile_negativeYearsExperience_returns400() throws Exception {
        String json = """
                {
                    "publicSlug": "coach-alex",
                    "yearsExperience": -1
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("yearsExperience")));
    }

    @Test
    @DisplayName("POST /trainer-profiles with unknown property returns 400")
    void createProfile_unknownProperty_returns400() throws Exception {
        String json = """
                {
                    "publicSlug": "coach-alex",
                    "canCoach": true
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /trainer-profiles duplicate profile returns 409 TRAINER_PROFILE_ALREADY_EXISTS")
    void createProfile_duplicate_returns409() throws Exception {
        when(createTrainerProfileUseCase.createTrainerProfile(any(CreateTrainerProfileCommand.class)))
                .thenThrow(new TrainerProfileAlreadyExistsException("Trainer profile already exists"));

        String json = """
                {
                    "publicSlug": "coach-alex"
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_PROFILE_ALREADY_EXISTS")));
    }

    @Test
    @DisplayName("POST /trainer-profiles duplicate slug returns 409 TRAINER_SLUG_ALREADY_EXISTS")
    void createProfile_slugExists_returns409() throws Exception {
        when(createTrainerProfileUseCase.createTrainerProfile(any(CreateTrainerProfileCommand.class)))
                .thenThrow(new TrainerSlugAlreadyExistsException("Public slug 'coach-alex' already exists"));

        String json = """
                {
                    "publicSlug": "coach-alex"
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_SLUG_ALREADY_EXISTS")));
    }

    @Test
    @DisplayName("POST /trainer-profiles revoked capability returns 409 TRAINER_CAPABILITY_REVOKED")
    void createProfile_revokedRole_returns409() throws Exception {
        when(createTrainerProfileUseCase.createTrainerProfile(any(CreateTrainerProfileCommand.class)))
                .thenThrow(new TrainerCapabilityRevokedException("TRAINER role has been revoked"));

        String json = """
                {
                    "publicSlug": "coach-alex"
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_CAPABILITY_REVOKED")));
    }

    @Test
    @DisplayName("POST /trainer-profiles suspended account returns 403 ACCOUNT_UNAVAILABLE")
    void createProfile_accountSuspended_returns403() throws Exception {
        when(createTrainerProfileUseCase.createTrainerProfile(any(CreateTrainerProfileCommand.class)))
                .thenThrow(new AccountUnavailableException("Account is SUSPENDED"));

        String json = """
                {
                    "publicSlug": "coach-alex"
                }
                """;

        mockMvc.perform(post("/api/v1/trainer-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }

    // ==========================================
    // GET /api/v1/trainer-profiles/me
    // ==========================================

    @Test
    @DisplayName("GET /trainer-profiles/me returns profile with coachingEligibility and canCoach=false")
    void getProfile_success() throws Exception {
        when(getTrainerProfileUseCase.getTrainerProfile(USER_ID)).thenReturn(sampleTrainerProfileView(USER_ID));

        mockMvc.perform(get("/api/v1/trainer-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(USER_ID.toString())))
                .andExpect(jsonPath("$.publicSlug", is("coach-alex")))
                .andExpect(jsonPath("$.verificationStatus", is("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.activityStatus", is("ACTIVE")))
                .andExpect(jsonPath("$.coachingEligibility.eligible", is(false)))
                .andExpect(jsonPath("$.coachingEligibility.blockingReasons", hasItem("APPLICATION_NOT_SUBMITTED")));
    }

    @Test
    @DisplayName("GET /trainer-profiles/me unauthenticated returns 401")
    void getProfile_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/trainer-profiles/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /trainer-profiles/me when not found returns 404 TRAINER_PROFILE_NOT_FOUND")
    void getProfile_notFound() throws Exception {
        when(getTrainerProfileUseCase.getTrainerProfile(USER_ID))
                .thenThrow(new TrainerProfileNotFoundException("Trainer profile not found"));

        mockMvc.perform(get("/api/v1/trainer-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_PROFILE_NOT_FOUND")));
    }

    @Test
    @DisplayName("GET /trainer-profiles/me when role revoked returns 403 TRAINER_CAPABILITY_UNAVAILABLE")
    void getProfile_revokedCapability_returns403() throws Exception {
        when(getTrainerProfileUseCase.getTrainerProfile(USER_ID))
                .thenThrow(new TrainerCapabilityUnavailableException("TRAINER capability is revoked or inactive"));

        mockMvc.perform(get("/api/v1/trainer-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_CAPABILITY_UNAVAILABLE")));
    }

    // ==========================================
    // PATCH /api/v1/trainer-profiles/me
    // ==========================================

    @Test
    @DisplayName("PATCH /trainer-profiles/me updates partial fields")
    void updateProfile_partial_success() throws Exception {
        TrainerProfile updated = new TrainerProfile(
                USER_ID,
                "coach-alex-pro",
                "Updated bio",
                new BigDecimal("8.5"),
                false,
                TrainerVerificationStatus.NOT_SUBMITTED,
                TrainerActivityStatus.ACTIVE,
                null,
                null,
                true,
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-21T11:00:00Z")
        );
        when(updateTrainerProfileUseCase.updateTrainerProfile(any(UpdateTrainerProfileCommand.class)))
                .thenReturn(new TrainerProfileView(updated, new CoachingEligibility(false, List.of("APPLICATION_NOT_SUBMITTED"))));

        String json = """
                {
                    "publicSlug": "coach-alex-pro",
                    "acceptingStudents": false
                }
                """;

        mockMvc.perform(patch("/api/v1/trainer-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicSlug", is("coach-alex-pro")))
                .andExpect(jsonPath("$.acceptingStudents", is(false)));

        ArgumentCaptor<UpdateTrainerProfileCommand> captor = ArgumentCaptor.forClass(UpdateTrainerProfileCommand.class);
        verify(updateTrainerProfileUseCase).updateTrainerProfile(captor.capture());
        UpdateTrainerProfileCommand cmd = captor.getValue();
        assertThat(cmd.userId()).isEqualTo(USER_ID);
        assertThat(cmd.publicSlug().isSpecified()).isTrue();
        assertThat(cmd.publicSlug().value()).isEqualTo("coach-alex-pro");
        assertThat(cmd.acceptingStudents().isSpecified()).isTrue();
        assertThat(cmd.acceptingStudents().value()).isFalse();
        assertThat(cmd.bio().isSpecified()).isFalse();
        assertThat(cmd.yearsExperience().isSpecified()).isFalse();
    }

    @Test
    @DisplayName("PATCH /trainer-profiles/me allows setting bio to null")
    void updateProfile_clearBio_success() throws Exception {
        TrainerProfile updated = new TrainerProfile(
                USER_ID,
                "coach-alex",
                null,
                new BigDecimal("8.5"),
                true,
                TrainerVerificationStatus.NOT_SUBMITTED,
                TrainerActivityStatus.ACTIVE,
                null,
                null,
                true,
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-21T11:00:00Z")
        );
        when(updateTrainerProfileUseCase.updateTrainerProfile(any(UpdateTrainerProfileCommand.class)))
                .thenReturn(new TrainerProfileView(updated, new CoachingEligibility(false, List.of("APPLICATION_NOT_SUBMITTED"))));

        String json = """
                {
                    "bio": null
                }
                """;

        mockMvc.perform(patch("/api/v1/trainer-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio", nullValue()));

        ArgumentCaptor<UpdateTrainerProfileCommand> captor = ArgumentCaptor.forClass(UpdateTrainerProfileCommand.class);
        verify(updateTrainerProfileUseCase).updateTrainerProfile(captor.capture());
        assertThat(captor.getValue().bio().isSpecified()).isTrue();
        assertThat(captor.getValue().bio().value()).isNull();
    }

    @Test
    @DisplayName("PATCH /trainer-profiles/me with empty body returns 400 validation failed")
    void updateProfile_emptyBody_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/trainer-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("PATCH /trainer-profiles/me with acceptingStudents null returns 400 VALIDATION_FAILED")
    void updateProfile_acceptingStudentsNull_returns400() throws Exception {
        String json = """
                {
                    "acceptingStudents": null
                }
                """;

        mockMvc.perform(patch("/api/v1/trainer-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("PATCH /trainer-profiles/me with prohibited field returns 400 VALIDATION_FAILED")
    void updateProfile_prohibitedField_returns400() throws Exception {
        String json = """
                {
                    "verificationStatus": "VERIFIED"
                }
                """;

        mockMvc.perform(patch("/api/v1/trainer-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }
}
