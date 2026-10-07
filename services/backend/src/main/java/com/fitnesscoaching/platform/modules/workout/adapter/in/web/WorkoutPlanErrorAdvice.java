package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.fitnesscoaching.platform.modules.workout.adapter.in.web")
public class WorkoutPlanErrorAdvice {
    private final Clock clock;
    public WorkoutPlanErrorAdvice(Clock clock) { this.clock = clock; }

    @ExceptionHandler(WorkoutPlanFailure.class)
    public ResponseEntity<ErrorResponse> handle(WorkoutPlanFailure failure) {
        return ResponseEntity.status(failure.error().status()).body(ErrorResponse.of(
                failure.error().name(), "Workout plan operation could not be completed",
                Instant.now(clock), RequestIdHolder.get()));
    }
}
