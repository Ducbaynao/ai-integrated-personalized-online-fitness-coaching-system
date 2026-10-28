package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.AcceptRecommendationResult;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.RejectRecommendationResult;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.WorkoutProposalResult;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AiWorkoutProposalController.class)
@Import({
        SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ClockConfig.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        JacksonConfig.class
})
class AiWorkoutProposalControllerUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiWorkoutProposalUseCase proposalUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("POST /ai/workout-proposals: Generates proposal successfully")
    void generateProposal_returns200() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        WorkoutProposalResult result = new WorkoutProposalResult(
                recId,
                runId,
                AiRecommendationStatus.PENDING,
                AiDecisionAuthority.STUDENT,
                "1-Week Starter Plan",
                "Explanation",
                Instant.now().plusSeconds(86400),
                Instant.now(),
                "{\"plan\":{\"name\":\"1-Week Starter Plan\"}}"
        );

        when(proposalUseCase.generateProposal(any())).thenReturn(result);

        String requestBody = """
                {
                    "studentId": "%s",
                    "commandKey": "cmd-key-123",
                    "preferences": {"focus": "Strength"}
                }
                """.formatted(actorId);

        mockMvc.perform(post("/ai/workout-proposals")
                        .with(jwt().jwt(j -> j.subject(actorId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(recId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.decisionAuthority").value("STUDENT"))
                .andExpect(jsonPath("$.title").value("1-Week Starter Plan"));
    }

    @Test
    @DisplayName("GET /ai/recommendations/{id}: Returns recommendation details")
    void getRecommendation_returns200() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .studentId(actorId)
                .title("Sample Recommendation")
                .explanation("Details")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .structuredPayload("{\"key\":\"value\"}")
                .build();

        when(proposalUseCase.getRecommendation(actorId, recId)).thenReturn(rec);

        mockMvc.perform(get("/ai/recommendations/" + recId)
                        .with(jwt().jwt(j -> j.subject(actorId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.title").value("Sample Recommendation"));
    }

    @Test
    @DisplayName("POST /ai/recommendations/{id}/accept: Accepts recommendation successfully")
    void acceptRecommendation_returns200() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        AcceptRecommendationResult result = new AcceptRecommendationResult(
                recId,
                AiRecommendationStatus.APPLIED,
                actorId,
                "WORKOUT_PLAN",
                planId,
                Instant.now(),
                false
        );

        when(proposalUseCase.acceptRecommendation(any())).thenReturn(result);

        String requestBody = """
                {
                    "commandKey": "accept-cmd-1",
                    "decisionNote": "Approved"
                }
                """;

        mockMvc.perform(post("/ai/recommendations/" + recId + "/accept")
                        .with(jwt().jwt(j -> j.subject(actorId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(recId.toString()))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.resultingEntityId").value(planId.toString()))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    @DisplayName("POST /ai/recommendations/{id}/reject: Rejects recommendation successfully")
    void rejectRecommendation_returns200() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();

        RejectRecommendationResult result = new RejectRecommendationResult(
                recId,
                AiRecommendationStatus.REJECTED,
                actorId,
                Instant.now(),
                "Too difficult"
        );

        when(proposalUseCase.rejectRecommendation(any())).thenReturn(result);

        String requestBody = """
                {
                    "reason": "Too difficult"
                }
                """;

        mockMvc.perform(post("/ai/recommendations/" + recId + "/reject")
                        .with(jwt().jwt(j -> j.subject(actorId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(recId.toString()))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionNote").value("Too difficult"));
    }
}
