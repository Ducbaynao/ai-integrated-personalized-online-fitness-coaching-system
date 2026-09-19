package com.fitnesscoaching.platform.common.exception;

public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(String message) {
        super(message);
    }

    public EmailAlreadyRegisteredException() {
        super("Email is already registered.");
    }
}
