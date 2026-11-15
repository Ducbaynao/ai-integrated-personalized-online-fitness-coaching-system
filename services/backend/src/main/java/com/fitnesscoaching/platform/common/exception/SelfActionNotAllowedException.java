package com.fitnesscoaching.platform.common.exception;

public class SelfActionNotAllowedException extends RuntimeException {
    public SelfActionNotAllowedException(String message) {
        super(message);
    }
}
