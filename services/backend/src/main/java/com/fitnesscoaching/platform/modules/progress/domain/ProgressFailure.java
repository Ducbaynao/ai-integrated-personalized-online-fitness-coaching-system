package com.fitnesscoaching.platform.modules.progress.domain;

public class ProgressFailure extends RuntimeException {
    private final ProgressError error;

    public ProgressFailure(ProgressError error, String message) {
        super(message);
        this.error = error;
    }

    public ProgressFailure(ProgressError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public ProgressError error() {
        return error;
    }
}
