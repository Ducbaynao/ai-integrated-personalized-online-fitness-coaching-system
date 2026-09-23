package com.fitnesscoaching.platform.modules.goal.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.FitnessGoalNotFoundException;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.ActivateFitnessGoalRequest;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.CreateFitnessGoalRequest;
import com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto.FitnessGoalResponse;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetFitnessGoalDetailUseCase;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fitness-goals")
public class FitnessGoalController {

    private final CreateFitnessGoalUseCase createFitnessGoalUseCase;
    private final GetFitnessGoalDetailUseCase getFitnessGoalDetailUseCase;
    private final GetCurrentFitnessGoalUseCase getCurrentFitnessGoalUseCase;
    private final ActivateFitnessGoalUseCase activateFitnessGoalUseCase;

    public FitnessGoalController(
            CreateFitnessGoalUseCase createFitnessGoalUseCase,
            GetFitnessGoalDetailUseCase getFitnessGoalDetailUseCase,
            GetCurrentFitnessGoalUseCase getCurrentFitnessGoalUseCase,
            ActivateFitnessGoalUseCase activateFitnessGoalUseCase
    ) {
        this.createFitnessGoalUseCase = createFitnessGoalUseCase;
        this.getFitnessGoalDetailUseCase = getFitnessGoalDetailUseCase;
        this.getCurrentFitnessGoalUseCase = getCurrentFitnessGoalUseCase;
        this.activateFitnessGoalUseCase = activateFitnessGoalUseCase;
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
}
