package com.fitnesscoaching.platform.common.exception;

public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Refresh token is invalid, expired, revoked, or has already been used.");
    }
}
