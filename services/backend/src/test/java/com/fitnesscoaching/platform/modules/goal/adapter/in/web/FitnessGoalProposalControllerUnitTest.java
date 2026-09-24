package com.fitnesscoaching.platform.modules.goal.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.*;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.*;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalProposalPage;
import com.fitnesscoaching.platform.modules.goal.application.port.in.*;
import com.fitnesscoaching.platform.modules.goal.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FitnessGoalProposalController.class)
@Import({
        SecurityConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ClockConfig.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        JacksonConfig.class
})
class FitnessGoalProposalControllerUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreateGoalProposalUseCase createGoalProposalUseCase;

    @MockitoBean
    private GetStudentGoalProposalsUseCase getStudentGoalProposalsUseCase;

    @MockitoBean
    private GetGoalProposalDetailUseCase getGoalProposalDetailUseCase;

    @MockitoBean
    private DecideGoalProposalUseCase decideGoalProposalUseCase;

    @MockitoBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/proposals: returns 201 Created for trainer")
    void createProposal_success() throws Exception {
        UUID goalId = UUID.randomUUID();
        UUID trainerId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        CreateGoalProposalRequest request = new CreateGoalProposalRequest(
                "Proposed Title",
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                60,
                "Reason for proposal",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of(new CreateGoalTargetRequest(1, null, null, BigDecimal.valueOf(80), BigDecimal.valueOf(75), null, null, (short) 1, null, null, null, null))
        );

        GoalProposal created = new GoalProposal(
                proposalId,
                studentId,
                goalId,
                UUID.randomUUID(),
                ProposalSource.TRAINER,
                trainerId,
                "Proposed Title",
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                60,
                "Reason for proposal",
                ProposalStatus.PENDING,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                List.of(new GoalProposalObjective(UUID.randomUUID(), proposalId, (short) 1, "FAT_LOSS", "Fat Loss", ObjectivePriority.PRIMARY, 0, null)),
                List.of(),
                null
        );

        when(createGoalProposalUseCase.createProposal(any())).thenReturn(created);

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .with(jwt().jwt(b -> b.subject(trainerId.toString())).authorities(new SimpleGrantedAuthority("ROLE_TRAINER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/fitness-goal-proposals/" + proposalId))
                .andExpect(jsonPath("$.id", is(proposalId.toString())))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.reason", is("Reason for proposal")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goals/{goalId}/proposals: returns 403 when user is not trainer")
    void createProposal_forbiddenForStudent() throws Exception {
        UUID goalId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();

        CreateGoalProposalRequest request = new CreateGoalProposalRequest(
                "Proposed Title",
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                60,
                "Reason",
                List.of(new CreateGoalObjectiveRequest((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        mockMvc.perform(post("/api/v1/fitness-goals/{goalId}/proposals", goalId)
                        .with(jwt().jwt(b -> b.subject(studentId.toString())).authorities(new SimpleGrantedAuthority("ROLE_STUDENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/fitness-goal-proposals/me: returns paginated list for student")
    void getMyProposals_success() throws Exception {
        UUID studentId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        GoalProposal p = new GoalProposal(
                proposalId,
                studentId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                ProposalSource.TRAINER,
                UUID.randomUUID(),
                "Proposed Title",
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                60,
                "Reason",
                ProposalStatus.PENDING,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                List.of(),
                List.of(),
                null
        );

        when(getStudentGoalProposalsUseCase.getStudentProposals(any()))
                .thenReturn(new GoalProposalPage(List.of(p), 0, 20, 1L, 1));

        mockMvc.perform(get("/api/v1/fitness-goal-proposals/me")
                        .with(jwt().jwt(b -> b.subject(studentId.toString())).authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(proposalId.toString())))
                .andExpect(jsonPath("$.totalItems", is(1)))
                .andExpect(jsonPath("$.totalPages", is(1)));
    }

    @Test
    @DisplayName("GET /api/v1/fitness-goal-proposals/{proposalId}: returns 200 for authorized user")
    void getProposalDetail_success() throws Exception {
        UUID studentId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        GoalProposal p = new GoalProposal(
                proposalId,
                studentId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                ProposalSource.TRAINER,
                UUID.randomUUID(),
                "Proposed Title",
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                60,
                "Reason",
                ProposalStatus.PENDING,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                List.of(),
                List.of(),
                null
        );

        when(getGoalProposalDetailUseCase.getProposalDetail(any())).thenReturn(p);

        mockMvc.perform(get("/api/v1/fitness-goal-proposals/{proposalId}", proposalId)
                        .with(jwt().jwt(b -> b.subject(studentId.toString())).authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(proposalId.toString())))
                .andExpect(jsonPath("$.status", is("PENDING")));
    }

    @Test
    @DisplayName("POST /api/v1/fitness-goal-proposals/{proposalId}/decisions: returns 200 on student decision")
    void decideProposal_success() throws Exception {
        UUID studentId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        DecideGoalProposalRequest request = new DecideGoalProposalRequest(
                ProposalDecision.ACCEPT,
                null
        );

        GoalProposal accepted = new GoalProposal(
                proposalId,
                studentId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                ProposalSource.TRAINER,
                UUID.randomUUID(),
                "Proposed Title",
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                60,
                "Reason",
                ProposalStatus.ACCEPTED,
                studentId,
                Instant.now(),
                null,
                null,
                Instant.now(),
                Instant.now(),
                List.of(),
                List.of(),
                null
        );

        when(decideGoalProposalUseCase.decideProposal(any())).thenReturn(accepted);

        mockMvc.perform(post("/api/v1/fitness-goal-proposals/{proposalId}/decisions", proposalId)
                        .with(jwt().jwt(b -> b.subject(studentId.toString())).authorities(new SimpleGrantedAuthority("ROLE_STUDENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(proposalId.toString())))
                .andExpect(jsonPath("$.status", is("ACCEPTED")));
    }
}
