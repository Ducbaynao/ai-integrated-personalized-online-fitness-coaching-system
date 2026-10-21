package com.fitnesscoaching.platform.modules.schedule.adapter.in.web;

import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentFailure;
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

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AppointmentController.class)
public class AppointmentErrorAdvice {
    private final Clock clock;
    public AppointmentErrorAdvice(Clock clock) { this.clock = clock; }

    @ExceptionHandler(AppointmentFailure.class)
    public ResponseEntity<Error> domain(AppointmentFailure failure) {
        return ResponseEntity.status(failure.status()).body(new Error(failure.code(),
                "Appointment could not be processed", Instant.now(clock), RequestIdHolder.get(), List.of()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Error> validation(Exception failure) {
        return ResponseEntity.badRequest().body(new Error("VALIDATION_FAILED", "Invalid request",
                Instant.now(clock), RequestIdHolder.get(), List.of()));
    }

    public record Error(String errorCode, String message, Instant timestamp, String requestId,
                        List<Object> fieldErrors) {}
}
