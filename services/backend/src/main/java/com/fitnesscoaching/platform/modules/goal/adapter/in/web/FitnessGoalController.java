package com.fitnesscoaching.platform.modules.goal.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.FitnessGoalNotFoundException;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.ActivateFitnessGoalRequest;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.CreateFitnessGoalRequest;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.CreateGoalVersionRequest;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.FitnessGoalResponse;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.GoalVersionPageResponse;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.GoalVersionResponse;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalVersionCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalVersionUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetFitnessGoalDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionDetailQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionsQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionsUseCase;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalVersionPage;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.CreateGoalTransitionRequest;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.GoalTransitionResponse;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalTransitionResult;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTransitionCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTransitionUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionDetailQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionsQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionsUseCase;
import com.fitnesscoaching.platform.modules.goal.domain.GoalTransition;
import java.util.List;

@RestController
@RequestMapping("/api/v1/fitness-goals")
public class FitnessGoalController {

    private final CreateFitnessGoalUseCase createFitnessGoalUseCase;
    private final GetFitnessGoalDetailUseCase getFitnessGoalDetailUseCase;
    private final GetCurrentFitnessGoalUseCase getCurrentFitnessGoalUseCase;
    private final ActivateFitnessGoalUseCase activateFitnessGoalUseCase;
    private final GetGoalVersionsUseCase getGoalVersionsUseCase;
    private final GetGoalVersionDetailUseCase getGoalVersionDetailUseCase;
    private final CreateGoalVersionUseCase createGoalVersionUseCase;
    private final CreateGoalTransitionUseCase createGoalTransitionUseCase;
    private final GetGoalTransitionsUseCase getGoalTransitionsUseCase;
    private final GetGoalTransitionDetailUseCase getGoalTransitionDetailUseCase;

    public FitnessGoalController(
            CreateFitnessGoalUseCase createFitnessGoalUseCase,
            GetFitnessGoalDetailUseCase getFitnessGoalDetailUseCase,
            GetCurrentFitnessGoalUseCase getCurrentFitnessGoalUseCase,
            ActivateFitnessGoalUseCase activateFitnessGoalUseCase,
            GetGoalVersionsUseCase getGoalVersionsUseCase,
            GetGoalVersionDetailUseCase getGoalVersionDetailUseCase,
            CreateGoalVersionUseCase createGoalVersionUseCase,
            CreateGoalTransitionUseCase createGoalTransitionUseCase,
            GetGoalTransitionsUseCase getGoalTransitionsUseCase,
            GetGoalTransitionDetailUseCase getGoalTransitionDetailUseCase
    ) {
        this.createFitnessGoalUseCase = createFitnessGoalUseCase;
        this.getFitnessGoalDetailUseCase = getFitnessGoalDetailUseCase;
        this.getCurrentFitnessGoalUseCase = getCurrentFitnessGoalUseCase;
        this.activateFitnessGoalUseCase = activateFitnessGoalUseCase;
        this.getGoalVersionsUseCase = getGoalVersionsUseCase;
        this.getGoalVersionDetailUseCase = getGoalVersionDetailUseCase;
        this.createGoalVersionUseCase = createGoalVersionUseCase;
        this.createGoalTransitionUseCase = createGoalTransitionUseCase;
        this.getGoalTransitionsUseCase = getGoalTransitionsUseCase;
        this.getGoalTransitionDetailUseCase = getGoalTransitionDetailUseCase;
    }

    @PostMapping
    public ResponseEntity<FitnessGoalResponse> createFitnessGoal(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateFitnessGoalRequest request
    ) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        CreateFitnessGoalCommand command = request.toCommand(studentId);
        FitnessGoal created = createFitnessGoalUseCase.createFitnessGoal(command);

