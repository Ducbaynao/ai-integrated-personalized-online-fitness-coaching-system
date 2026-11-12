package com.fitnesscoaching.platform.modules.measurement.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.exception.ScannerUnavailableException;
import com.fitnesscoaching.platform.common.exception.StorageUnavailableException;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementFailure;
import com.fitnesscoaching.platform.modules.progress.adapter.in.web.ProgressDashboardController;
import com.fitnesscoaching.platform.modules.progress.domain.ProgressFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.time.Instant;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        MeasurementController.class,
        MetricCatalogController.class,
        ProgressPhotoController.class,
        ProgressDashboardController.class
})
public class MeasurementErrorAdvice {

    private final Clock clock;

    public MeasurementErrorAdvice(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MeasurementFailure.class)
    public ResponseEntity<ErrorResponse> handleMeasurementFailure(MeasurementFailure failure) {
        HttpStatus status = HttpStatus.valueOf(failure.error().status());
        ErrorResponse response = ErrorResponse.of(
                failure.error().name(),
                failure.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get()
        );
        return ResponseEntity.status(status).body(response);
    }

    @ExceptionHandler(ProgressFailure.class)
    public ResponseEntity<ErrorResponse> handleProgressFailure(ProgressFailure failure) {
        HttpStatus status = switch (failure.error()) {
            case PROGRESS_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case STUDENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_WINDOW_PARAMETERS -> HttpStatus.BAD_REQUEST;
        };
        ErrorResponse response = ErrorResponse.of(
                failure.error().name(),
                failure.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get()
        );
        return ResponseEntity.status(status).body(response);
    }

    @ExceptionHandler(ScannerUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleScannerUnavailable(ScannerUnavailableException ex) {
        ErrorResponse response = ErrorResponse.of(
                "SCANNER_UNAVAILABLE",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleStorageUnavailable(StorageUnavailableException ex) {
        ErrorResponse response = ErrorResponse.of(
                "STORAGE_UNAVAILABLE",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @ExceptionHandler(ApplicationValidationException.class)
    public ResponseEntity<ErrorResponse> handleApplicationValidation(ApplicationValidationException ex) {
        ErrorResponse response = ErrorResponse.of(
                "VALIDATION_FAILED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                ex.getFieldErrors()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccessException(org.springframework.dao.DataAccessException ex) {
        String msg = ex.getMessage();
        if (msg != null && (msg.contains("Measurement unit is incompatible with its metric") || msg.contains("Unknown metric definition"))) {
            ErrorResponse response = ErrorResponse.of(
                    "VALIDATION_FAILED",
                    "Database constraint rejected measurement: " + (ex.getRootCause() != null ? ex.getRootCause().getMessage() : ex.getMessage()),
                    Instant.now(clock),
                    RequestIdHolder.get()
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        throw ex;
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            jakarta.validation.ConstraintViolationException.class,
            java.time.DateTimeException.class
    })
    public ResponseEntity<ErrorResponse> handleValidation(Exception ex) {
        ErrorResponse response = ErrorResponse.of(
                "VALIDATION_FAILED",
                "Invalid measurement or progress request parameters: " + ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
}
