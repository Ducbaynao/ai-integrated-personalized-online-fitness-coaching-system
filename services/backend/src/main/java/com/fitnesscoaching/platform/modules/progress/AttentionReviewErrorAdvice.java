package com.fitnesscoaching.platform.modules.progress;

import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
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
@RestControllerAdvice(assignableTypes=AttentionReviewController.class)
public class AttentionReviewErrorAdvice {
    private final Clock clock;
    public AttentionReviewErrorAdvice(Clock clock) { this.clock=clock; }
    @ExceptionHandler(AttentionReviewFailure.class)
    public ResponseEntity<ErrorResponse> failure(AttentionReviewFailure failure) {
        return ResponseEntity.status(failure.status()).body(ErrorResponse.of(failure.code(),
                "Attention or review request could not be completed",Instant.now(clock),RequestIdHolder.get()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_FAILED",
                "Invalid attention or review request",Instant.now(clock),RequestIdHolder.get()));
    }
}
