package com.fitnesscoaching.platform.modules.chat;

public final class ChatFailure extends RuntimeException {
    private final int status;
    public ChatFailure(String code, int status) { super(code); this.status = status; }
    public int status() { return status; }
    public String code() { return getMessage(); }
}
