package com.fitnesscoaching.platform.modules.schedule.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ScheduleProposalController.class)
public class ScheduleProposalErrorAdvice {
    private final Clock clock;
    public ScheduleProposalErrorAdvice(Clock clock) { this.clock = clock; }

    @ExceptionHandler(WorkoutScheduleFailure.class)
    public ResponseEntity<ScheduleError> schedule(WorkoutScheduleFailure failure) {
        List<ItemError> items = failure.clientItemId() == null ? List.of()
                : List.of(new ItemError(failure.clientItemId(), null, failure.code(), "Schedule item could not be confirmed"));
        return ResponseEntity.status(failure.status()).body(new ScheduleError(failure.code(),
                "Workout schedule proposal could not be processed", Instant.now(clock), RequestIdHolder.get(),
                List.of(), items));
    }

    @ExceptionHandler(WorkoutPlanFailure.class)
    public ResponseEntity<ScheduleError> concealedPlan(WorkoutPlanFailure failure) {
        int status = failure.error().status();
        String code = switch (status) {
            case 404 -> failure.error().name();
            case 409 -> "WORKOUT_SCHEDULE_PLAN_VERSION_STALE";
            case 403 -> "WORKOUT_SCHEDULE_ACCESS_DENIED";
            default -> "WORKOUT_SCHEDULE_VALIDATION_FAILED";
        };
        return ResponseEntity.status(status).body(new ScheduleError(code,
                "Workout schedule proposal could not be processed", Instant.now(clock), RequestIdHolder.get(),
                List.of(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ScheduleError> invalidBody(MethodArgumentNotValidException failure) {
        List<FieldErrorDto> fields = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDto(error.getField(), "VALIDATION_FAILED", "Invalid field"))
                .toList();
        return ResponseEntity.badRequest().body(new ScheduleError("WORKOUT_SCHEDULE_VALIDATION_FAILED",
                "Request validation failed", Instant.now(clock), RequestIdHolder.get(), fields, List.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ScheduleError> malformedBody(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().body(new ScheduleError("WORKOUT_SCHEDULE_VALIDATION_FAILED",
                "Request validation failed", Instant.now(clock), RequestIdHolder.get(), List.of(), List.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ScheduleError> invalidParameter(MethodArgumentTypeMismatchException failure) {
        return ResponseEntity.badRequest().body(new ScheduleError("WORKOUT_SCHEDULE_VALIDATION_FAILED",
                "Request validation failed", Instant.now(clock), RequestIdHolder.get(), List.of(), List.of()));
    }

    public record ScheduleError(String errorCode, String message, Instant timestamp, String requestId,
                                List<FieldErrorDto> fieldErrors, List<ItemError> itemErrors) {}
    public record ItemError(UUID clientItemId, String field, String code, String message) {}
}
