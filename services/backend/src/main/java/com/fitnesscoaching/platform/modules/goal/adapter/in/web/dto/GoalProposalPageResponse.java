package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.application.model.GoalProposalPage;

import java.util.List;

public record GoalProposalPageResponse(
        List<GoalProposalResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public static GoalProposalPageResponse fromDomain(GoalProposalPage domainPage) {
        return new GoalProposalPageResponse(
                domainPage.items().stream().map(GoalProposalResponse::fromDomain).toList(),
                domainPage.page(),
                domainPage.size(),
                domainPage.totalItems(),
                domainPage.totalPages()
        );
    }
}
