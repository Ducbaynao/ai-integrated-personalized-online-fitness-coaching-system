package com.fitnesscoaching.platform.modules.administration.privileged.application.model;

import java.util.List;

public record PrivilegedAccessPage(
        List<PrivilegedAccessModel> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
}
