package com.fitnesscoaching.platform.modules.trainer.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationAlreadyDecidedException;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationNotFoundException;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.trainer.application.model.AdminTrainerApplicationPage;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.DecideTrainerApplicationUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationDetailUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationsUseCase;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminTrainerApplicationController.class)
@Import({
        SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ClockConfig.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        JacksonConfig.class
})
class AdminTrainerApplicationControllerUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GetAdminTrainerApplicationsUseCase getApplicationsUseCase;

    @MockitoBean
    private GetAdminTrainerApplicationDetailUseCase getApplicationDetailUseCase;

    @MockitoBean
    private DecideTrainerApplicationUseCase decideApplicationUseCase;

    @MockitoBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID APP_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TRAINER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private TrainerApplication sampleApplication(TrainerVerificationStatus status) {
        Instant now = Instant.parse("2026-09-23T12:00:00Z");
        return new TrainerApplication(
                APP_ID,
                TRAINER_ID,
                status,
                now.minusSeconds(7200),
                status != TrainerVerificationStatus.PENDING ? now : null,
                status != TrainerVerificationStatus.PENDING ? ADMIN_ID : null,
                status == TrainerVerificationStatus.REJECTED ? "Invalid doc" : null,
                "Internal review note",
                "Applicant note",
                List.of(UUID.randomUUID()),
                List.of(UUID.randomUUID()),
                now.minusSeconds(7200),
                now
        );
    }

    // --- Authentication & Role Authorization Tests ---

    @Test
    @DisplayName("GET /admin/trainer-applications: unauthenticated returns 401 UNAUTHORIZED")
    void getApplications_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/trainer-applications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("UNAUTHORIZED")));
    }

    @Test
    @DisplayName("GET /admin/trainer-applications: non-admin (role TRAINER) returns 403 ACCESS_DENIED")
    void getApplications_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/trainer-applications")
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("TRAINER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_TRAINER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
    }

    // --- GET /api/v1/admin/trainer-applications Tests ---

    @Test
    @DisplayName("GET /admin/trainer-applications: admin returns 200 OK with paginated list")
    void getApplications_admin_returns200() throws Exception {
        TrainerApplication app = sampleApplication(TrainerVerificationStatus.PENDING);
        AdminTrainerApplicationPage page = new AdminTrainerApplicationPage(List.of(app), 0, 20, 1L, 1);

        when(getApplicationsUseCase.getApplications(any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/trainer-applications")
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(APP_ID.toString())))
                .andExpect(jsonPath("$.items[0].status", is("PENDING")))
                .andExpect(jsonPath("$.items[0].reviewNotes", is("Internal review note")))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.totalItems", is(1)))
                .andExpect(jsonPath("$.totalPages", is(1)));
    }

    @Test
    @DisplayName("GET /admin/trainer-applications: invalid status param returns 400 VALIDATION_FAILED")
    void getApplications_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/trainer-applications")
                        .param("status", "INVALID_STATUS")
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("status")));
    }

    // --- GET /api/v1/admin/trainer-applications/{applicationId} Tests ---

    @Test
    @DisplayName("GET /admin/trainer-applications/{id}: admin returns 200 OK with detail")
    void getApplicationDetail_admin_returns200() throws Exception {
        TrainerApplication app = sampleApplication(TrainerVerificationStatus.PENDING);
        when(getApplicationDetailUseCase.getApplicationDetail(any(), any())).thenReturn(app);

        mockMvc.perform(get("/api/v1/admin/trainer-applications/" + APP_ID)
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(APP_ID.toString())))
                .andExpect(jsonPath("$.trainerId", is(TRAINER_ID.toString())))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.applicantNote", is("Applicant note")))
                .andExpect(jsonPath("$.reviewNotes", is("Internal review note")));
    }

    @Test
    @DisplayName("GET /admin/trainer-applications/{id}: not found returns 404 TRAINER_APPLICATION_NOT_FOUND")
    void getApplicationDetail_notFound_returns404() throws Exception {
        when(getApplicationDetailUseCase.getApplicationDetail(any(), any()))
                .thenThrow(new TrainerApplicationNotFoundException("Not found"));

        mockMvc.perform(get("/api/v1/admin/trainer-applications/" + APP_ID)
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_APPLICATION_NOT_FOUND")));
    }

    // --- POST /api/v1/admin/trainer-applications/{applicationId}/decisions Tests ---

    @Test
    @DisplayName("POST /admin/trainer-applications/{id}/decisions: valid APPROVE returns 200 OK")
    void decide_approve_returns200() throws Exception {
        TrainerApplication verified = sampleApplication(TrainerVerificationStatus.VERIFIED);
        when(decideApplicationUseCase.decideApplication(any())).thenReturn(verified);

        String body = """
                {
                    "decision": "APPROVE",
                    "reviewNotes": "All certificates check out."
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + APP_ID + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(APP_ID.toString())))
                .andExpect(jsonPath("$.status", is("VERIFIED")));
    }

    @Test
    @DisplayName("POST /admin/trainer-applications/{id}/decisions: valid REJECT returns 200 OK")
    void decide_reject_returns200() throws Exception {
        TrainerApplication rejected = sampleApplication(TrainerVerificationStatus.REJECTED);
        when(decideApplicationUseCase.decideApplication(any())).thenReturn(rejected);

        String body = """
                {
                    "decision": "REJECT",
                    "rejectionReason": "Certificate expired.",
                    "reviewNotes": "Contacted issuer, certificate not valid."
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + APP_ID + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(APP_ID.toString())))
                .andExpect(jsonPath("$.status", is("REJECTED")));
    }

    @Test
    @DisplayName("POST /admin/trainer-applications/{id}/decisions: blank decision returns 400 VALIDATION_FAILED")
    void decide_blankDecision_returns400() throws Exception {
        String body = """
                {
                    "decision": ""
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + APP_ID + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /admin/trainer-applications/{id}/decisions: unknown property rejected with 400 VALIDATION_FAILED")
    void decide_unknownProperty_returns400() throws Exception {
        String body = """
                {
                    "decision": "APPROVE",
                    "unknownField": "unexpected"
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + APP_ID + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /admin/trainer-applications/{id}/decisions: not pending returns 409 TRAINER_APPLICATION_ALREADY_DECIDED")
    void decide_alreadyDecided_returns409() throws Exception {
        when(decideApplicationUseCase.decideApplication(any()))
                .thenThrow(new TrainerApplicationAlreadyDecidedException("Already decided"));

        String body = """
                {
                    "decision": "APPROVE"
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + APP_ID + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("TRAINER_APPLICATION_ALREADY_DECIDED")));
    }

    @Test
    @DisplayName("POST /admin/trainer-applications/{id}/decisions: admin inactive in DB returns 403 ACCOUNT_UNAVAILABLE")
    void decide_adminInactive_returns403() throws Exception {
        when(decideApplicationUseCase.decideApplication(any()))
                .thenThrow(new AccountUnavailableException("ACCOUNT_UNAVAILABLE: Account not active"));

        String body = """
                {
                    "decision": "APPROVE"
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + APP_ID + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }

    @Test
    @DisplayName("GET /admin/trainer-applications/{id}: malformed UUID returns 400 VALIDATION_FAILED")
    void getDetail_malformedUuid_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/trainer-applications/not-a-valid-uuid")
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("GET /admin/trainer-applications: large page overflow parameter returns 400 VALIDATION_FAILED")
    void getApplications_overflowPageParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/trainer-applications?page=99999999999999999999999")
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /admin/trainer-applications/{id}/decisions: profile not PENDING returns 409 INVALID_LIFECYCLE_TRANSITION")
    void decide_profileNotPending_returns409() throws Exception {
        when(decideApplicationUseCase.decideApplication(any()))
                .thenThrow(new InvalidLifecycleTransitionException("Trainer profile is not in PENDING state"));

        String body = """
                {
                    "decision": "APPROVE"
                }
                """;

        mockMvc.perform(post("/api/v1/admin/trainer-applications/" + APP_ID + "/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));
    }
}
