package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.Destination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
public class WorkoutScheduleChangeController {
    private final WorkoutScheduleChangeUseCase changes;
    public WorkoutScheduleChangeController(WorkoutScheduleChangeUseCase changes) { this.changes = changes; }

    @PostMapping("/api/v1/planned-workouts/{occurrenceId}/reschedule")
    public ResponseEntity<WorkoutScheduleChangeUseCase.ChangeResult> reschedule(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID occurrenceId,
            @Valid @RequestBody RescheduleRequest request) {
        var result = changes.reschedule(new WorkoutScheduleChangeUseCase.Reschedule(occurrenceId,
                UUID.fromString(jwt.getSubject()), value(request.expectedOccurrenceVersion()),
                request.destination().toCommand(), request.reason(), request.commandKey()));
        return ResponseEntity.created(URI.create("/api/v1/planned-workouts/" + result.replacementOccurrenceId()))
                .body(result);
    }

    @PostMapping("/api/v1/planned-workouts/{occurrenceId}/cancel")
    public WorkoutScheduleChangeUseCase.ChangeResult cancel(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID occurrenceId,
            @Valid @RequestBody CancelRequest request) {
        return changes.cancel(new WorkoutScheduleChangeUseCase.Cancel(occurrenceId,
                UUID.fromString(jwt.getSubject()), value(request.expectedOccurrenceVersion()),
                request.reason(), request.commandKey()));
    }

    private static long value(Long version) { return version == null ? -1 : version; }
    public record RescheduleRequest(@NotNull Long expectedOccurrenceVersion,
                                    @NotNull @Valid DestinationRequest destination,
                                    @Size(max = 1000) String reason, @NotBlank String commandKey) {}
    public record CancelRequest(@NotNull Long expectedOccurrenceVersion,
                                @Size(max = 1000) String reason, @NotBlank String commandKey) {}
    public record DestinationRequest(@NotNull LocalDateTime localStart, @NotNull LocalDateTime localEnd,
                                     @NotBlank String timezone, @NotBlank String startUtcOffset,
                                     @NotBlank String endUtcOffset) {
        Destination toCommand() {
            return new Destination(localStart, localEnd, timezone, startUtcOffset, endUtcOffset);
        }
    }
}
