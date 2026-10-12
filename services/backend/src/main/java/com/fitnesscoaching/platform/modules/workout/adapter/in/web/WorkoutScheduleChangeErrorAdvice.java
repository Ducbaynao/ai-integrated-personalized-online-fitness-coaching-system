package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
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

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WorkoutScheduleChangeController.class)
public class WorkoutScheduleChangeErrorAdvice {
    private final Clock clock;
    public WorkoutScheduleChangeErrorAdvice(Clock clock) { this.clock = clock; }

    @ExceptionHandler(WorkoutScheduleFailure.class)
    public ResponseEntity<ErrorResponse> failed(WorkoutScheduleFailure failure) {
        return ResponseEntity.status(failure.status()).body(ErrorResponse.of(failure.code(),
                "Workout schedule change is not available", Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception failure) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED",
                "Invalid schedule change request", Instant.now(clock), RequestIdHolder.get()));
    }
}
