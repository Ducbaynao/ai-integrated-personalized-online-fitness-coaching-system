package com.fitnesscoaching.platform.modules.audit.application.model;

import java.util.List;

public record AuditLogPage(
        List<AuditLogEntry> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
}