        URI location = URI.create("/api/v1/fitness-goals/" + created.id());
        return ResponseEntity.created(location).body(FitnessGoalResponse.fromDomain(created));
    }

    @GetMapping("/me/current")
    public ResponseEntity<FitnessGoalResponse> getCurrentFitnessGoal(@AuthenticationPrincipal Jwt jwt) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        FitnessGoal current = getCurrentFitnessGoalUseCase.getCurrentFitnessGoal(studentId)
                .orElseThrow(() -> new FitnessGoalNotFoundException("No active fitness goal found for student."));

        return ResponseEntity.ok(FitnessGoalResponse.fromDomain(current));
    }

    @GetMapping("/{goalId}")
    public ResponseEntity<FitnessGoalResponse> getFitnessGoalDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID goalId
    ) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        FitnessGoal goal = getFitnessGoalDetailUseCase.getFitnessGoalDetail(studentId, goalId);

        return ResponseEntity.ok(FitnessGoalResponse.fromDomain(goal));
    }

    @PostMapping("/{goalId}/activate")
    public ResponseEntity<FitnessGoalResponse> activateFitnessGoal(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID goalId,
            @Valid @RequestBody(required = false) ActivateFitnessGoalRequest request
    ) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        String reason = request != null ? request.reason() : null;
        ActivateFitnessGoalCommand command = new ActivateFitnessGoalCommand(studentId, goalId, reason);
        FitnessGoal activated = activateFitnessGoalUseCase.activateFitnessGoal(command);

        return ResponseEntity.ok(FitnessGoalResponse.fromDomain(activated));
    }

    @GetMapping("/{goalId}/versions")
    public ResponseEntity<GoalVersionPageResponse> getGoalVersions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID goalId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        GetGoalVersionsQuery query = new GetGoalVersionsQuery(studentId, goalId, page, size);
        GoalVersionPage versionPage = getGoalVersionsUseCase.getGoalVersions(query);
        GoalVersionPageResponse response = new GoalVersionPageResponse(
                versionPage.items().stream().map(GoalVersionResponse::fromDomain).toList(),
                versionPage.page(),
                versionPage.size(),
                versionPage.totalElements(),
                versionPage.totalPages()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{goalId}/versions/{versionId}")
    public ResponseEntity<GoalVersionResponse> getGoalVersionDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID goalId,
            @PathVariable UUID versionId
    ) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        GetGoalVersionDetailQuery query = new GetGoalVersionDetailQuery(studentId, goalId, versionId);
        FitnessGoalVersion version = getGoalVersionDetailUseCase.getGoalVersionDetail(query);

        return ResponseEntity.ok(GoalVersionResponse.fromDomain(version));
    }

    @PostMapping("/{goalId}/versions")
    public ResponseEntity<GoalVersionResponse> createGoalVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID goalId,
            @Valid @RequestBody CreateGoalVersionRequest request
    ) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        CreateGoalVersionCommand command = request.toCommand(studentId, goalId);
        FitnessGoalVersion created = createGoalVersionUseCase.createGoalVersion(command);

        URI location = URI.create("/api/v1/fitness-goals/" + goalId + "/versions/" + created.id());
        return ResponseEntity.created(location).body(GoalVersionResponse.fromDomain(created));
    }

    @PostMapping("/{goalId}/transitions")
    public ResponseEntity<GoalTransitionResponse> createGoalTransition(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID goalId,
            @Valid @RequestBody CreateGoalTransitionRequest request
    ) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        CreateGoalTransitionCommand command = request.toCommand(studentId, goalId);
        GoalTransitionResult result = createGoalTransitionUseCase.createGoalTransition(command);

        URI location = URI.create("/api/v1/fitness-goals/" + goalId + "/transitions/" + result.transition().id());
        return ResponseEntity.created(location).body(GoalTransitionResponse.fromDomain(result.transition(), result.newGoal()));
    }

    @GetMapping("/{goalId}/transitions")
    public ResponseEntity<List<GoalTransitionResponse>> getGoalTransitions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID goalId
    ) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        GetGoalTransitionsQuery query = new GetGoalTransitionsQuery(studentId, goalId);
        List<GoalTransition> transitions = getGoalTransitionsUseCase.getGoalTransitions(query);
        List<GoalTransitionResponse> response = transitions.stream()
                .map(GoalTransitionResponse::fromDomain)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{goalId}/transitions/{transitionId}")
    public ResponseEntity<GoalTransitionResponse> getGoalTransitionDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID goalId,
            @PathVariable UUID transitionId
    ) {
        UUID studentId = UUID.fromString(jwt.getSubject());
        GetGoalTransitionDetailQuery query = new GetGoalTransitionDetailQuery(studentId, goalId, transitionId);
        GoalTransition transition = getGoalTransitionDetailUseCase.getGoalTransitionDetail(query);

        return ResponseEntity.ok(GoalTransitionResponse.fromDomain(transition));
    }
}
