package com.fitnesscoaching.platform.modules.schedule.application.service;

public final class AppointmentFailure extends RuntimeException {
    private final String code;
    private final int status;

    public AppointmentFailure(String code, int status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public int status() { return status; }
}
