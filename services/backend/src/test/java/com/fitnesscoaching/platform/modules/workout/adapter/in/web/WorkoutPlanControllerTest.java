package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import org.junit.jupiter.api.Test;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WorkoutPlanController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        ClockConfig.class, GlobalExceptionHandler.class, WorkoutPlanErrorAdvice.class,
        RequestIdFilter.class, JacksonConfig.class})
class WorkoutPlanControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean WorkoutPlanQueryUseCase queries;
    @MockitoBean WorkoutPlanCommandUseCase commands;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    @Test void currentPlanIsActorScopedAndKeepsReadContext() throws Exception {
        UUID actor = UUID.randomUUID(); UUID plan = UUID.randomUUID();
        var summary = new WorkoutPlanViews.PlanSummary(plan, actor, null, UUID.randomUUID(), "Plan", null,
                "STUDENT", WorkoutPlanStatus.ACTIVE, 1,
                com.fitnesscoaching.platform.modules.workout.domain.DecisionOwnerType.STUDENT, actor,
                null, null, Instant.EPOCH, Instant.EPOCH, null, UUID.randomUUID(), 1,
                Instant.EPOCH, Instant.EPOCH, WorkoutPlanViews.ReadContext.CURRENT);
        when(queries.current(actor, null)).thenReturn(new WorkoutPlanViews.PlanDetail(summary, null));

        mvc.perform(get("/api/v1/workout-plans/current").with(jwt().jwt(j -> j.subject(actor.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.id").value(plan.toString()))
                .andExpect(jsonPath("$.plan.readContext").value("CURRENT"));
    }

    @Test void createsDraftAndReturnsLocationAndConcurrencyToken() throws Exception {
        UUID actor = UUID.randomUUID(); UUID plan = UUID.randomUUID(); UUID version = UUID.randomUUID();
        when(commands.createDraft(any())).thenReturn(new WorkoutPlanCommandUseCase.CommandResult(
                plan, version, 1, WorkoutPlanStatus.DRAFT, 0, Instant.EPOCH, false));
        Map<String, Object> body = Map.of("studentId", actor, "name", "Kế hoạch", "sessions", List.of(),
                "commandKey", "create-01");

        mvc.perform(post("/api/v1/workout-plans").with(jwt().jwt(j -> j.subject(actor.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/workout-plans/" + plan))
                .andExpect(jsonPath("$.planVersionNumber").value(1))
                .andExpect(jsonPath("$.aggregateVersion").value(0))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test void publishesCompleteReplacementSnapshotWithoutClientEffectiveTime() throws Exception {
        UUID actor = UUID.randomUUID(); UUID plan = UUID.randomUUID(); UUID version = UUID.randomUUID();
        when(commands.publishVersion(any())).thenReturn(new WorkoutPlanCommandUseCase.CommandResult(
                plan, version, 2, WorkoutPlanStatus.ACTIVE, 5, Instant.EPOCH, false));
        Map<String, Object> body = Map.of("expectedVersion", 4, "reason", "Đổi chiến lược",
                "summary", "Giai đoạn mới", "sessions", List.of(Map.of(
                        "weekNumber", 1, "dayNumber", 1, "sequenceNumber", 1, "name", "Buổi 1",
                        "prescriptions", List.of())), "commandKey", "publish-01");

        mvc.perform(post("/api/v1/workout-plans/{planId}/versions", plan)
                        .with(jwt().jwt(j -> j.subject(actor.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/workout-plans/" + plan + "/versions/" + version))
                .andExpect(jsonPath("$.planVersionNumber").value(2))
                .andExpect(jsonPath("$.effectiveAt").exists());
        verify(commands).publishVersion(any());
    }

    @Test void negativePrescriptionIsRejectedBeforeCommandInvocation() throws Exception {
        UUID actor = UUID.randomUUID();
        Map<String, Object> prescription = Map.of("exerciseVariationId", UUID.randomUUID(),
                "sequenceNumber", 1, "targetSets", -1);
        Map<String, Object> session = Map.of("weekNumber", 1, "dayNumber", 1, "sequenceNumber", 1,
                "name", "Buổi 1", "prescriptions", List.of(prescription));
        Map<String, Object> body = Map.of("studentId", actor, "name", "Kế hoạch",
                "sessions", List.of(session), "commandKey", "create-01");

        mvc.perform(post("/api/v1/workout-plans").with(jwt().jwt(j -> j.subject(actor.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        verify(commands, never()).createDraft(any());
    }

    @Test void stableDomainConflictDoesNotExposeInternalMessage() throws Exception {
        UUID actor = UUID.randomUUID(); UUID plan = UUID.randomUUID();
        when(commands.activate(any())).thenThrow(new WorkoutPlanFailure(
                WorkoutPlanError.ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS, "secret database detail"));
        Map<String, Object> body = Map.of("expectedVersion", 1, "commandKey", "activate-01");

        mvc.perform(post("/api/v1/workout-plans/{planId}/activate", plan)
                        .with(jwt().jwt(j -> j.subject(actor.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("Workout plan operation could not be completed"));
    }

    @Test void expectedVersionIsRequiredAtRuntime() throws Exception {
        UUID actor = UUID.randomUUID(); UUID plan = UUID.randomUUID();

        mvc.perform(post("/api/v1/workout-plans/{planId}/activate", plan)
                        .with(jwt().jwt(j -> j.subject(actor.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("commandKey", "activate-01"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        verify(commands, never()).activate(any());
    }

    @Test void adjustmentReplayReturnsOkWithoutSyntheticNullLocation() throws Exception {
        UUID actor = UUID.randomUUID(); UUID occurrence = UUID.randomUUID();
        when(commands.adjustOccurrence(any())).thenReturn(new WorkoutPlanCommandUseCase.OccurrenceResult(
                occurrence, null, 2, true));
        Map<String, Object> body = Map.of("expectedVersion", 1, "type", "NOTE",
                "afterValue", Map.of("note", "Nhẹ hơn"), "reason", "Theo buổi tập",
                "commandKey", "adjust-01");

        mvc.perform(post("/api/v1/planned-workouts/{occurrenceId}/adjustments", occurrence)
                        .with(jwt().jwt(j -> j.subject(actor.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.occurrenceVersion").value(2));
    }
}
