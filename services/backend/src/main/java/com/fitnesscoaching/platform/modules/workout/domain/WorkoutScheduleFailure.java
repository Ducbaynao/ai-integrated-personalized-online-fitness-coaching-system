package com.fitnesscoaching.platform.modules.workout.domain;

import java.util.UUID;

public class WorkoutScheduleFailure extends RuntimeException {
    private final String code;
    private final int status;
    private final UUID clientItemId;

    public WorkoutScheduleFailure(String code, int status) { this(code, status, null); }
    public WorkoutScheduleFailure(String code, int status, UUID clientItemId) {
        super(code);
        this.code = code;
        this.status = status;
        this.clientItemId = clientItemId;
    }
    public String code() { return code; }
    public int status() { return status; }
    public UUID clientItemId() { return clientItemId; }
}
