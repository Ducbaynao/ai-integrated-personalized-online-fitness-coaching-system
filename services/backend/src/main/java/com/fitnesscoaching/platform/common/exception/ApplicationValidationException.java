package com.fitnesscoaching.platform.common.exception;

import java.util.Collections;
import java.util.List;

public class ApplicationValidationException extends RuntimeException {

    private final List<FieldErrorDto> fieldErrors;

    public ApplicationValidationException(String message) {
        super(message);
        this.fieldErrors = Collections.emptyList();
    }

    public ApplicationValidationException(String message, List<FieldErrorDto> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors != null ? Collections.unmodifiableList(fieldErrors) : Collections.emptyList();
    }

    public List<FieldErrorDto> getFieldErrors() {
        return fieldErrors;
    }
}
