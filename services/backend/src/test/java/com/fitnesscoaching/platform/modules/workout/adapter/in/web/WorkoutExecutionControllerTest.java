package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutExecutionViews;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecution;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionStatus;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = WorkoutExecutionController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        ClockConfig.class, GlobalExceptionHandler.class, WorkoutPlanErrorAdvice.class,
        WorkoutExecutionErrorAdvice.class, WorkoutExecutionWebMapper.class, RequestIdFilter.class,
        JacksonConfig.class})
class WorkoutExecutionControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean WorkoutExecutionQueryUseCase queries;
    @MockitoBean WorkoutExecutionCommandUseCase commands;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    @Test
    void startReturnsFullPersistedDetailAndStableLocationForReplay() throws Exception {
        UUID actor = UUID.randomUUID(), occurrence = UUID.randomUUID(), execution = UUID.randomUUID();
        WorkoutExecution aggregate = aggregate(execution, actor);
        when(commands.start(any())).thenReturn(aggregate);
        when(queries.detail(actor, execution)).thenReturn(detail(execution, actor, "FROZEN"));
        byte[] body = json.writeValueAsBytes(Map.of("expectedOccurrenceVersion", 3, "commandKey", "start-1"));

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/planned-workouts/{occurrenceId}/executions", occurrence)
                            .with(jwt().jwt(j -> j.subject(actor.toString())))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/workout-executions/" + execution))
                    .andExpect(jsonPath("$.execution.executionId").value(execution.toString()))
                    .andExpect(jsonPath("$.execution.snapshotMode").value("FROZEN"))
                    .andExpect(jsonPath("$.exercises").isArray());
        }
        verify(commands, times(2)).start(any());
        verify(queries, times(2)).detail(actor, execution);
    }

    @Test
    void commandKeyBoundaryAccepts120AndRejects121BeforeCommand() throws Exception {
        UUID actor = UUID.randomUUID(), occurrence = UUID.randomUUID(), execution = UUID.randomUUID();
        when(commands.start(any())).thenReturn(aggregate(execution, actor));
        when(queries.detail(actor, execution)).thenReturn(detail(execution, actor, "FROZEN"));

        mvc.perform(post("/api/v1/planned-workouts/{id}/executions", occurrence)
                        .with(jwt().jwt(j -> j.subject(actor.toString()))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("expectedOccurrenceVersion", 0,
                                "commandKey", "k".repeat(120)))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/planned-workouts/{id}/executions", occurrence)
                        .with(jwt().jwt(j -> j.subject(actor.toString()))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("expectedOccurrenceVersion", 0,
                                "commandKey", "k".repeat(121)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("commandKey"));
        verify(commands, times(1)).start(any());
    }

    @ParameterizedTest
    @MethodSource("startErrors")
    void startMapsStableErrorsWithoutLeakingInternalMessage(WorkoutExecutionError error, int statusCode)
            throws Exception {
        UUID actor = UUID.randomUUID(), occurrence = UUID.randomUUID();
        when(commands.start(any())).thenThrow(new WorkoutExecutionFailure(error, "sql constraint secret"));

        mvc.perform(post("/api/v1/planned-workouts/{id}/executions", occurrence)
                        .with(jwt().jwt(j -> j.subject(actor.toString()))).header("X-Request-ID", "api-test-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("expectedOccurrenceVersion", 0,
                                "commandKey", "start-error"))))
                .andExpect(status().is(statusCode))
                .andExpect(header().string("X-Request-ID", "api-test-1"))
                .andExpect(jsonPath("$.errorCode").value(error.name()))
                .andExpect(jsonPath("$.requestId").value("api-test-1"))
                .andExpect(jsonPath("$.message").value("Workout execution operation could not be completed"));
    }

    @Test
    void plannedOccurrenceNotFoundUsesExistingStableWorkoutPlanAdvice() throws Exception {
        UUID actor = UUID.randomUUID(), occurrence = UUID.randomUUID();
        when(commands.start(any())).thenThrow(new WorkoutPlanFailure(
                WorkoutPlanError.PLANNED_WORKOUT_NOT_FOUND, "internal"));

        mvc.perform(post("/api/v1/planned-workouts/{id}/executions", occurrence)
                        .with(jwt().jwt(j -> j.subject(actor.toString()))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("expectedOccurrenceVersion", 0,
                                "commandKey", "start-not-found"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PLANNED_WORKOUT_NOT_FOUND"));
    }

    @Test
    void skipReturnsApprovedShapeAndDoesNotReadExecution() throws Exception {
        UUID actor = UUID.randomUUID(), occurrence = UUID.randomUUID();
        when(commands.skip(any())).thenReturn(new WorkoutExecutionCommandUseCase.PlannedResult(
                occurrence, "SKIPPED", 4, true));

        mvc.perform(post("/api/v1/planned-workouts/{id}/skip", occurrence)
                        .with(jwt().jwt(j -> j.subject(actor.toString()))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("expectedOccurrenceVersion", 3,
                                "commandKey", "skip-1", "reason", "Rest"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrenceId").value(occurrence.toString()))
                .andExpect(jsonPath("$.status").value("SKIPPED"))
                .andExpect(jsonPath("$.occurrenceVersion").value(4))
                .andExpect(jsonPath("$.replayed").value(true));
        verifyNoInteractions(queries);
    }

    @Test
    void currentIsActorScopedWithoutStudentQueryParameter() throws Exception {
        UUID actor = UUID.randomUUID(), execution = UUID.randomUUID();
        when(queries.current(actor)).thenReturn(detail(execution, actor, "FROZEN"));

        mvc.perform(get("/api/v1/workout-executions/current")
                        .with(jwt().jwt(j -> j.subject(actor.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.studentId").value(actor.toString()));
        verify(queries).current(actor);
    }

    @Test
    void historyDefaultsTargetToActorAndMapsOnlySummaries() throws Exception {
        UUID actor = UUID.randomUUID(), execution = UUID.randomUUID();
        when(queries.history(actor, actor, 0, 20)).thenReturn(new WorkoutExecutionViews.Page(
                List.of(detail(execution, actor, "LEGACY_REFERENCE_ONLY")), 0, 20, 1, 1));

        mvc.perform(get("/api/v1/workout-executions")
                        .with(jwt().jwt(j -> j.subject(actor.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].executionId").value(execution.toString()))
                .andExpect(jsonPath("$.items[0].snapshotMode").value("LEGACY_REFERENCE_ONLY"))
                .andExpect(jsonPath("$.items[0].planId").doesNotExist())
                .andExpect(jsonPath("$.items[0].exercises").doesNotExist())
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void trainerHistoryTargetAndEmptyConcealedPageArePassedThrough() throws Exception {
        UUID trainer = UUID.randomUUID(), student = UUID.randomUUID();
        when(queries.history(trainer, student, 2, 5)).thenReturn(
                new WorkoutExecutionViews.Page(List.of(), 2, 5, 0, 0));

        mvc.perform(get("/api/v1/workout-executions").param("studentId", student.toString())
                        .param("page", "2").param("size", "5")
                        .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(0));
        verify(queries).history(trainer, student, 2, 5);
    }

    @Test
    void historyRejectsInvalidPageAndSizeBeforeQuery() throws Exception {
        UUID actor = UUID.randomUUID();
        mvc.perform(get("/api/v1/workout-executions").param("page", "-1").param("size", "101")
                        .with(jwt().jwt(j -> j.subject(actor.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        verifyNoInteractions(queries);
    }

    @Test
    void legacyDetailSerializesUnknownEvidenceAsNull() throws Exception {
        UUID actor = UUID.randomUUID(), execution = UUID.randomUUID();
        when(queries.detail(actor, execution)).thenReturn(detail(execution, actor, "LEGACY_REFERENCE_ONLY"));

        mvc.perform(get("/api/v1/workout-executions/{id}", execution)
                        .with(jwt().jwt(j -> j.subject(actor.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.planId").doesNotExist())
                .andExpect(jsonPath("$.execution.snapshotFrozenAt").doesNotExist())
                .andExpect(jsonPath("$.exercises").isArray());
    }

    @Test
    void setUpsertUsesPathClientIdentityAndReturnsRefreshedDetail() throws Exception {
        UUID actor = UUID.randomUUID(), execution = UUID.randomUUID(), exercise = UUID.randomUUID();
        UUID clientSet = UUID.randomUUID();
        when(commands.upsertSet(any())).thenReturn(aggregate(execution, actor));
        when(queries.detail(actor, execution)).thenReturn(detail(execution, actor, "FROZEN"));
        Map<String, Object> body = Map.of("expectedVersion", 2, "exerciseExecutionId", exercise,
                "baselineSetNumber", 1, "setNumber", 1, "setType", "WORKING",
                "completionStatus", "COMPLETED", "repetitions", 8);

        mvc.perform(put("/api/v1/workout-executions/{execution}/sets/{set}", execution, clientSet)
                        .with(jwt().jwt(j -> j.subject(actor.toString()))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.executionId").value(execution.toString()));
        var command = org.mockito.ArgumentCaptor.forClass(WorkoutExecutionCommandUseCase.UpsertSetCommand.class);
        verify(commands).upsertSet(command.capture());
        assertThat(command.getValue().set().clientSetId()).isEqualTo(clientSet);
        assertThat(command.getValue().exerciseExecutionId()).isEqualTo(exercise);
    }

    @Test
    void invalidSetRangeIsRejectedBeforeCommand() throws Exception {
        UUID actor = UUID.randomUUID(), execution = UUID.randomUUID(), clientSet = UUID.randomUUID();
        Map<String, Object> body = Map.of("expectedVersion", 0, "exerciseExecutionId", UUID.randomUUID(),
                "setNumber", 1, "setType", "WORKING", "completionStatus", "COMPLETED", "rpe", 11);

        mvc.perform(put("/api/v1/workout-executions/{execution}/sets/{set}", execution, clientSet)
                        .with(jwt().jwt(j -> j.subject(actor.toString()))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        verify(commands, never()).upsertSet(any());
    }

    @Test
    void substitutionExposesOnlyActualVariationMutation() throws Exception {
        UUID actor = UUID.randomUUID(), execution = UUID.randomUUID(), exercise = UUID.randomUUID();
        UUID actual = UUID.randomUUID();
        when(commands.substitute(any())).thenReturn(aggregate(execution, actor));
        when(queries.detail(actor, execution)).thenReturn(detail(execution, actor, "FROZEN"));

        mvc.perform(put("/api/v1/workout-executions/{execution}/exercises/{exercise}", execution, exercise)
                        .with(jwt().jwt(j -> j.subject(actor.toString()))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("expectedVersion", 2,
                                "actualExerciseVariationId", actual, "substitutionReason", "Equipment"))))
                .andExpect(status().isOk());
        var command = org.mockito.ArgumentCaptor.forClass(
                WorkoutExecutionCommandUseCase.SubstituteExerciseCommand.class);
        verify(commands).substitute(command.capture());
        assertThat(command.getValue().actualVariationId()).isEqualTo(actual);
    }

    @Test
    void completeAndAbortDoNotAcceptClientTerminalStatusAndReturnRefreshedDetail() throws Exception {
        UUID actor = UUID.randomUUID(), execution = UUID.randomUUID();
        when(commands.complete(any())).thenReturn(aggregate(execution, actor));
        when(commands.abort(any())).thenReturn(aggregate(execution, actor));
        when(queries.detail(actor, execution)).thenReturn(detail(execution, actor, "FROZEN"));
        Map<String, Object> body = Map.of("expectedVersion", 2, "commandKey", "terminal-1", "overallRpe", 8);

        mvc.perform(post("/api/v1/workout-executions/{id}/complete", execution)
                        .with(jwt().jwt(j -> j.subject(actor.toString()))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/workout-executions/{id}/abort", execution)
                        .with(jwt().jwt(j -> j.subject(actor.toString()))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body))).andExpect(status().isOk());
        verify(commands).complete(any());
        verify(commands).abort(any());
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> startErrors() {
        return Stream.of(
                error(WorkoutExecutionError.VALIDATION_FAILED, 400),
                error(WorkoutExecutionError.WORKOUT_EXECUTION_ACCESS_DENIED, 403),
                error(WorkoutExecutionError.WORKOUT_EXECUTION_NOT_FOUND, 404),
                error(WorkoutExecutionError.ACTIVE_WORKOUT_EXECUTION_EXISTS, 409),
                error(WorkoutExecutionError.PLANNED_WORKOUT_ALREADY_STARTED, 409),
                error(WorkoutExecutionError.PLANNED_WORKOUT_NOT_STARTABLE, 409),
                error(WorkoutExecutionError.WORKOUT_EXECUTION_BASELINE_INVALID, 409),
                error(WorkoutExecutionError.WORKOUT_EXECUTION_VERSION_CONFLICT, 409),
                error(WorkoutExecutionError.WORKOUT_EXECUTION_IDEMPOTENCY_CONFLICT, 409));
    }

    private static org.junit.jupiter.params.provider.Arguments error(WorkoutExecutionError error, int status) {
        return org.junit.jupiter.params.provider.Arguments.of(error, status);
    }

    private static WorkoutExecution aggregate(UUID execution, UUID student) {
        return new WorkoutExecution(execution, student, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, 1L, "FROZEN", Instant.EPOCH, null, WorkoutExecutionStatus.IN_PROGRESS,
                null, null, 0, List.of());
    }

    private static WorkoutExecutionViews.Detail detail(UUID execution, UUID student, String mode) {
        boolean legacy = "LEGACY_REFERENCE_ONLY".equals(mode);
        return new WorkoutExecutionViews.Detail(execution, student, legacy ? null : UUID.randomUUID(),
                legacy ? null : UUID.randomUUID(), legacy ? null : UUID.randomUUID(),
                legacy ? null : UUID.randomUUID(), null, mode, Instant.EPOCH, null,
                WorkoutExecutionStatus.IN_PROGRESS, null, null, 0, legacy ? null : Instant.EPOCH,
                legacy ? null : 1L, legacy ? null : Instant.EPOCH, legacy ? null : Instant.EPOCH,
                null, legacy ? null : "SELF_PERFORMABLE", List.of());
    }
}
