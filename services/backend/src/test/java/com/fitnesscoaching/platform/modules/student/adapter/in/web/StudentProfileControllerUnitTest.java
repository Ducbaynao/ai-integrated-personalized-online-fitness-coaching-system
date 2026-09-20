package com.fitnesscoaching.platform.modules.student.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.exception.StudentCapabilityRevokedException;
import com.fitnesscoaching.platform.common.exception.StudentProfileAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.StudentProfileNotFoundException;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.student.application.port.in.CreateStudentProfileCommand;
import com.fitnesscoaching.platform.modules.student.application.port.in.CreateStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.application.port.in.UpdateStudentProfileCommand;
import com.fitnesscoaching.platform.modules.student.application.port.in.UpdateStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.domain.Gender;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
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
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
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

@WebMvcTest(controllers = StudentProfileController.class)
@Import({
        SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ClockConfig.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        JacksonConfig.class
})
class StudentProfileControllerUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreateStudentProfileUseCase createStudentProfileUseCase;

    @MockitoBean
    private GetStudentProfileUseCase getStudentProfileUseCase;

    @MockitoBean
    private UpdateStudentProfileUseCase updateStudentProfileUseCase;

    @MockitoBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("POST /student-profiles creates student profile and returns 201 with Location header")
    void createProfile_success() throws Exception {
        StudentProfile created = new StudentProfile(
                USER_ID,
                LocalDate.of(1995, 5, 20),
                Gender.MALE,
                TrainingExperienceLevel.BEGINNER,
                new BigDecimal("6.5"),
                3,
                60,
                null,
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-20T10:00:00Z")
        );
        when(createStudentProfileUseCase.createStudentProfile(any(CreateStudentProfileCommand.class)))
                .thenReturn(created);

        String json = """
                {
                    "dateOfBirth": "1995-05-20",
                    "gender": "MALE",
                    "trainingExperienceLevel": "BEGINNER",
                    "trainingExperienceMonths": 6.5,
                    "availableDaysPerWeek": 3,
                    "preferredSessionMinutes": 60,
                    "onboardingCompleted": false
                }
                """;

        mockMvc.perform(post("/api/v1/student-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/student-profiles/me"))
                .andExpect(jsonPath("$.userId", is(USER_ID.toString())))
                .andExpect(jsonPath("$.gender", is("MALE")))
                .andExpect(jsonPath("$.trainingExperienceLevel", is("BEGINNER")))
                .andExpect(jsonPath("$.onboardingCompleted", is(false)))
                .andExpect(jsonPath("$.onboardingCompletedAt", nullValue()));
    }

    @Test
    @DisplayName("POST /student-profiles unauthenticated returns 401")
    void createProfile_unauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/student-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /student-profiles with future dateOfBirth returns 400")
    void createProfile_futureDateOfBirth_returns400() throws Exception {
        String json = """
                {
                    "dateOfBirth": "2099-01-01"
                }
                """;

        mockMvc.perform(post("/api/v1/student-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /student-profiles with negative trainingExperienceMonths returns 400")
    void createProfile_negativeMonths_returns400() throws Exception {
        String json = """
                {
                    "trainingExperienceMonths": -1.0
                }
                """;

        mockMvc.perform(post("/api/v1/student-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /student-profiles with invalid availableDaysPerWeek returns 400")
    void createProfile_invalidDays_returns400() throws Exception {
        String json = """
                {
                    "availableDaysPerWeek": 8
                }
                """;

        mockMvc.perform(post("/api/v1/student-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /student-profiles with onboardingCompleted omitted defaults to false")
    void createProfile_onboardingCompletedOmitted_defaultsToFalse() throws Exception {
        StudentProfile profile = new StudentProfile(
                USER_ID, null, null, null, null, null, null, null,
                Instant.parse("2026-09-20T10:00:00Z"), Instant.parse("2026-09-20T10:00:00Z"));
        when(createStudentProfileUseCase.createStudentProfile(any(CreateStudentProfileCommand.class)))
                .thenReturn(profile);

        mockMvc.perform(post("/api/v1/student-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateStudentProfileCommand> captor = ArgumentCaptor.forClass(CreateStudentProfileCommand.class);
        verify(createStudentProfileUseCase).createStudentProfile(captor.capture());
        assertThat(captor.getValue().onboardingCompleted()).isFalse();
    }

    @Test
    @DisplayName("POST /student-profiles with onboardingCompleted false passes false")
    void createProfile_onboardingCompletedFalse_isFalse() throws Exception {
        StudentProfile profile = new StudentProfile(
                USER_ID, null, null, null, null, null, null, null,
                Instant.parse("2026-09-20T10:00:00Z"), Instant.parse("2026-09-20T10:00:00Z"));
        when(createStudentProfileUseCase.createStudentProfile(any(CreateStudentProfileCommand.class)))
                .thenReturn(profile);

        mockMvc.perform(post("/api/v1/student-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": false}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateStudentProfileCommand> captor = ArgumentCaptor.forClass(CreateStudentProfileCommand.class);
        verify(createStudentProfileUseCase).createStudentProfile(captor.capture());
        assertThat(captor.getValue().onboardingCompleted()).isFalse();
    }

    @Test
    @DisplayName("POST /student-profiles with onboardingCompleted true passes true")
    void createProfile_onboardingCompletedTrue_isTrue() throws Exception {
        StudentProfile profile = new StudentProfile(
                USER_ID, null, null, null, null, null, null, Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-20T10:00:00Z"), Instant.parse("2026-09-20T10:00:00Z"));
        when(createStudentProfileUseCase.createStudentProfile(any(CreateStudentProfileCommand.class)))
                .thenReturn(profile);

        mockMvc.perform(post("/api/v1/student-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": true}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateStudentProfileCommand> captor = ArgumentCaptor.forClass(CreateStudentProfileCommand.class);
        verify(createStudentProfileUseCase).createStudentProfile(captor.capture());
        assertThat(captor.getValue().onboardingCompleted()).isTrue();
    }

    @Test
    @DisplayName("POST /student-profiles with onboardingCompleted explicit null returns 400 VALIDATION_FAILED")
    void createProfile_onboardingCompletedExplicitNull_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/student-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /student-profiles when profile already exists returns 409")
    void createProfile_alreadyExists_returns409() throws Exception {
        when(createStudentProfileUseCase.createStudentProfile(any(CreateStudentProfileCommand.class)))
                .thenThrow(new StudentProfileAlreadyExistsException("Profile exists"));

        mockMvc.perform(post("/api/v1/student-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_PROFILE_ALREADY_EXISTS")));
    }

    @Test
    @DisplayName("POST /student-profiles when role is revoked returns 409 STUDENT_CAPABILITY_REVOKED")
    void createProfile_roleRevoked_returns409() throws Exception {
        when(createStudentProfileUseCase.createStudentProfile(any(CreateStudentProfileCommand.class)))
                .thenThrow(new StudentCapabilityRevokedException("Revoked"));

        mockMvc.perform(post("/api/v1/student-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_REVOKED")));
    }

    @Test
    @DisplayName("POST /student-profiles when account is suspended returns 403 ACCOUNT_UNAVAILABLE")
    void createProfile_suspended_returns403() throws Exception {
        when(createStudentProfileUseCase.createStudentProfile(any(CreateStudentProfileCommand.class)))
                .thenThrow(new AccountUnavailableException("Account is SUSPENDED"));

        mockMvc.perform(post("/api/v1/student-profiles")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }

    @Test
    @DisplayName("GET /student-profiles/me returns student profile")
    void getMyProfile_success() throws Exception {
        StudentProfile profile = new StudentProfile(
                USER_ID,
                LocalDate.of(1990, 1, 1),
                Gender.FEMALE,
                TrainingExperienceLevel.ADVANCED,
                new BigDecimal("24.0"),
                5,
                90,
                Instant.parse("2026-09-20T11:00:00Z"),
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-20T11:00:00Z")
        );
        when(getStudentProfileUseCase.getStudentProfile(USER_ID)).thenReturn(profile);

        mockMvc.perform(get("/api/v1/student-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(USER_ID.toString())))
                .andExpect(jsonPath("$.gender", is("FEMALE")))
                .andExpect(jsonPath("$.trainingExperienceLevel", is("ADVANCED")))
                .andExpect(jsonPath("$.onboardingCompleted", is(true)))
                .andExpect(jsonPath("$.onboardingCompletedAt", is("2026-09-20T11:00:00Z")));
    }

    @Test
    @DisplayName("GET /student-profiles/me when not found returns 404")
    void getMyProfile_notFound_returns404() throws Exception {
        when(getStudentProfileUseCase.getStudentProfile(USER_ID))
                .thenThrow(new StudentProfileNotFoundException("Not found"));

        mockMvc.perform(get("/api/v1/student-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_PROFILE_NOT_FOUND")));
    }

    @Test
    @DisplayName("PATCH /student-profiles/me with empty body returns 400 VALIDATION_FAILED")
    void patchMyProfile_emptyBody_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("PATCH /student-profiles/me with null onboardingCompleted returns 400")
    void patchMyProfile_nullOnboardingCompleted_returns400() throws Exception {
        String json = """
                {
                    "onboardingCompleted": null
                }
                """;

        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("PATCH /student-profiles/me with explicit null resets nullable fields")
    void patchMyProfile_explicitNull_success() throws Exception {
        StudentProfile updated = new StudentProfile(
                USER_ID,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-20T12:00:00Z")
        );
        when(updateStudentProfileUseCase.updateStudentProfile(any(UpdateStudentProfileCommand.class)))
                .thenReturn(updated);

        String json = """
                {
                    "gender": null,
                    "dateOfBirth": null
                }
                """;

        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gender", nullValue()))
                .andExpect(jsonPath("$.dateOfBirth", nullValue()));

        ArgumentCaptor<UpdateStudentProfileCommand> captor = ArgumentCaptor.forClass(UpdateStudentProfileCommand.class);
        verify(updateStudentProfileUseCase).updateStudentProfile(captor.capture());
        assertThat(captor.getValue().gender().isNull()).isTrue();
        assertThat(captor.getValue().dateOfBirth().isNull()).isTrue();
        assertThat(captor.getValue().trainingExperienceLevel().isSpecified()).isFalse();
    }

    @Test
    @DisplayName("GET /student-profiles/me when capability unavailable returns 403 STUDENT_CAPABILITY_UNAVAILABLE")
    void get_whenCapabilityUnavailable_returns403() throws Exception {
        when(getStudentProfileUseCase.getStudentProfile(USER_ID))
                .thenThrow(new com.fitnesscoaching.platform.common.exception.StudentCapabilityUnavailableException("Student capability is not active"));

        mockMvc.perform(get("/api/v1/student-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));
    }

    @Test
    @DisplayName("PATCH /student-profiles/me when capability unavailable returns 403 STUDENT_CAPABILITY_UNAVAILABLE")
    void patch_whenCapabilityUnavailable_returns403() throws Exception {
        when(updateStudentProfileUseCase.updateStudentProfile(any(UpdateStudentProfileCommand.class)))
                .thenThrow(new com.fitnesscoaching.platform.common.exception.StudentCapabilityUnavailableException("Student capability is not active"));

        mockMvc.perform(patch("/api/v1/student-profiles/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableDaysPerWeek\": 4}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("STUDENT_CAPABILITY_UNAVAILABLE")));
    }
}
