package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WorkoutScheduleBatchController.class)
public class WorkoutScheduleBatchErrorAdvice {
    private final Clock clock;
    public WorkoutScheduleBatchErrorAdvice(Clock clock) { this.clock = clock; }

    @ExceptionHandler(WorkoutScheduleFailure.class)
    public ResponseEntity<ScheduleError> handle(WorkoutScheduleFailure failure) {
        List<ItemError> items = failure.clientItemId() == null ? List.of()
                : List.of(new ItemError(failure.clientItemId(), null, failure.code(), "Schedule item could not be confirmed"));
        return ResponseEntity.status(failure.status()).body(new ScheduleError(failure.code(),
                "Workout schedule could not be confirmed", Instant.now(clock), RequestIdHolder.get(),
                List.of(), items));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ScheduleError> invalidBody(MethodArgumentNotValidException failure) {
        List<FieldErrorDto> fields = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDto(error.getField(), "VALIDATION_FAILED", "Invalid field"))
                .toList();
        return ResponseEntity.badRequest().body(new ScheduleError("VALIDATION_FAILED", "Request validation failed",
                Instant.now(clock), RequestIdHolder.get(), fields, List.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ScheduleError> malformedBody(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().body(new ScheduleError("VALIDATION_FAILED", "Request validation failed",
                Instant.now(clock), RequestIdHolder.get(), List.of(), List.of()));
    }

    public record ScheduleError(String errorCode, String message, Instant timestamp, String requestId,
                                List<FieldErrorDto> fieldErrors, List<ItemError> itemErrors) {}
    public record ItemError(UUID clientItemId, String field, String code, String message) {}
}
