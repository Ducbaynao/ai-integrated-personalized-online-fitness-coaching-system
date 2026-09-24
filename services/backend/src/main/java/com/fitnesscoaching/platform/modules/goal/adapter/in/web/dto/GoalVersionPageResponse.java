package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import java.util.List;

public record GoalVersionPageResponse(
        List<GoalVersionResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public GoalVersionPageResponse {
        items = items != null ? List.copyOf(items) : List.of();
    }
}
