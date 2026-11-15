package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserPage;

import java.util.List;

public record AdminUserPageResponse(
        List<AdminUserSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static AdminUserPageResponse fromDomain(AdminUserPage page) {
        return new AdminUserPageResponse(
                page.content().stream().map(AdminUserSummaryResponse::fromDomain).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages()
        );
    }
}
