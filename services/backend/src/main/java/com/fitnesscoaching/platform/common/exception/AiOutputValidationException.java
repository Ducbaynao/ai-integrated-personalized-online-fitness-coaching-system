package com.fitnesscoaching.platform.common.exception;

public class AiOutputValidationException extends RuntimeException {
    public AiOutputValidationException(String message) {
        super(message);
    }

    public AiOutputValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
