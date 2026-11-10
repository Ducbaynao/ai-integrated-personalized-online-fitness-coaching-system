package com.fitnesscoaching.platform.common.exception;

public class TargetUserNotFoundException extends RuntimeException {
    public TargetUserNotFoundException(String message) {
        super(message);
    }
}
