package com.fitnesscoaching.platform.modules.goal.adapter.in.web;

import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.*;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalProposalPage;
import com.fitnesscoaching.platform.modules.goal.application.port.in.*;
import com.fitnesscoaching.platform.modules.goal.domain.GoalProposal;
import com.fitnesscoaching.platform.modules.goal.domain.ProposalStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
public class FitnessGoalProposalController {

    private final CreateGoalProposalUseCase createGoalProposalUseCase;
    private final GetStudentGoalProposalsUseCase getStudentGoalProposalsUseCase;
    private final GetGoalProposalDetailUseCase getGoalProposalDetailUseCase;
    private final DecideGoalProposalUseCase decideGoalProposalUseCase;

    public FitnessGoalProposalController(
            CreateGoalProposalUseCase createGoalProposalUseCase,
            GetStudentGoalProposalsUseCase getStudentGoalProposalsUseCase,
            GetGoalProposalDetailUseCase getGoalProposalDetailUseCase,
            DecideGoalProposalUseCase decideGoalProposalUseCase
    ) {
        this.createGoalProposalUseCase = createGoalProposalUseCase;
        this.getStudentGoalProposalsUseCase = getStudentGoalProposalsUseCase;
        this.getGoalProposalDetailUseCase = getGoalProposalDetailUseCase;
        this.decideGoalProposalUseCase = decideGoalProposalUseCase;
    }

    @PostMapping("/api/v1/fitness-goals/{goalId}/proposals")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<GoalProposalResponse> createProposal(
            @PathVariable UUID goalId,
            @Valid @RequestBody CreateGoalProposalRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID trainerId = UUID.fromString(jwt.getSubject());
        CreateGoalProposalCommand command = request.toCommand(goalId, trainerId);
        GoalProposal created = createGoalProposalUseCase.createProposal(command);

        URI location = URI.create("/api/v1/fitness-goal-proposals/" + created.id());
        return ResponseEntity.created(location).body(GoalProposalResponse.fromDomain(created));
    }

    @GetMapping("/api/v1/fitness-goal-proposals/me")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<GoalProposalPageResponse> getMyProposals(
            @RequestParam(required = false) ProposalStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        GetStudentGoalProposalsQuery query = new GetStudentGoalProposalsQuery(studentId, status, page, size);
        GoalProposalPage result = getStudentGoalProposalsUseCase.getStudentProposals(query);

        return ResponseEntity.ok(GoalProposalPageResponse.fromDomain(result));
    }

    @GetMapping("/api/v1/fitness-goal-proposals/{proposalId}")
    @PreAuthorize("hasAnyRole('STUDENT', 'TRAINER')")
    public ResponseEntity<GoalProposalResponse> getProposalDetail(
            @PathVariable UUID proposalId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        GetGoalProposalDetailQuery query = new GetGoalProposalDetailQuery(proposalId, userId);
        GoalProposal proposal = getGoalProposalDetailUseCase.getProposalDetail(query);

        return ResponseEntity.ok(GoalProposalResponse.fromDomain(proposal));
    }

    @PostMapping("/api/v1/fitness-goal-proposals/{proposalId}/decisions")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<GoalProposalResponse> decideProposal(
            @PathVariable UUID proposalId,
            @Valid @RequestBody DecideGoalProposalRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        DecideGoalProposalCommand command = request.toCommand(proposalId, studentId);
        GoalProposal decided = decideGoalProposalUseCase.decideProposal(command);

        return ResponseEntity.ok(GoalProposalResponse.fromDomain(decided));
    }
}
