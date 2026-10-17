package com.fitnesscoaching.platform.common.exception;

public class ContentConflictException extends RuntimeException {
    public ContentConflictException(String message) {
        super(message);
    }

    public ContentConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
