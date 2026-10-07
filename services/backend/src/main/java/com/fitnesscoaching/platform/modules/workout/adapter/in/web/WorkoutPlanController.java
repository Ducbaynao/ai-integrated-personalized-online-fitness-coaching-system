package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.workout.adapter.in.web.WorkoutPlanDtos.*;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.*;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@Validated
@RestController
public class WorkoutPlanController {
    private final WorkoutPlanQueryUseCase queries;
    private final WorkoutPlanCommandUseCase commands;
    private final ObjectMapper objectMapper;

    public WorkoutPlanController(WorkoutPlanQueryUseCase queries, WorkoutPlanCommandUseCase commands,
                                 ObjectMapper objectMapper) {
        this.queries = queries;
        this.commands = commands;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/v1/workout-plans")
    public Page<PlanSummary> list(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(required = false) UUID studentId,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.list(actor(jwt), studentId, page, size);
    }

    @GetMapping("/api/v1/workout-plans/current")
    public PlanDetail current(@AuthenticationPrincipal Jwt jwt,
                              @RequestParam(required = false) UUID studentId) {
        return queries.current(actor(jwt), studentId);
    }

    @GetMapping("/api/v1/workout-plans/{planId}")
    public PlanDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId) {
        return queries.detail(actor(jwt), planId);
    }

    @GetMapping("/api/v1/workout-plans/{planId}/versions")
    public Page<VersionSummary> versions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId,
                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.versions(actor(jwt), planId, page, size);
    }

    @GetMapping("/api/v1/workout-plans/{planId}/versions/{versionId}")
    public VersionDetail version(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId,
                                 @PathVariable UUID versionId) {
        return queries.version(actor(jwt), planId, versionId);
    }

    @PostMapping("/api/v1/workout-plans")
    public ResponseEntity<CommandResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody CreateDraftRequest request) {
        CommandResponse response = CommandResponse.from(commands.createDraft(request.toCommand(actor(jwt))));
        return ResponseEntity.created(URI.create("/api/v1/workout-plans/" + response.planId())).body(response);
    }

    @PutMapping("/api/v1/workout-plans/{planId}/draft")
    public CommandResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId,
                                  @Valid @RequestBody UpdateDraftRequest request) {
        return CommandResponse.from(commands.updateDraft(new WorkoutPlanCommandUseCase.UpdateDraftCommand(
                planId, actor(jwt), request.expectedVersion(), request.name(), request.description(),
                request.sessions().stream().map(SessionRequest::toDomain).toList(), request.commandKey())));
    }

    @PostMapping("/api/v1/workout-plans/{planId}/activate")
    public CommandResponse activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId,
                                    @Valid @RequestBody ActivateRequest request) {
        return CommandResponse.from(commands.activate(new WorkoutPlanCommandUseCase.ActivateCommand(
                planId, actor(jwt), request.expectedVersion(), request.commandKey())));
    }

    @PostMapping("/api/v1/workout-plans/{planId}/transitions")
    public CommandResponse transition(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId,
                                      @Valid @RequestBody TransitionRequest request) {
        return CommandResponse.from(commands.transition(new WorkoutPlanCommandUseCase.TransitionCommand(
                planId, actor(jwt), request.expectedVersion(), request.target(), request.reason(),
                request.commandKey())));
    }

    @PostMapping("/api/v1/workout-plans/{planId}/versions")
    public ResponseEntity<CommandResponse> publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId,
                                                    @Valid @RequestBody PublishVersionRequest request) {
        CommandResponse response = CommandResponse.from(commands.publishVersion(
                new WorkoutPlanCommandUseCase.PublishVersionCommand(planId, actor(jwt), request.expectedVersion(),
                        request.commandKey(), request.reason(), request.summary(),
                        request.sessions().stream().map(SessionRequest::toDomain).toList())));
        return ResponseEntity.created(URI.create("/api/v1/workout-plans/" + planId + "/versions/"
                + response.planVersionId())).body(response);
    }

    @PostMapping("/api/v1/workout-plans/successors")
    public ResponseEntity<CommandResponse> successor(@AuthenticationPrincipal Jwt jwt,
                                                      @Valid @RequestBody CreateSuccessorRequest request) {
        CommandResponse response = CommandResponse.from(commands.createStudentSuccessor(
                new WorkoutPlanCommandUseCase.CreateStudentSuccessorCommand(request.sourcePlanId(),
                        request.sourceVersionId(), actor(jwt), request.name(), request.commandKey())));
        return ResponseEntity.created(URI.create("/api/v1/workout-plans/" + response.planId())).body(response);
    }

    @PostMapping("/api/v1/planned-workouts/{occurrenceId}/adjustments")
    public ResponseEntity<AdjustmentResponse> adjustment(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable UUID occurrenceId,
                                                          @Valid @RequestBody AdjustmentRequest request) {
        var result = commands.adjustOccurrence(new WorkoutPlanCommandUseCase.AdjustOccurrenceCommand(
                occurrenceId, actor(jwt), request.expectedVersion(), request.commandKey(), request.type(),
                request.plannedSessionExerciseId(), request.replacementVariationId(), json(request.beforeValue()),
                json(request.afterValue()), request.reason()));
        AdjustmentResponse response = AdjustmentResponse.from(result);
        if (result.replayed()) return ResponseEntity.ok(response);
        return ResponseEntity.created(URI.create("/api/v1/planned-workouts/" + occurrenceId + "/adjustments/"
                + result.adjustmentId())).body(response);
    }

    private String json(JsonNode value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception invalid) {
            throw new WorkoutPlanFailure(WorkoutPlanError.VALIDATION_FAILED, "Invalid adjustment JSON");
        }
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
