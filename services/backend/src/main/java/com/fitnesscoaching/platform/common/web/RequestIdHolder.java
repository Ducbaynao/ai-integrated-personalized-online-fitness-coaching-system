package com.fitnesscoaching.platform.common.web;

import org.slf4j.MDC;

import java.util.UUID;

public final class RequestIdHolder {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String MDC_KEY = "requestId";

    private RequestIdHolder() {
    }

    public static String get() {
        String requestId = MDC.get(MDC_KEY);
        return requestId != null ? requestId : UUID.randomUUID().toString();
    }

    public static UUID getAsUuid() {
        String reqIdStr = get();
        try {
            return UUID.fromString(reqIdStr);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }
}
