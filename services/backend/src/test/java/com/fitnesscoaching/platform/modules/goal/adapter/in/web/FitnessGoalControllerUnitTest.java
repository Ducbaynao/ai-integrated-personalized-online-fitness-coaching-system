package com.fitnesscoaching.platform.modules.goal.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.ActiveFitnessGoalAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.exception.FitnessGoalAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.FitnessGoalNotFoundException;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetFitnessGoalDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.goal.domain.GoalObjective;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
import com.fitnesscoaching.platform.modules.goal.domain.ObjectivePriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FitnessGoalController.class)
@Import({
        SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ClockConfig.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        JacksonConfig.class
})
class FitnessGoalControllerUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreateFitnessGoalUseCase createFitnessGoalUseCase;

    @MockitoBean
    private GetFitnessGoalDetailUseCase getFitnessGoalDetailUseCase;

    @MockitoBean
    private GetCurrentFitnessGoalUseCase getCurrentFitnessGoalUseCase;

    @MockitoBean
    private ActivateFitnessGoalUseCase activateFitnessGoalUseCase;

    @MockitoBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    private final UUID studentId = UUID.randomUUID();
    private final UUID goalId = UUID.randomUUID();

    private FitnessGoal buildDummyGoal(GoalStatus status) {
        GoalObjective obj = new GoalObjective(
                UUID.randomUUID(), null, (short) 1, "MUSCLE_GAIN", "Muscle Gain",
                ObjectivePriority.PRIMARY, 0, null
        );
        FitnessGoalVersion version = new FitnessGoalVersion(
                UUID.randomUUID(), goalId, 1, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 12, 31), 90, Instant.now(), null, null,
                "INITIAL_CREATION", null, studentId, null, Instant.now(),
                null, null, null, List.of(obj), List.of()
        );
        return new FitnessGoal(
                goalId, studentId, "Hypertrophy Goal", status, studentId,
                status == GoalStatus.ACTIVE ? Instant.now() : null,
                null, null, null, null, Instant.now(), Instant.now(), version
        );
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals - unauthenticated returns 401")
    void createGoal_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals - validation error on missing fields returns 400")
    void createGoal_validationFailed_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "",
                                  "startDate": null,
                                  "objectives": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals - timeline validation failure returns 400")
    void createGoal_timelineValidationFailure_returns400() throws Exception {
        when(createFitnessGoalUseCase.createFitnessGoal(any()))
                .thenThrow(new ApplicationValidationException(
                        "Target date must be strictly after start date",
                        List.of(new FieldErrorDto("targetDate", "InvalidRange", "Target date must be strictly after start date"))
                ));

        mockMvc.perform(post("/api/v1/fitness-goals")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Hypertrophy Goal",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2026-10-01",
                                  "objectives": [
                                    { "goalTypeCode": "MUSCLE_GAIN", "priority": "PRIMARY" }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("targetDate")))
                .andExpect(jsonPath("$.fieldErrors[0].code", is("InvalidRange")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals - success returns 201 with Location header")
    void createGoal_success_returns201() throws Exception {
        FitnessGoal dummy = buildDummyGoal(GoalStatus.DRAFT);
        when(createFitnessGoalUseCase.createFitnessGoal(any())).thenReturn(dummy);

        mockMvc.perform(post("/api/v1/fitness-goals")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Hypertrophy Goal",
                                  "startDate": "2026-10-01",
                                  "targetDate": "2026-12-31",
                                  "objectives": [
                                    {
                                      "goalTypeCode": "MUSCLE_GAIN",
                                      "priority": "PRIMARY"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/fitness-goals/" + dummy.id()))
                .andExpect(jsonPath("$.id", is(dummy.id().toString())))
                .andExpect(jsonPath("$.title", is("Hypertrophy Goal")))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andExpect(jsonPath("$.currentVersion.objectives[0].goalTypeCode", is("MUSCLE_GAIN")));
    }

    @Test
    @DisplayName("GET /api/v1/fitness-goals/me/current - returns 200 when active goal exists")
    void getCurrentGoal_success_returns200() throws Exception {
        FitnessGoal dummy = buildDummyGoal(GoalStatus.ACTIVE);
        when(getCurrentFitnessGoalUseCase.getCurrentFitnessGoal(studentId)).thenReturn(Optional.of(dummy));

        mockMvc.perform(get("/api/v1/fitness-goals/me/current")
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(dummy.id().toString())))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("GET /api/v1/fitness-goals/me/current - returns 404 when no active goal")
    void getCurrentGoal_notFound_returns404() throws Exception {
        when(getCurrentFitnessGoalUseCase.getCurrentFitnessGoal(studentId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/fitness-goals/me/current")
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("FITNESS_GOAL_NOT_FOUND")));
    }

    @Test
    @DisplayName("GET /api/v1/fitness-goals/{goalId} - returns 200 for owner")
    void getGoalDetail_success_returns200() throws Exception {
        FitnessGoal dummy = buildDummyGoal(GoalStatus.DRAFT);
        when(getFitnessGoalDetailUseCase.getFitnessGoalDetail(studentId, goalId)).thenReturn(dummy);

        mockMvc.perform(get("/api/v1/fitness-goals/" + goalId)
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(dummy.id().toString())));
    }

    @Test
    @DisplayName("GET /api/v1/fitness-goals/{goalId} - non-owner returns 403")
    void getGoalDetail_nonOwner_returns403() throws Exception {
        when(getFitnessGoalDetailUseCase.getFitnessGoalDetail(studentId, goalId))
                .thenThrow(new FitnessGoalAccessDeniedException("Access denied to fitness goal: " + goalId));

        mockMvc.perform(get("/api/v1/fitness-goals/" + goalId)
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/activate - returns 200 on activation")
    void activateGoal_success_returns200() throws Exception {
        FitnessGoal dummy = buildDummyGoal(GoalStatus.ACTIVE);
        when(activateFitnessGoalUseCase.activateFitnessGoal(any())).thenReturn(dummy);

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Ready to start\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/activate - returns 409 when active goal already exists")
    void activateGoal_activeAlreadyExists_returns409() throws Exception {
        when(activateFitnessGoalUseCase.activateFitnessGoal(any()))
                .thenThrow(new ActiveFitnessGoalAlreadyExistsException("Student already has an active fitness goal."));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("ACTIVE_FITNESS_GOAL_ALREADY_EXISTS")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/activate - returns 409 on invalid transition")
    void activateGoal_invalidTransition_returns409() throws Exception {
        when(activateFitnessGoalUseCase.activateFitnessGoal(any()))
                .thenThrow(new InvalidLifecycleTransitionException("Cannot activate fitness goal in status: ACTIVE"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/activate")
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_LIFECYCLE_TRANSITION")));
    }
}
