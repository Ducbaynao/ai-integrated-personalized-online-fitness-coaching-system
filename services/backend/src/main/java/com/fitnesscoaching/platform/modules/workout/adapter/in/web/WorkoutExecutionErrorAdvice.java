package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WorkoutExecutionController.class)
public class WorkoutExecutionErrorAdvice {
    private final Clock clock;

    public WorkoutExecutionErrorAdvice(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(WorkoutExecutionFailure.class)
    public ResponseEntity<ErrorResponse> handle(WorkoutExecutionFailure failure) {
        return ResponseEntity.status(failure.error().status()).body(ErrorResponse.of(
                failure.error().name(), "Workout execution operation could not be completed",
                Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> validation(ConstraintViolationException failure) {
        List<FieldErrorDto> fields = failure.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDto(field(violation.getPropertyPath().toString()),
                        violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                        violation.getMessage()))
                .toList();
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_FAILED", "Request validation failed",
                Instant.now(clock), RequestIdHolder.get(), fields));
    }

    private static String field(String path) {
        int separator = path.lastIndexOf('.');
        return separator < 0 ? path : path.substring(separator + 1);
    }
}
