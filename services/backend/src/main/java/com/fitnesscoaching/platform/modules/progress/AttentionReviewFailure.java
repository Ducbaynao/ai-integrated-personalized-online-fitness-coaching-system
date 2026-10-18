package com.fitnesscoaching.platform.modules.progress;

public final class AttentionReviewFailure extends RuntimeException {
    private final int status;
    public AttentionReviewFailure(String code, int status) { super(code); this.status = status; }
    public int status() { return status; }
    public String code() { return getMessage(); }
}
