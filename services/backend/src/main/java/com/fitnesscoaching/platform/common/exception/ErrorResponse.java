package com.fitnesscoaching.platform.common.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public record ErrorResponse(
        @JsonProperty("errorCode") String errorCode,
        @JsonProperty("message") String message,
        @JsonProperty("timestamp") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp,
        @JsonProperty("requestId") String requestId,
        @JsonProperty("fieldErrors") List<FieldErrorDto> fieldErrors
) {
    public ErrorResponse {
        if (fieldErrors == null) {
            fieldErrors = Collections.emptyList();
        }
    }

    public static ErrorResponse of(String errorCode, String message, Instant timestamp, String requestId) {
        return new ErrorResponse(errorCode, message, timestamp, requestId, Collections.emptyList());
    }

    public static ErrorResponse of(String errorCode, String message, Instant timestamp, String requestId, List<FieldErrorDto> fieldErrors) {
        return new ErrorResponse(errorCode, message, timestamp, requestId, fieldErrors);
    }
}
