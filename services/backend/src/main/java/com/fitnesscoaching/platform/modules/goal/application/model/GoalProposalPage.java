package com.fitnesscoaching.platform.modules.goal.application.model;

import com.fitnesscoaching.platform.modules.goal.domain.GoalProposal;

import java.util.List;

public record GoalProposalPage(
        List<GoalProposal> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public GoalProposalPage {
        items = items != null ? List.copyOf(items) : List.of();
    }
}
