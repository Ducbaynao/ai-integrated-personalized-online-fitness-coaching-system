package com.fitnesscoaching.platform.common.exception;

public class TrainerProfileAlreadyExistsException extends RuntimeException {
    public TrainerProfileAlreadyExistsException(String message) {
        super(message);
    }
}
