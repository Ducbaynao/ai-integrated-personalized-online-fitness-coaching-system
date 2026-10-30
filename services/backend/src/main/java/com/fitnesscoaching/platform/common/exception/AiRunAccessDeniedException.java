package com.fitnesscoaching.platform.common.exception;

public class AiRunAccessDeniedException extends RuntimeException {
    public AiRunAccessDeniedException(String message) {
        super(message);
    }

    public AiRunAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
