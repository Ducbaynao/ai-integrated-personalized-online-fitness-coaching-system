package com.fitnesscoaching.platform.common.exception;

public class DataSharingPermissionRequiredException extends RuntimeException {
    public DataSharingPermissionRequiredException(String message) {
        super(message);
    }
}
