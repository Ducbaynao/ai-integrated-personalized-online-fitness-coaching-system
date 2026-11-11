package com.fitnesscoaching.platform.modules.administration.application.model;

import java.util.List;

public record AdminUserPage(
        List<AdminUserSummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
