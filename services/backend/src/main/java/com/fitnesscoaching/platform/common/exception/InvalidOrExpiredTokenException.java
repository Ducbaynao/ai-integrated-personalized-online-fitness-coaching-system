package com.fitnesscoaching.platform.common.exception;

public class InvalidOrExpiredTokenException extends RuntimeException {

    public InvalidOrExpiredTokenException(String message) {
        super(message);
    }

    public InvalidOrExpiredTokenException() {
        super("The verification token is invalid, expired, or has already been used.");
    }
}
