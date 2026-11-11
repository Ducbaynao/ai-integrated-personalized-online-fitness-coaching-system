package com.fitnesscoaching.platform.modules.support.adapter.in.web.dto;

import java.util.UUID;

public record AssignSupportCaseRequest(
        UUID assignedToAdminId,
        UUID assignedTo
) {
    public UUID getEffectiveAssignedTo() {
        return assignedToAdminId != null ? assignedToAdminId : assignedTo;
    }
}
