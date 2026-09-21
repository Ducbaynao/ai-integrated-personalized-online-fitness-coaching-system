package com.fitnesscoaching.platform.common.exception;

public class TrainerSlugAlreadyExistsException extends RuntimeException {
    public TrainerSlugAlreadyExistsException(String message) {
        super(message);
    }
}
