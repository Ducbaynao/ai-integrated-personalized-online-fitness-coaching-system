package com.fitnesscoaching.platform.modules.measurement.domain;

public class MeasurementFailure extends RuntimeException {
    private final MeasurementError error;

    public MeasurementFailure(MeasurementError error, String message) {
        super(message);
        this.error = error;
    }

    public MeasurementError error() {
        return error;
    }
}
