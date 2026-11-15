package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

public record ForceLogoutResponse(
        int revokedSessionCount,
        String message
) {
}
