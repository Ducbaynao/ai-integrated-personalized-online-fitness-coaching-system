package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutRecurrenceUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
public class WorkoutRecurrenceController {
    private final WorkoutRecurrenceUseCase useCase;
    public WorkoutRecurrenceController(WorkoutRecurrenceUseCase useCase) { this.useCase = useCase; }

    @PostMapping("/api/v1/planned-workout-series")
    public ResponseEntity<WorkoutRecurrenceUseCase.Series> confirm(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest body) {
        UUID actor = actor(jwt);
        return ResponseEntity.status(201).body(useCase.confirmStudent(body.command(actor, actor)));
    }

    @GetMapping("/api/v1/planned-workout-series")
    public WorkoutRecurrenceUseCase.Page<WorkoutRecurrenceUseCase.Series> mine(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return useCase.listStudent(actor(jwt), status, page, size);
    }

    @PostMapping("/api/v1/students/{studentId}/planned-workout-series")
    public ResponseEntity<WorkoutRecurrenceUseCase.Series> propose(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID studentId,
            @Valid @RequestBody CreateRequest body) {
        return ResponseEntity.status(201).body(useCase.proposeTrainer(body.command(actor(jwt), studentId)));
    }

    @GetMapping("/api/v1/students/{studentId}/planned-workout-series")
    public WorkoutRecurrenceUseCase.Page<WorkoutRecurrenceUseCase.Series> trainerList(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID studentId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return useCase.listTrainer(actor(jwt), studentId, status, page, size);
    }

    @GetMapping("/api/v1/planned-workout-series/{seriesId}")
    public WorkoutRecurrenceUseCase.Series detail(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID seriesId) {
        return useCase.detail(actor(jwt), seriesId);
    }

    @GetMapping("/api/v1/planned-workout-series/{seriesId}/periods")
    public WorkoutRecurrenceUseCase.Page<WorkoutRecurrenceUseCase.Period> periods(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID seriesId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return useCase.periods(actor(jwt), seriesId, page, size);
    }

    @PostMapping("/api/v1/planned-workout-series/{seriesId}/decisions")
    public WorkoutRecurrenceUseCase.Series decide(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID seriesId,
                                                  @Valid @RequestBody DecisionRequest body) {
        return useCase.decide(new WorkoutRecurrenceUseCase.Decision(actor(jwt), seriesId,
                body.expectedSeriesVersion(), body.decision(), body.commandKey()));
    }

    @PostMapping("/api/v1/planned-workout-series/{seriesId}/stop")
    public WorkoutRecurrenceUseCase.Series stop(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable UUID seriesId,
                                                @Valid @RequestBody VersionRequest body) {
        return useCase.stop(new WorkoutRecurrenceUseCase.VersionCommand(actor(jwt), seriesId,
                body.expectedSeriesVersion(), body.commandKey()));
    }

    @PostMapping("/api/v1/planned-workout-series/{seriesId}/withdraw")
    public WorkoutRecurrenceUseCase.Series withdraw(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable UUID seriesId,
                                                    @Valid @RequestBody VersionRequest body) {
        return useCase.withdraw(new WorkoutRecurrenceUseCase.VersionCommand(actor(jwt), seriesId,
                body.expectedSeriesVersion(), body.commandKey()));
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    public record CreateRequest(@NotNull UUID planId, @NotNull UUID sourcePlanVersionId,
                                @NotNull Long expectedPlanAggregateVersion,
                                @NotNull LocalDate mondayAnchorDate, @NotBlank String timezone,
                                @NotEmpty List<@Valid ItemRequest> items, @NotBlank String commandKey) {
        WorkoutRecurrenceUseCase.Create command(UUID actor, UUID student) {
            return new WorkoutRecurrenceUseCase.Create(actor, student, planId, sourcePlanVersionId,
                    expectedPlanAggregateVersion, mondayAnchorDate, timezone,
                    items.stream().map(ItemRequest::command).toList(), commandKey);
        }
    }
    public record ItemRequest(@NotNull UUID planSessionId, int weekNumber, int dayNumber,
                              int sequenceNumber, @NotNull LocalDateTime firstLocalStart,
                              @NotNull LocalDateTime firstLocalEnd) {
        WorkoutRecurrenceUseCase.Item command() {
            return new WorkoutRecurrenceUseCase.Item(planSessionId, weekNumber, dayNumber,
                    sequenceNumber, firstLocalStart, firstLocalEnd);
        }
    }
    public record DecisionRequest(@NotNull Long expectedSeriesVersion, @NotBlank String decision,
                                  @NotBlank String commandKey) {}
    public record VersionRequest(@NotNull Long expectedSeriesVersion, @NotBlank String commandKey) {}
}
