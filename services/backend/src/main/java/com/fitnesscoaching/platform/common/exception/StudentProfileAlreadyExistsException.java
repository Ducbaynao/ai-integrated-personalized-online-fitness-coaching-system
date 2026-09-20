package com.fitnesscoaching.platform.common.exception;

public class StudentProfileAlreadyExistsException extends RuntimeException {

    public StudentProfileAlreadyExistsException(String message) {
        super(message);
    }
}
