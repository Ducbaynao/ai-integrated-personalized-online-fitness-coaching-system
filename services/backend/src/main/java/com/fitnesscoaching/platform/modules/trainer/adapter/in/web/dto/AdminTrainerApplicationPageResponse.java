package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.trainer.application.model.AdminTrainerApplicationPage;

import java.util.List;

public record AdminTrainerApplicationPageResponse(
        List<AdminTrainerApplicationResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public static AdminTrainerApplicationPageResponse fromDomain(AdminTrainerApplicationPage page) {
        if (page == null) {
            return null;
        }
        List<AdminTrainerApplicationResponse> responses = page.items().stream()
                .map(AdminTrainerApplicationResponse::fromDomain)
                .toList();
        return new AdminTrainerApplicationPageResponse(
                responses,
                page.page(),
                page.size(),
                page.totalItems(),
                page.totalPages()
        );
    }
}
