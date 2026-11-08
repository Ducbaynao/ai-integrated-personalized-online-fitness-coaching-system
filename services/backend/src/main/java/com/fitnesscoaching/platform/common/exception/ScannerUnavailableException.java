package com.fitnesscoaching.platform.common.exception;

public class ScannerUnavailableException extends RuntimeException {

    public ScannerUnavailableException(String message) {
        super(message);
    }

    public ScannerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
