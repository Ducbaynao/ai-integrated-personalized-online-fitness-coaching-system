package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WorkoutScheduleCalendarController.class)
public class WorkoutScheduleCalendarErrorAdvice {
    private final Clock clock;
    public WorkoutScheduleCalendarErrorAdvice(Clock clock) { this.clock = clock; }

    @ExceptionHandler(WorkoutScheduleFailure.class)
    public ResponseEntity<?> failure(WorkoutScheduleFailure failure) {
        if (failure.status() == 403)
            return ResponseEntity.status(403).body(ErrorResponse.of(failure.code(),
                    "Workout schedule is not available", Instant.now(clock), RequestIdHolder.get()));
        return invalid();
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<WorkoutScheduleBatchErrorAdvice.ScheduleError> invalidQuery(Exception failure) {
        return invalid();
    }

    private ResponseEntity<WorkoutScheduleBatchErrorAdvice.ScheduleError> invalid() {
        return ResponseEntity.badRequest().body(new WorkoutScheduleBatchErrorAdvice.ScheduleError(
                "WORKOUT_SCHEDULE_WINDOW_INVALID", "Invalid calendar window", Instant.now(clock),
                RequestIdHolder.get(), List.of(), List.of()));
    }
}
