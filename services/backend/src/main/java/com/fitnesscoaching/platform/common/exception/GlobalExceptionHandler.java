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

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(
                "INVALID_CREDENTIALS", ex.getMessage(), Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler(AccountUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleAccountUnavailable(AccountUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                "ACCOUNT_UNAVAILABLE", ex.getMessage(), Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(
                "UNAUTHORIZED",
                "Authenticated user does not exist.",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(
                "INVALID_REFRESH_TOKEN", ex.getMessage(), Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler(StudentProfileAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleStudentProfileAlreadyExists(StudentProfileAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "STUDENT_PROFILE_ALREADY_EXISTS",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(StudentCapabilityRevokedException.class)
    public ResponseEntity<ErrorResponse> handleStudentCapabilityRevoked(StudentCapabilityRevokedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "STUDENT_CAPABILITY_REVOKED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(StudentCapabilityUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleStudentCapabilityUnavailable(StudentCapabilityUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                "STUDENT_CAPABILITY_UNAVAILABLE",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(StudentProfileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStudentProfileNotFound(StudentProfileNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                "STUDENT_PROFILE_NOT_FOUND",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
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

        if (msg.contains("student_profiles_pkey") || msg.contains("student_profiles")) {
            ErrorResponse response = ErrorResponse.of(
                    "STUDENT_PROFILE_ALREADY_EXISTS",
                    "Student profile already exists for this account.",
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

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        ErrorResponse response = ErrorResponse.of(
                "ACCESS_DENIED",
                "Access is denied.",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(ApplicationValidationException.class)
    public ResponseEntity<ErrorResponse> handleApplicationValidation(ApplicationValidationException ex) {
        ErrorResponse response = ErrorResponse.of(
                "VALIDATION_FAILED",
                ex.getMessage() != null ? ex.getMessage() : "Request validation failed",
                Instant.now(clock),
                RequestIdHolder.get(),
                ex.getFieldErrors()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(SystemRoleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSystemRoleNotFound(SystemRoleNotFoundException ex) {
        log.error("System role missing from database: {}", ex.getMessage());
        ErrorResponse response = ErrorResponse.of(
                "INTERNAL_SERVER_ERROR",
                "System configuration error: required role does not exist.",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
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
