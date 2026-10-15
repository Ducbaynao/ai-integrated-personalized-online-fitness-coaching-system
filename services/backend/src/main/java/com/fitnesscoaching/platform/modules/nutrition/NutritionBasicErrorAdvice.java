package com.fitnesscoaching.platform.modules.nutrition;

import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.time.Instant;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = NutritionBasicController.class)
public class NutritionBasicErrorAdvice {
    private final Clock clock;
    public NutritionBasicErrorAdvice(Clock clock) { this.clock = clock; }

    @ExceptionHandler(NutritionFailure.class)
    public ResponseEntity<ErrorResponse> failed(NutritionFailure failure) {
        return ResponseEntity.status(failure.status()).body(ErrorResponse.of(failure.code(),
                "Nutrition request could not be completed", Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_FAILED",
                "Invalid nutrition request", Instant.now(clock), RequestIdHolder.get()));
    }
}
