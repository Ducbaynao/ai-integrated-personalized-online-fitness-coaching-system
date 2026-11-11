package com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.administration.privileged.application.model.PrivilegedAccessPage;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public record PrivilegedAccessPageResponse(
        List<PrivilegedAccessResponse> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
    public static PrivilegedAccessPageResponse fromDomain(PrivilegedAccessPage pageModel, Instant now) {
        if (pageModel == null) return new PrivilegedAccessPageResponse(Collections.emptyList(), 0, 0, 0, 0);
        List<PrivilegedAccessResponse> items = pageModel.content() != null
                ? pageModel.content().stream().map(m -> PrivilegedAccessResponse.fromDomain(m, now)).toList()
                : Collections.emptyList();
        return new PrivilegedAccessPageResponse(
                items,
                pageModel.totalElements(),
                pageModel.totalPages(),
                pageModel.page(),
                pageModel.size()
        );
    }
}
