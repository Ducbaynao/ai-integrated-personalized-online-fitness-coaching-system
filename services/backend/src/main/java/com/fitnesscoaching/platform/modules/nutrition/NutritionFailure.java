package com.fitnesscoaching.platform.modules.nutrition;

public final class NutritionFailure extends RuntimeException {
    private final String code;
    private final int status;

    public NutritionFailure(String code, int status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public int status() { return status; }
}
