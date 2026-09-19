package com.fitnesscoaching.platform.common.exception;

import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<FieldErrorDto> fieldErrors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new FieldErrorDto(
                    fieldError.getField(),
                    fieldError.getCode() != null ? fieldError.getCode() : "Invalid",
                    fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "Invalid value"
            ));
        }

        ErrorResponse response = ErrorResponse.of(
                "VALIDATION_FAILED",
                "Request validation failed",
                Instant.now(clock),
                RequestIdHolder.get(),
                fieldErrors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        ErrorResponse response = ErrorResponse.of(
                "VALIDATION_FAILED",
                "Request validation failed",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        ErrorResponse response = ErrorResponse.of(
                "VALIDATION_FAILED",
                "Malformed request body",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException ex) {
        ErrorResponse response = ErrorResponse.of(
                "EMAIL_ALREADY_REGISTERED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(InvalidOrExpiredTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrExpiredToken(InvalidOrExpiredTokenException ex) {
        ErrorResponse response = ErrorResponse.of(
                "INVALID_OR_EXPIRED_TOKEN",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (msg.contains("users_email_key") || msg.contains("email")) {
            ErrorResponse response = ErrorResponse.of(
                    "EMAIL_ALREADY_REGISTERED",
                    "Email is already registered.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        log.error("Data integrity violation", ex);
        ErrorResponse response = ErrorResponse.of(
                "DATA_INTEGRITY_VIOLATION",
                "A database constraint was violated.",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled exception caught in controller advice", ex);
        ErrorResponse response = ErrorResponse.of(
                "INTERNAL_SERVER_ERROR",
                "An unexpected server error occurred.",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
