package com.fitnesscoaching.platform.common.exception;

public class AccountUnavailableException extends RuntimeException {

    public AccountUnavailableException() {
        super("Account state does not permit login.");
    }

    public AccountUnavailableException(String message) {
        super(message);
    }
}
