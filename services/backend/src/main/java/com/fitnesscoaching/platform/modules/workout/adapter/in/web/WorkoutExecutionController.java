package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.modules.workout.adapter.in.web.WorkoutExecutionDtos.*;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.SetExecution;
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
public class WorkoutExecutionController {
    private final WorkoutExecutionQueryUseCase queries;
    private final WorkoutExecutionCommandUseCase commands;
    private final WorkoutExecutionWebMapper mapper;

    public WorkoutExecutionController(WorkoutExecutionQueryUseCase queries,
                                      WorkoutExecutionCommandUseCase commands,
                                      WorkoutExecutionWebMapper mapper) {
        this.queries = queries;
        this.commands = commands;
        this.mapper = mapper;
    }

    @PostMapping("/api/v1/planned-workouts/{occurrenceId}/executions")
    public ResponseEntity<WorkoutExecutionDetail> start(@AuthenticationPrincipal Jwt jwt,
                                                         @PathVariable UUID occurrenceId,
                                                         @Valid @RequestBody StartWorkoutExecutionRequest request) {
        UUID actor = actor(jwt);
        var result = commands.start(new WorkoutExecutionCommandUseCase.StartCommand(
                occurrenceId, actor, request.expectedOccurrenceVersion(), request.commandKey()));
        WorkoutExecutionDetail body = mapper.detail(queries.detail(actor, result.id()));
        return ResponseEntity.created(URI.create("/api/v1/workout-executions/" + result.id())).body(body);
    }

    @PostMapping("/api/v1/planned-workouts/{occurrenceId}/skip")
    public SkipPlannedWorkoutResponse skip(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable UUID occurrenceId,
                                           @Valid @RequestBody SkipPlannedWorkoutRequest request) {
        var result = commands.skip(new WorkoutExecutionCommandUseCase.SkipCommand(occurrenceId, actor(jwt),
                request.expectedOccurrenceVersion(), request.reason(), request.commandKey()));
        return new SkipPlannedWorkoutResponse(result.occurrenceId(), result.status(), result.version(),
                result.replayed());
    }

    @GetMapping("/api/v1/workout-executions/current")
    public WorkoutExecutionDetail current(@AuthenticationPrincipal Jwt jwt) {
        return mapper.detail(queries.current(actor(jwt)));
    }

    @GetMapping("/api/v1/workout-executions")
    public WorkoutExecutionPage history(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam(required = false) UUID studentId,
                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID actor = actor(jwt);
        return mapper.page(queries.history(actor, studentId == null ? actor : studentId, page, size));
    }

    @GetMapping("/api/v1/workout-executions/{executionId}")
    public WorkoutExecutionDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID executionId) {
        return mapper.detail(queries.detail(actor(jwt), executionId));
    }

    @PutMapping("/api/v1/workout-executions/{executionId}/sets/{clientSetId}")
    public WorkoutExecutionDetail upsertSet(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable UUID executionId,
                                             @PathVariable UUID clientSetId,
                                             @Valid @RequestBody UpsertWorkoutSetRequest request) {
        UUID actor = actor(jwt);
        SetExecution set = new SetExecution(null, clientSetId, request.baselineSetNumber(), request.setNumber(),
                request.setType().name(), request.completionStatus().name(), request.repetitions(),
                request.loadValue(), request.loadUnitId(), request.durationSeconds(), request.distanceValue(),
                request.distanceUnitId(), request.rpe(), request.rir(), request.tempo(),
                request.restAfterSeconds(), request.note());
        commands.upsertSet(new WorkoutExecutionCommandUseCase.UpsertSetCommand(executionId,
                request.exerciseExecutionId(), actor, request.expectedVersion(), set));
        return mapper.detail(queries.detail(actor, executionId));
    }

    @PutMapping("/api/v1/workout-executions/{executionId}/exercises/{exerciseExecutionId}")
    public WorkoutExecutionDetail substitute(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable UUID executionId,
                                               @PathVariable UUID exerciseExecutionId,
                                               @Valid @RequestBody SubstituteWorkoutExerciseRequest request) {
        UUID actor = actor(jwt);
        commands.substitute(new WorkoutExecutionCommandUseCase.SubstituteExerciseCommand(executionId,
                exerciseExecutionId, actor, request.expectedVersion(), request.actualExerciseVariationId(),
                request.substitutionReason()));
        return mapper.detail(queries.detail(actor, executionId));
    }

    @PostMapping("/api/v1/workout-executions/{executionId}/complete")
    public WorkoutExecutionDetail complete(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable UUID executionId,
                                            @Valid @RequestBody CompleteWorkoutExecutionRequest request) {
        UUID actor = actor(jwt);
        commands.complete(new WorkoutExecutionCommandUseCase.TerminalCommand(executionId, actor,
                request.expectedVersion(), request.commandKey(), request.overallRpe(), request.sessionNote()));
        return mapper.detail(queries.detail(actor, executionId));
    }

    @PostMapping("/api/v1/workout-executions/{executionId}/abort")
    public WorkoutExecutionDetail abort(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable UUID executionId,
                                         @Valid @RequestBody AbortWorkoutExecutionRequest request) {
        UUID actor = actor(jwt);
        commands.abort(new WorkoutExecutionCommandUseCase.TerminalCommand(executionId, actor,
                request.expectedVersion(), request.commandKey(), request.overallRpe(), request.sessionNote()));
        return mapper.detail(queries.detail(actor, executionId));
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
