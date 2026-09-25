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
import com.fitnesscoaching.platform.common.exception.GoalLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.GoalTransitionNotFoundException;
import com.fitnesscoaching.platform.common.exception.GoalVersionConflictException;
import com.fitnesscoaching.platform.common.exception.GoalVersionNoChangesException;
import com.fitnesscoaching.platform.common.exception.GoalVersionNotFoundException;
import com.fitnesscoaching.platform.common.exception.NewGoalJourneyRequiredException;
import com.fitnesscoaching.platform.common.exception.SameGoalJourneyTransitionException;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalTransitionResult;
import com.fitnesscoaching.platform.modules.goal.domain.GoalTransition;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.GoalVersionPageResponse;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.GoalVersionResponse;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalVersionPage;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTransitionUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalVersionUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetFitnessGoalDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionsUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionsUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.PauseFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ResumeFitnessGoalUseCase;
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
    private GetGoalVersionsUseCase getGoalVersionsUseCase;

    @MockitoBean
    private GetGoalVersionDetailUseCase getGoalVersionDetailUseCase;

    @MockitoBean
    private CreateGoalVersionUseCase createGoalVersionUseCase;

    @MockitoBean
    private CreateGoalTransitionUseCase createGoalTransitionUseCase;

    @MockitoBean
    private GetGoalTransitionsUseCase getGoalTransitionsUseCase;

    @MockitoBean
    private GetGoalTransitionDetailUseCase getGoalTransitionDetailUseCase;

    @MockitoBean
    private PauseFitnessGoalUseCase pauseFitnessGoalUseCase;

    @MockitoBean
    private ResumeFitnessGoalUseCase resumeFitnessGoalUseCase;

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

    @Test
    @DisplayName("GET /api/v1/fitness-goals/{goalId}/versions - returns 200 with versions page")
    void getGoalVersions_success_returns200() throws Exception {
        UUID versionId = UUID.randomUUID();
        FitnessGoalVersion domainVersion = new FitnessGoalVersion(
                versionId, goalId, 1, "Goal V1", LocalDate.now(), LocalDate.now().plusDays(90), 90,
                Instant.now(), null, null, "INITIAL", null, studentId, null,
                Instant.now(), null, null, null, List.of(), List.of()
        );
        GoalVersionPage pageModel = new GoalVersionPage(
                List.of(domainVersion), 0, 20, 1L, 1
        );
        when(getGoalVersionsUseCase.getGoalVersions(any())).thenReturn(pageModel);

        mockMvc.perform(get("/api/v1/fitness-goals/" + goalId + "/versions")
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id", is(versionId.toString())))
                .andExpect(jsonPath("$.items[0].versionNumber", is(1)))
                .andExpect(jsonPath("$.items[0].isCurrent", is(true)))
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.totalPages", is(1)));
    }

    @Test
    @DisplayName("GET /api/v1/fitness-goals/{goalId}/versions/{versionId} - returns 200 with version detail")
    void getGoalVersionDetail_success_returns200() throws Exception {
        UUID versionId = UUID.randomUUID();
        FitnessGoalVersion domainVersion = new FitnessGoalVersion(
                versionId, goalId, 2, "Goal V2", LocalDate.now(), LocalDate.now().plusDays(120), 120,
                Instant.now(), null, null, "Extended timeline", null, studentId, null,
                Instant.now(), Instant.now(), studentId, null, List.of(), List.of()
        );
        when(getGoalVersionDetailUseCase.getGoalVersionDetail(any())).thenReturn(domainVersion);

        mockMvc.perform(get("/api/v1/fitness-goals/" + goalId + "/versions/" + versionId)
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(versionId.toString())))
                .andExpect(jsonPath("$.versionNumber", is(2)))
                .andExpect(jsonPath("$.isCurrent", is(true)))
                .andExpect(jsonPath("$.durationDays", is(120)));
    }

    @Test
    @DisplayName("GET /api/v1/fitness-goals/{goalId}/versions/{versionId} - returns 404 when version not found")
    void getGoalVersionDetail_notFound_returns404() throws Exception {
        UUID versionId = UUID.randomUUID();
        when(getGoalVersionDetailUseCase.getGoalVersionDetail(any()))
                .thenThrow(new GoalVersionNotFoundException("Goal version not found: " + versionId));

        mockMvc.perform(get("/api/v1/fitness-goals/" + goalId + "/versions/" + versionId)
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("GOAL_VERSION_NOT_FOUND")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/versions - returns 201 on success")
    void createGoalVersion_success_returns201() throws Exception {
        UUID versionId = UUID.randomUUID();
        FitnessGoalVersion created = new FitnessGoalVersion(
                versionId, goalId, 2, "Revised Goal", LocalDate.now(), LocalDate.now().plusDays(120), 120,
                Instant.now(), null, null, "Extending program", null, studentId, null,
                Instant.now(), Instant.now(), studentId, null, List.of(), List.of()
        );
        when(createGoalVersionUseCase.createGoalVersion(any())).thenReturn(created);

        String json = """
                {
                    "title": "Revised Goal",
                    "durationDays": 120,
                    "changeReason": "Extending program",
                    "objectives": [
                        {
                            "goalTypeCode": "MUSCLE_GAIN",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/fitness-goals/" + goalId + "/versions/" + versionId))
                .andExpect(jsonPath("$.id", is(versionId.toString())))
                .andExpect(jsonPath("$.versionNumber", is(2)))
                .andExpect(jsonPath("$.isCurrent", is(true)));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/versions - returns 400 when missing changeReason")
    void createGoalVersion_missingReason_returns400() throws Exception {
        String json = """
                {
                    "title": "Revised Goal",
                    "objectives": [
                        {
                            "goalTypeCode": "MUSCLE_GAIN",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/versions - returns 409 on version conflict")
    void createGoalVersion_conflict_returns409() throws Exception {
        when(createGoalVersionUseCase.createGoalVersion(any()))
                .thenThrow(new GoalVersionConflictException("Current goal version has changed; unable to create version"));

        String json = """
                {
                    "changeReason": "Concurrent update test",
                    "objectives": [
                        {
                            "goalTypeCode": "MUSCLE_GAIN",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_VERSION_CONFLICT")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/versions - returns 409 on new journey required")
    void createGoalVersion_newJourneyRequired_returns409() throws Exception {
        when(createGoalVersionUseCase.createGoalVersion(any()))
                .thenThrow(new NewGoalJourneyRequiredException("Primary goal type cannot be changed within the same journey; a new goal and goal transition is required"));

        String json = """
                {
                    "changeReason": "Switch to fat loss",
                    "objectives": [
                        {
                            "goalTypeCode": "FAT_LOSS",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("NEW_GOAL_JOURNEY_REQUIRED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/versions - returns 409 on no changes")
    void createGoalVersion_noChanges_returns409() throws Exception {
        when(createGoalVersionUseCase.createGoalVersion(any()))
                .thenThrow(new GoalVersionNoChangesException("No changes detected in goal version"));

        String json = """
                {
                    "changeReason": "Duplicate version",
                    "objectives": [
                        {
                            "goalTypeCode": "MUSCLE_GAIN",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_VERSION_NO_CHANGES")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/versions - returns 400 when changeReason exceeds 100 characters")
    void createGoalVersion_reasonExceeds100Chars_returns400() throws Exception {
        String longReason = "A".repeat(101);
        String json = """
                {
                    "changeReason": "%s",
                    "objectives": [
                        {
                            "goalTypeCode": "MUSCLE_GAIN",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """.formatted(longReason);

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/versions")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/transitions - success creates transition and returns 201 Created")
    void createGoalTransition_success_returns201() throws Exception {
        UUID newGoalId = UUID.randomUUID();
        UUID transitionId = UUID.randomUUID();
        GoalTransition transition = new GoalTransition(
                transitionId, goalId, newGoalId, "New journey: Fat Loss", null, studentId, Instant.now(), "Notes"
        );
        FitnessGoal newGoal = buildDummyGoal(GoalStatus.ACTIVE);
        GoalTransitionResult result = new GoalTransitionResult(transition, newGoal);

        when(createGoalTransitionUseCase.createGoalTransition(any())).thenReturn(result);

        String json = """
                {
                    "title": "Fat Loss Journey",
                    "startDate": "2026-10-01",
                    "targetDate": "2026-12-31",
                    "durationDays": 91,
                    "transitionReason": "Switching to Fat Loss",
                    "notes": "Transition notes",
                    "objectives": [
                        {
                            "goalTypeCode": "FAT_LOSS",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/transitions")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", is("/api/v1/fitness-goals/" + goalId + "/transitions/" + transitionId)))
                .andExpect(jsonPath("$.id", is(transitionId.toString())))
                .andExpect(jsonPath("$.previousGoalId", is(goalId.toString())))
                .andExpect(jsonPath("$.newGoalId", is(newGoalId.toString())))
                .andExpect(jsonPath("$.transitionReason", is("New journey: Fat Loss")))
                .andExpect(jsonPath("$.newGoal", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/transitions - same primary goal type throws 409 SAME_GOAL_JOURNEY_NOT_PERMITTED")
    void createGoalTransition_samePrimaryType_returns409() throws Exception {
        when(createGoalTransitionUseCase.createGoalTransition(any()))
                .thenThrow(new SameGoalJourneyTransitionException("Cannot transition to a new journey with the same primary goal type; use goal versioning instead"));

        String json = """
                {
                    "title": "Muscle Gain Continuation",
                    "startDate": "2026-10-01",
                    "transitionReason": "Same journey transition attempt",
                    "objectives": [
                        {
                            "goalTypeCode": "MUSCLE_GAIN",
                            "priority": "PRIMARY"
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/transitions")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("SAME_GOAL_JOURNEY_NOT_PERMITTED")));
    }

    @Test
    @DisplayName("GET /api/v1/fitness-goals/{goalId}/transitions - returns 200 OK with transitions list")
    void getGoalTransitions_success_returns200() throws Exception {
        UUID transitionId = UUID.randomUUID();
        GoalTransition transition = new GoalTransition(
                transitionId, goalId, UUID.randomUUID(), "Transition to Fat Loss", null, studentId, Instant.now(), null
        );

        when(getGoalTransitionsUseCase.getGoalTransitions(any())).thenReturn(List.of(transition));

        mockMvc.perform(get("/api/v1/fitness-goals/" + goalId + "/transitions")
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", is(transitionId.toString())))
                .andExpect(jsonPath("$[0].previousGoalId", is(goalId.toString())));
    }

    @Test
    @DisplayName("GET /api/v1/fitness-goals/{goalId}/transitions/{transitionId} - returns 200 OK")
    void getGoalTransitionDetail_success_returns200() throws Exception {
        UUID transitionId = UUID.randomUUID();
        GoalTransition transition = new GoalTransition(
                transitionId, goalId, UUID.randomUUID(), "Transition to Fat Loss", null, studentId, Instant.now(), "Notes"
        );

        when(getGoalTransitionDetailUseCase.getGoalTransitionDetail(any())).thenReturn(transition);

        mockMvc.perform(get("/api/v1/fitness-goals/" + goalId + "/transitions/" + transitionId)
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(transitionId.toString())))
                .andExpect(jsonPath("$.transitionReason", is("Transition to Fat Loss")));
    }

    @Test
    @DisplayName("GET /api/v1/fitness-goals/{goalId}/transitions/{transitionId} - not found returns 404")
    void getGoalTransitionDetail_notFound_returns404() throws Exception {
        UUID transitionId = UUID.randomUUID();
        when(getGoalTransitionDetailUseCase.getGoalTransitionDetail(any()))
                .thenThrow(new GoalTransitionNotFoundException("Goal transition not found: " + transitionId));

        mockMvc.perform(get("/api/v1/fitness-goals/" + goalId + "/transitions/" + transitionId)
                        .with(jwt().jwt(b -> b.subject(studentId.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("GOAL_TRANSITION_NOT_FOUND")));
    }

    // ==========================================
    // GOAL-05: Pause and Resume Unit Tests
    // ==========================================

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/pause - success returns 200 and PAUSED status")
    void pauseGoal_success_returns200() throws Exception {
        FitnessGoal pausedGoal = buildDummyGoal(GoalStatus.PAUSED);
        when(pauseFitnessGoalUseCase.pauseFitnessGoal(any())).thenReturn(pausedGoal);

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Injury recovery\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(goalId.toString())))
                .andExpect(jsonPath("$.status", is("PAUSED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/resume - success returns 200 and ACTIVE status")
    void resumeGoal_success_returns200() throws Exception {
        FitnessGoal resumedGoal = buildDummyGoal(GoalStatus.ACTIVE);
        when(resumeFitnessGoalUseCase.resumeFitnessGoal(any())).thenReturn(resumedGoal);

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Recovered and cleared to train\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(goalId.toString())))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/pause - unauthenticated returns 401")
    void pauseGoal_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Injury recovery\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/resume - unauthenticated returns 401")
    void resumeGoal_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Recovered and cleared to train\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/pause - blank reason returns 400")
    void pauseGoal_blankReason_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/resume - blank reason returns 400")
    void resumeGoal_blankReason_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/pause - reason exceeding 1000 characters returns 400")
    void pauseGoal_reasonExceeds1000_returns400() throws Exception {
        String longReason = "A".repeat(1001);
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + longReason + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/resume - reason exceeding 1000 characters returns 400")
    void resumeGoal_reasonExceeds1000_returns400() throws Exception {
        String longReason = "A".repeat(1001);
        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + longReason + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/pause - goal not found returns 404")
    void pauseGoal_notFound_returns404() throws Exception {
        when(pauseFitnessGoalUseCase.pauseFitnessGoal(any()))
                .thenThrow(new FitnessGoalNotFoundException("Fitness goal not found: " + goalId));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Injury recovery\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("FITNESS_GOAL_NOT_FOUND")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/resume - goal not found returns 404")
    void resumeGoal_notFound_returns404() throws Exception {
        when(resumeFitnessGoalUseCase.resumeFitnessGoal(any()))
                .thenThrow(new FitnessGoalNotFoundException("Fitness goal not found: " + goalId));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Ready to train\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("FITNESS_GOAL_NOT_FOUND")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/pause - access denied for non-owner returns 403")
    void pauseGoal_accessDenied_returns403() throws Exception {
        when(pauseFitnessGoalUseCase.pauseFitnessGoal(any()))
                .thenThrow(new FitnessGoalAccessDeniedException("Access denied to fitness goal: " + goalId));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Injury recovery\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/resume - access denied for non-owner returns 403")
    void resumeGoal_accessDenied_returns403() throws Exception {
        when(resumeFitnessGoalUseCase.resumeFitnessGoal(any()))
                .thenThrow(new FitnessGoalAccessDeniedException("Access denied to fitness goal: " + goalId));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Ready to train\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/pause - goal not ACTIVE returns 409 GOAL_LIFECYCLE_CONFLICT")
    void pauseGoal_conflictNotActive_returns409() throws Exception {
        when(pauseFitnessGoalUseCase.pauseFitnessGoal(any()))
                .thenThrow(new GoalLifecycleConflictException("Cannot pause fitness goal: goal is not in ACTIVE status. Current status: DRAFT"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/pause")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Injury recovery\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_LIFECYCLE_CONFLICT")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/resume - goal not PAUSED returns 409 GOAL_LIFECYCLE_CONFLICT")
    void resumeGoal_conflictNotPaused_returns409() throws Exception {
        when(resumeFitnessGoalUseCase.resumeFitnessGoal(any()))
                .thenThrow(new GoalLifecycleConflictException("Cannot resume fitness goal: goal is not in PAUSED status. Current status: ACTIVE"));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Ready to train\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("GOAL_LIFECYCLE_CONFLICT")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/resume - active goal already exists returns 409 ACTIVE_FITNESS_GOAL_ALREADY_EXISTS")
    void resumeGoal_activeGoalAlreadyExists_returns409() throws Exception {
        when(resumeFitnessGoalUseCase.resumeFitnessGoal(any()))
                .thenThrow(new ActiveFitnessGoalAlreadyExistsException("Student already has an active fitness goal."));

        mockMvc.perform(post("/api/v1/fitness-goals/" + goalId + "/resume")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Ready to train\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("ACTIVE_FITNESS_GOAL_ALREADY_EXISTS")));
    }
}
