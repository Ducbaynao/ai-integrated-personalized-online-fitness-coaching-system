package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.ConfirmBatch;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.Item;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
public class WorkoutScheduleBatchController {
    private final WorkoutScheduleBatchUseCase batches;
    public WorkoutScheduleBatchController(WorkoutScheduleBatchUseCase batches) { this.batches = batches; }

    @PostMapping("/api/v1/planned-workout-batches")
    public ResponseEntity<WorkoutScheduleBatchUseCase.ConfirmedBatch> confirm(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Request request) {
        var result = batches.confirmDirect(new ConfirmBatch(UUID.fromString(jwt.getSubject()), request.planId(),
                request.sourcePlanVersionId(), request.expectedPlanAggregateVersion() == null ? -1 : request.expectedPlanAggregateVersion(), request.weekAnchorDate(),
                request.timezone(), request.items().stream().map(InputItem::toCommand).toList(), request.commandKey()));
        return ResponseEntity.status(201).body(result);
    }

    public record Request(@NotNull UUID planId, @NotNull UUID sourcePlanVersionId,
                          @NotNull Long expectedPlanAggregateVersion, @NotNull LocalDate weekAnchorDate,
                          @NotBlank String timezone, @NotEmpty List<@Valid InputItem> items,
                          @NotBlank String commandKey) {}
    public record InputItem(@NotNull UUID clientItemId, @NotNull UUID planSessionId,
                            int weekNumber, int dayNumber, int sequenceNumber,
                            @NotNull LocalDateTime localStart, @NotNull LocalDateTime localEnd,
                            @NotBlank String startUtcOffset, @NotBlank String endUtcOffset) {
        Item toCommand() { return new Item(clientItemId, planSessionId, weekNumber, dayNumber, sequenceNumber,
                localStart, localEnd, startUtcOffset, endUtcOffset); }
    }
}
