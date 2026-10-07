package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.CoachingRelationshipRequiredException;
import com.fitnesscoaching.platform.common.exception.DataSharingAccessLevelInsufficientException;
import com.fitnesscoaching.platform.common.exception.DataSharingPermissionRequiredException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerNotEligibleException;
import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanError;
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

    @ExceptionHandler(CoachingRelationshipRequiredException.class)
    public ResponseEntity<ErrorResponse> coachingPeriodRequired(CoachingRelationshipRequiredException failure) {
        return stable(WorkoutPlanError.COACHING_PERIOD_REQUIRED);
    }

    @ExceptionHandler({DataSharingPermissionRequiredException.class,
            DataSharingAccessLevelInsufficientException.class, TrainerCapabilityUnavailableException.class,
            TrainerNotEligibleException.class, AccountUnavailableException.class, UserNotFoundException.class})
    public ResponseEntity<ErrorResponse> accessDenied(RuntimeException failure) {
        return stable(WorkoutPlanError.WORKOUT_PLAN_ACCESS_DENIED);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> validation(IllegalArgumentException failure) {
        return stable(WorkoutPlanError.VALIDATION_FAILED);
    }

    private ResponseEntity<ErrorResponse> stable(WorkoutPlanError error) {
        return ResponseEntity.status(error.status()).body(ErrorResponse.of(error.name(),
                "Workout plan operation could not be completed", Instant.now(clock), RequestIdHolder.get()));
    }
}
