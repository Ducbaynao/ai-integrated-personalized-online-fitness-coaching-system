package com.fitnesscoaching.platform.common.exception;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Email or password is invalid.");
    }
}
