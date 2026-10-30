package com.fitnesscoaching.platform.common.exception;

import java.util.Collections;
import java.util.List;

public class AiRunValidationException extends RuntimeException {

    private final List<FieldErrorDto> fieldErrors;

    public AiRunValidationException(String message) {
        super(message);
        this.fieldErrors = Collections.emptyList();
    }

    public AiRunValidationException(String message, Throwable cause) {
        super(message, cause);
        this.fieldErrors = Collections.emptyList();
    }

    public AiRunValidationException(String message, List<FieldErrorDto> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors != null ? Collections.unmodifiableList(fieldErrors) : Collections.emptyList();
    }

    public List<FieldErrorDto> getFieldErrors() {
        return fieldErrors;
    }
}
