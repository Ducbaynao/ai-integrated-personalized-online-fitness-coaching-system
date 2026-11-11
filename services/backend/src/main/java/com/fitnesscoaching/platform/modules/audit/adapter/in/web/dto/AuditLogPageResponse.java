package com.fitnesscoaching.platform.modules.audit.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.audit.application.model.AuditLogPage;

import java.util.Collections;
import java.util.List;

public record AuditLogPageResponse(
        List<AuditLogEntryResponse> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
    public static AuditLogPageResponse fromDomain(AuditLogPage pageModel) {
        if (pageModel == null) return new AuditLogPageResponse(Collections.emptyList(), 0, 0, 0, 0);
        List<AuditLogEntryResponse> items = pageModel.content() != null
                ? pageModel.content().stream().map(AuditLogEntryResponse::fromDomain).toList()
                : Collections.emptyList();
        return new AuditLogPageResponse(
                items,
                pageModel.totalElements(),
                pageModel.totalPages(),
                pageModel.page(),
                pageModel.size()
        );
    }
}
