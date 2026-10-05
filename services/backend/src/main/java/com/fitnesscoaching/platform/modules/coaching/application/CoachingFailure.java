package com.fitnesscoaching.platform.modules.coaching.application;

import org.springframework.http.HttpStatus;

public class CoachingFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public CoachingFailure(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
