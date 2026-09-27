package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import java.util.List;

public record FitnessGoalPageResponse(
        List<FitnessGoalResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public FitnessGoalPageResponse {
        items = items != null ? List.copyOf(items) : List.of();
    }
}
