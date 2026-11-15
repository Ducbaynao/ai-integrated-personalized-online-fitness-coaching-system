package com.fitnesscoaching.platform.modules.measurement.domain;

public enum MeasurementError {
    MEASUREMENT_ACCESS_DENIED(403),
    MEASUREMENT_NOT_FOUND(404),
    METRIC_NOT_FOUND(404),
    UNIT_INCOMPATIBLE(400),
    MEASUREMENT_CORRECTION_CONFLICT(409),
    PHOTO_NOT_FOUND(404),
    VALIDATION_FAILED(400);

    private final int status;

    MeasurementError(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
