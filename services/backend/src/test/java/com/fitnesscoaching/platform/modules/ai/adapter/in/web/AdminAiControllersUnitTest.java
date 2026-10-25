package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.ToggleAiFeatureApiRequest;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AdminAiRunQueryUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiFeatureControlUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AdminAiRunQueryController.class, AdminAiFeatureController.class})
@Import({
        SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ClockConfig.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        JacksonConfig.class
})
class AdminAiControllersUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdminAiRunQueryUseCase queryUseCase;

    @MockitoBean
    private AiFeatureControlUseCase featureControlUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID adminId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @Test
    @DisplayName("GET /api/v1/admin/ai/runs returns 401 without authentication")
    void listRuns_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai/runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/admin/ai/runs returns 403 when user is not ADMIN")
    void listRuns_studentRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai/runs")
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/admin/ai/runs returns 200 with paginated operational list for ADMIN")
    void listRuns_admin_returns200() throws Exception {
        AdminAiRunQueryUseCase.AdminAiRunSummaryDto item = new AdminAiRunQueryUseCase.AdminAiRunSummaryDto(
                runId,
                "F01_GENERAL_KNOWLEDGE",
                "SUCCEEDED",
                "BUSINESS_ASSISTANCE",
                UUID.randomUUID(),
                Instant.now(),
                Instant.now(),
                Instant.now(),
                850,
                null,
                null,
                100,
                50,
                150,
                false,
                Instant.now()
        );

        when(queryUseCase.listRuns(eq(adminId), eq(0), eq(20), any(), any()))
                .thenReturn(new AdminAiRunQueryUseCase.AdminAiRunPageDto(
                        List.of(item), 1L, 0, 20, 1
                ));

        mockMvc.perform(get("/api/v1/admin/ai/runs")
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(runId.toString())))
                .andExpect(jsonPath("$.items[0].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.items[0].latencyMs", is(850)))
                .andExpect(jsonPath("$.items[0].totalTokenCount", is(150)));
    }

    @Test
    @DisplayName("GET /api/v1/admin/ai/runs/{runId} returns 200 with sanitized details")
    void getRunDetail_admin_returns200() throws Exception {
        AdminAiRunQueryUseCase.AdminAiValidationDto validation = new AdminAiRunQueryUseCase.AdminAiValidationDto(
                UUID.randomUUID(),
                "F01_SCHEMA_DOMAIN_VALIDATOR",
                true,
                "INFO",
                "{\"result\":\"PASSED\"}",
                Instant.now()
        );
        AdminAiRunQueryUseCase.AdminAiRunChunkDto chunk = new AdminAiRunQueryUseCase.AdminAiRunChunkDto(
                UUID.randomUUID(),
                1,
                BigDecimal.valueOf(0.95)
        );

        AdminAiRunQueryUseCase.AdminAiRunDetailDto detail = new AdminAiRunQueryUseCase.AdminAiRunDetailDto(
                runId,
                "F01_GENERAL_KNOWLEDGE",
                "SUCCEEDED",
                "BUSINESS_ASSISTANCE",
                UUID.randomUUID(),
                Instant.now(),
                Instant.now(),
                Instant.now(),
                1100,
                null,
                null,
                200,
                80,
                280,
                false,
                Instant.now(),
                List.of(validation),
                List.of(chunk)
        );

        when(queryUseCase.getRunDetail(adminId, runId)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/admin/ai/runs/{runId}", runId)
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(runId.toString())))
                .andExpect(jsonPath("$.validations", hasSize(1)))
                .andExpect(jsonPath("$.citations", hasSize(1)))
                .andExpect(jsonPath("$.inputPayload").doesNotExist()) // Verify sensitive raw payloads are NOT exposed!
                .andExpect(jsonPath("$.outputPayload").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/admin/ai/runs/{runId} returns 404 when run does not exist")
    void getRunDetail_notFound_returns404() throws Exception {
        when(queryUseCase.getRunDetail(adminId, runId))
                .thenThrow(new AiRunNotFoundException("AI Run not found"));

        mockMvc.perform(get("/api/v1/admin/ai/runs/{runId}", runId)
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("AI_RUN_NOT_FOUND")));
    }

    @Test
    @DisplayName("GET /api/v1/admin/ai/features/f01 returns feature status for ADMIN")
    void getF01Status_admin_returns200() throws Exception {
        when(featureControlUseCase.getF01Status()).thenReturn(new AiFeatureControlUseCase.AiFeatureStatusDto(
                "F01_GENERAL_KNOWLEDGE",
                true,
                "F01 Assistance",
                adminId,
                Instant.now()
        ));

        mockMvc.perform(get("/api/v1/admin/ai/features/f01")
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureCode", is("F01_GENERAL_KNOWLEDGE")))
                .andExpect(jsonPath("$.enabled", is(true)));
    }

    @Test
    @DisplayName("POST /api/v1/admin/ai/features/f01/toggle updates status with reason")
    void toggleF01_admin_returns200() throws Exception {
        ToggleAiFeatureApiRequest req = new ToggleAiFeatureApiRequest(false, "Scheduled system maintenance");

        when(featureControlUseCase.setF01Enabled(eq(adminId), eq(false), eq("Scheduled system maintenance")))
                .thenReturn(new AiFeatureControlUseCase.AiFeatureStatusDto(
                        "F01_GENERAL_KNOWLEDGE",
                        false,
                        "F01 Assistance",
                        adminId,
                        Instant.now()
                ));

        mockMvc.perform(post("/api/v1/admin/ai/features/f01/toggle")
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureCode", is("F01_GENERAL_KNOWLEDGE")))
                .andExpect(jsonPath("$.enabled", is(false)));
    }
}
