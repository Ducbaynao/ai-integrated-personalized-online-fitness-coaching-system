package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.ContinuityEvaluationDto;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.WorkoutProgressSummaryDto;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiProgressSummarizationUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AiProgressSummaryController.class)
@Import({
        SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ClockConfig.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        JacksonConfig.class
})
class AiProgressSummaryControllerUnitTest {
    @MockitoBean
    private com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery userRoleQuery;


    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiProgressSummarizationUseCase summarizationUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID actorId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    @DisplayName("POST /api/v1/ai/progress/summary requires authentication (401)")
    void summarize_unauthenticated_returns401() throws Exception {
        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, null
        );

        mockMvc.perform(post("/api/v1/ai/progress/summary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/ai/progress/summary with valid JWT returns 200 and INFORMATION payload")
    void summarize_authenticated_returns200() throws Exception {
        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, null
        );

        F04ProgressSummaryResponse response = new F04ProgressSummaryResponse(
                runId,
                UUID.randomUUID(),
                studentId,
                actorId,
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Tóm tắt tiến độ chu kỳ 28 ngày.",
                new WorkoutProgressSummaryDto("Completed 10/12", "Adherence 83.3%", "Volume 14500kg"),
                null,
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Consistent", false),
                List.of(),
                List.of(),
                List.of("workout.adherence"),
                now,
                "ENGINE:wm-1",
                null,
                false,
                false,
                null
        );

        when(summarizationUseCase.summarizeProgress(eq(actorId), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/ai/progress/summary")
                        .with(jwt().jwt(j -> j.subject(actorId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.resultType").value("INFORMATION"))
                .andExpect(jsonPath("$.runStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.summary").value("Tóm tắt tiến độ chu kỳ 28 ngày."))
                .andExpect(jsonPath("$.workoutProgressSummary.completionEvaluation").value("Completed 10/12"));
    }

    @Test
    @DisplayName("GET /api/v1/ai/progress/summary/{runId} returns 200 with freshness metadata")
    void readback_authenticated_returns200() throws Exception {
        F04ProgressSummaryResponse response = new F04ProgressSummaryResponse(
                runId,
                UUID.randomUUID(),
                studentId,
                actorId,
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Bản tóm tắt lưu trữ.",
                null,
                null,
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Consistent", false),
                List.of(),
                List.of(),
                List.of(),
                now,
                "ENGINE:wm-1",
                null,
                true, // isStale
                true, // needsRefresh
                "ENGINE:wm-2-MODIFIED"
        );

        when(summarizationUseCase.getProgressSummary(eq(actorId), eq(runId))).thenReturn(response);

        mockMvc.perform(get("/api/v1/ai/progress/summary/" + runId)
                        .with(jwt().jwt(j -> j.subject(actorId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.isStale").value(true))
                .andExpect(jsonPath("$.needsRefresh").value(true))
                .andExpect(jsonPath("$.currentWatermark").value("ENGINE:wm-2-MODIFIED"));
    }

    @Test
    @DisplayName("POST /api/v1/ai/progress/summary mapping FAILED TIMEOUT to 504")
    void summarize_timeout_returns504() throws Exception {
        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, null
        );

        F04ProgressSummaryResponse response = new F04ProgressSummaryResponse(
                runId,
                UUID.randomUUID(),
                studentId,
                actorId,
                "FAILED",
                "BLOCKED",
                "TIMEOUT",
                "AI service timed out",
                null, null, null, List.of(), List.of(), List.of(), now, "ENGINE:wm-1", null, false, false, null
        );

        when(summarizationUseCase.summarizeProgress(eq(actorId), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/ai/progress/summary")
                        .with(jwt().jwt(j -> j.subject(actorId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.reasonCode").value("TIMEOUT"));
    }
}
