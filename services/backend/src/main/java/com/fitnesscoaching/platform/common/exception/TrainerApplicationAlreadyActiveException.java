package com.fitnesscoaching.platform.common.exception;

public class TrainerApplicationAlreadyActiveException extends RuntimeException {

    public TrainerApplicationAlreadyActiveException(String message) {
        super(message);
    }
}
