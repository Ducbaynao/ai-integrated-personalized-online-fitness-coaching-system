package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03DraftDiff;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.ShareAdjustmentDraftApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.UpdateAdjustmentDraftApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutAdjustmentUseCase;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AiWorkoutAdjustmentController.class)
@Import({
        SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ClockConfig.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        JacksonConfig.class
})
class AiWorkoutAdjustmentControllerUnitTest {

    @MockitoBean
    private com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery userRoleQuery;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiWorkoutAdjustmentUseCase adjustmentUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("GET /ai/workout-adjustments/{id}: Returns detail with id, recommendationId, and sharing fields")
    void getWorkoutAdjustmentDetail_returns200WithCanonicalFields() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .aiRunId(runId)
                .studentId(actorId)
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .title("Load Adjustment")
                .explanation("Deload 10%")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .revision(2)
                .structuredPayload("{\"key\":\"value\"}")
                .originalPayload("{\"key\":\"value\"}")
                .sharedWithStudent(true)
                .sharedRevision(2)
                .sharedAt(now)
                .sharedBy(actorId)
                .build();

        when(adjustmentUseCase.getAdjustment(eq(actorId), eq(recId))).thenReturn(rec);

        mockMvc.perform(get("/ai/workout-adjustments/" + recId)
                        .with(jwt().jwt(j -> j.subject(actorId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recId.toString()))
                .andExpect(jsonPath("$.recommendationId").value(recId.toString()))
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.aiRunId").value(runId.toString()))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.sharedWithStudent").value(true))
                .andExpect(jsonPath("$.sharedRevision").value(2));
    }

    @Test
    @DisplayName("GET /ai/workout-adjustments/{id}: Unshared draft by student returns 403 AI_FORBIDDEN")
    void getWorkoutAdjustmentDetail_unsharedDraft_returns403() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();

        when(adjustmentUseCase.getAdjustment(eq(actorId), eq(recId)))
                .thenThrow(new AiRunAccessDeniedException("Access denied: Private trainer draft has not been shared with the student."));

        mockMvc.perform(get("/ai/workout-adjustments/" + recId)
                        .with(jwt().jwt(j -> j.subject(actorId.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AI_FORBIDDEN"));
    }

    @Test
    @DisplayName("PUT /ai/workout-adjustments/{id}/draft: Updates draft successfully")
    void updateDraft_returns200() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();

        UpdateAdjustmentDraftApiResponse response = new UpdateAdjustmentDraftApiResponse(
                recId,
                2,
                true,
                AiRecommendationStatus.PENDING,
                "Updated Title",
                "Updated Explanation",
                Instant.now(),
                objectMapper.readTree("{\"updated\":true}"),
                objectMapper.readTree("{\"original\":true}"),
                F03DraftDiff.noChanges("LOAD_ADJUSTMENT")
        );

        when(adjustmentUseCase.updateDraft(eq(actorId), eq(recId), any())).thenReturn(response);

        String body = """
                {
                    "expectedRevision": 1,
                    "title": "Updated Title",
                    "explanation": "Updated Explanation",
                    "structuredPayload": {"updated": true}
                }
                """;

        mockMvc.perform(put("/ai/workout-adjustments/" + recId + "/draft")
                        .with(jwt().jwt(j -> j.subject(actorId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(recId.toString()))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.isCustomized").value(true))
                .andExpect(jsonPath("$.title").value("Updated Title"));
    }

    @Test
    @DisplayName("PATCH /ai/workout-adjustments/{id}: Updates draft successfully via PATCH")
    void patchDraft_returns200() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();

        UpdateAdjustmentDraftApiResponse response = new UpdateAdjustmentDraftApiResponse(
                recId,
                3,
                true,
                AiRecommendationStatus.PENDING,
                "Patched Title",
                "Patched Explanation",
                Instant.now(),
                objectMapper.readTree("{\"patched\":true}"),
                null,
                F03DraftDiff.noChanges("LOAD_ADJUSTMENT")
        );

        when(adjustmentUseCase.updateDraft(eq(actorId), eq(recId), any())).thenReturn(response);

        String body = """
                {
                    "expectedRevision": 2,
                    "title": "Patched Title",
                    "explanation": "Patched Explanation",
                    "structuredPayload": {"patched": true}
                }
                """;

        mockMvc.perform(patch("/ai/workout-adjustments/" + recId)
                        .with(jwt().jwt(j -> j.subject(actorId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(recId.toString()))
                .andExpect(jsonPath("$.revision").value(3));
    }

    @Test
    @DisplayName("POST /ai/workout-adjustments/{id}/share: Shares draft successfully")
    void shareDraft_returns200() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();
        Instant now = Instant.now();

        ShareAdjustmentDraftApiResponse response = new ShareAdjustmentDraftApiResponse(
                recId,
                true,
                2,
                now,
                actorId
        );

        when(adjustmentUseCase.shareDraft(eq(actorId), eq(recId), any())).thenReturn(response);

        String body = """
                {
                    "expectedRevision": 2,
                    "shareNote": "Please review"
                }
                """;

        mockMvc.perform(post("/ai/workout-adjustments/" + recId + "/share")
                        .with(jwt().jwt(j -> j.subject(actorId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(recId.toString()))
                .andExpect(jsonPath("$.sharedWithStudent").value(true))
                .andExpect(jsonPath("$.sharedRevision").value(2))
                .andExpect(jsonPath("$.sharedBy").value(actorId.toString()));
    }

    @Test
    @DisplayName("POST /ai/workout-adjustments/{id}/share: Stale revision conflict returns 409 AI_RUN_LIFECYCLE_CONFLICT")
    void shareDraft_staleRevision_returns409() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();

        when(adjustmentUseCase.shareDraft(eq(actorId), eq(recId), any()))
                .thenThrow(new AiRunLifecycleConflictException("Revision conflict: expected revision 1 but current is 2"));

        String body = """
                {
                    "expectedRevision": 1
                }
                """;

        mockMvc.perform(post("/ai/workout-adjustments/" + recId + "/share")
                        .with(jwt().jwt(j -> j.subject(actorId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AI_RUN_LIFECYCLE_CONFLICT"));
    }

    @Test
    @DisplayName("GET /ai/workout-adjustments/{id}: Student reads shared snapshot after trainer further edits v3")
    void getWorkoutAdjustmentDetail_sharedDraftView_studentReceivesSharedSnapshotWithoutLiveModifications() throws Exception {
        UUID studentId = UUID.randomUUID();
        UUID trainerId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant sharedAt = Instant.now();

        // Use case returns shared view (revision 2, shared title v2, ver 2 payload)
        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .aiRunId(runId)
                .studentId(studentId)
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .title("Trainer Live Secret Title v3")
                .explanation("Trainer Live Secret Explanation v3")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.TRAINER)
                .revision(3)
                .structuredPayload("{\"ver\":3,\"secret\":\"trainer_note\"}")
                .sharedWithStudent(true)
                .sharedRevision(2)
                .sharedTitle("Shared Title v2")
                .sharedExplanation("Shared Explanation v2")
                .sharedPayload("{\"ver\":2}")
                .sharedAt(sharedAt)
                .sharedBy(trainerId)
                .build();

        AiRecommendation sharedView = rec.withSharedView(
                2, "Shared Title v2", "Shared Explanation v2", "{\"ver\":2}", sharedAt, trainerId, true
        );

        when(adjustmentUseCase.getAdjustment(eq(studentId), eq(recId))).thenReturn(sharedView);

        mockMvc.perform(get("/ai/workout-adjustments/" + recId)
                        .with(jwt().jwt(j -> j.subject(studentId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recId.toString()))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.title").value("Shared Title v2"))
                .andExpect(jsonPath("$.explanation").value("Shared Explanation v2"))
                .andExpect(jsonPath("$.structuredPayload.ver").value(2))
                .andExpect(jsonPath("$.sharedWithStudent").value(true))
                .andExpect(jsonPath("$.sharedRevision").value(2))
                .andExpect(jsonPath("$.sharedBy").value(trainerId.toString()));
    }

    @Test
    @DisplayName("GET /ai/workout-adjustments/{id}: Shared draft with null sharedTitle and sharedExplanation returns nulls without live fallback")
    void getWorkoutAdjustmentDetail_sharedDraftWithNullTitleAndExplanation_returnsNulls() throws Exception {
        UUID studentId = UUID.randomUUID();
        UUID trainerId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant sharedAt = Instant.now();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .aiRunId(runId)
                .studentId(studentId)
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .title("Live Draft Title")
                .explanation("Live Draft Explanation")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.TRAINER)
                .revision(2)
                .structuredPayload("{\"ver\":2}")
                .sharedWithStudent(true)
                .sharedRevision(1)
                .sharedTitle(null)
                .sharedExplanation(null)
                .sharedPayload("{\"ver\":1}")
                .sharedAt(sharedAt)
                .sharedBy(trainerId)
                .build();

        AiRecommendation sharedView = rec.withSharedView(
                1, null, null, "{\"ver\":1}", sharedAt, trainerId, false
        );

        when(adjustmentUseCase.getAdjustment(eq(studentId), eq(recId))).thenReturn(sharedView);

        mockMvc.perform(get("/ai/workout-adjustments/" + recId)
                        .with(jwt().jwt(j -> j.subject(studentId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recId.toString()))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.title").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.explanation").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.structuredPayload.ver").value(1));
    }

    @Test
    @DisplayName("GET /ai/workout-adjustments/{id}: Corrupted or missing snapshot returns 409 AI_RUN_LIFECYCLE_CONFLICT")
    void getWorkoutAdjustmentDetail_missingSnapshot_returns409Conflict() throws Exception {
        UUID studentId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();

        when(adjustmentUseCase.getAdjustment(eq(studentId), eq(recId)))
                .thenThrow(new AiRunLifecycleConflictException("Shared snapshot data is missing or corrupted."));

        mockMvc.perform(get("/ai/workout-adjustments/" + recId)
                        .with(jwt().jwt(j -> j.subject(studentId.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AI_RUN_LIFECYCLE_CONFLICT"));
    }
}
