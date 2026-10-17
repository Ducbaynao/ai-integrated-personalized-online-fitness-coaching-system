package com.fitnesscoaching.platform.modules.chat;

import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.notification.InAppNotificationController;
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
@RestControllerAdvice(assignableTypes = {ChatController.class, InAppNotificationController.class})
public class ChatErrorAdvice {
    private final Clock clock;
    public ChatErrorAdvice(Clock clock) { this.clock = clock; }

    @ExceptionHandler(ChatFailure.class)
    public ResponseEntity<ErrorResponse> failed(ChatFailure failure) {
        return ResponseEntity.status(failure.status()).body(ErrorResponse.of(failure.code(),
                "Chat request could not be completed", Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_FAILED",
                "Invalid chat request", Instant.now(clock), RequestIdHolder.get()));
    }
}
