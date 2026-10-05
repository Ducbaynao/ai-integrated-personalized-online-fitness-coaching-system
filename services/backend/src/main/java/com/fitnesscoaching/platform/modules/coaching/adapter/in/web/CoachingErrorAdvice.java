package com.fitnesscoaching.platform.modules.coaching.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.coaching.application.CoachingFailure;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;
import java.sql.SQLException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = CoachingController.class)
public class CoachingErrorAdvice {
    private static final Logger log = LoggerFactory.getLogger(CoachingErrorAdvice.class);
    private final Clock clock;

    public CoachingErrorAdvice(Clock clock) { this.clock = clock; }

    @ExceptionHandler(CoachingFailure.class)
    public ResponseEntity<ErrorResponse> handle(CoachingFailure ex) {
        return ResponseEntity.status(ex.status()).body(ErrorResponse.of(
                ex.code(), "Coaching operation could not be completed", Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> validation(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(
                "VALIDATION_FAILED", "Request validation failed", Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException ex) {
        DatabaseError error = databaseError(ex);
        String code = conflictCode(error);
        if (code == null) {
            log.error("Unhandled coaching integrity violation", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(
                    "INTERNAL_SERVER_ERROR", "An unexpected server error occurred.",
                    Instant.now(clock), RequestIdHolder.get()));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                code, "Coaching operation conflicts with current state", Instant.now(clock), RequestIdHolder.get()));
    }

    private static String conflictCode(DatabaseError error) {
        if ("PZ001".equals(error.sqlState())) {
            return "COACHING_PERIOD_CONFLICT";
        }
        if ("23505".equals(error.sqlState()) && error.constraint() != null) {
            return switch (error.constraint()) {
                case "uq_coaching_current_student" -> "COACHING_STUDENT_ALREADY_ASSIGNED";
                case "uq_active_coaching_relationship_pair" -> "COACHING_REQUEST_ALREADY_PENDING";
                case "uq_coaching_pending_resume" -> "COACHING_RESUME_ALREADY_PENDING";
                case "coaching_command_receipts_pkey" -> "COACHING_IDEMPOTENCY_CONFLICT";
                default -> null;
            };
        }
        if ("23P01".equals(error.sqlState()) && "coaching_period_no_overlap".equals(error.constraint())) {
            return "COACHING_PERIOD_CONFLICT";
        }
        return null;
    }

    private static DatabaseError databaseError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                String constraint = constraintName(sql);
                return new DatabaseError(sql.getSQLState(),
                        constraint == null ? null : constraint.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return new DatabaseError(null, null);
    }

    private static String constraintName(Throwable error) {
        try {
            Object serverError = error.getClass().getMethod("getServerErrorMessage").invoke(error);
            if (serverError == null) { return null; }
            Object value = serverError.getClass().getMethod("getConstraint").invoke(serverError);
            return value instanceof String name && !name.isBlank() ? name : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private record DatabaseError(String sqlState, String constraint) {}
}
